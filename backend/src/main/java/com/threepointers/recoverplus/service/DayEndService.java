package com.threepointers.recoverplus.service;

import com.threepointers.recoverplus.data.BookRepository;
import com.threepointers.recoverplus.engine.DayEndProcessor;
import com.threepointers.recoverplus.engine.DayEndProcessor.AiScore;
import com.threepointers.recoverplus.engine.DayEndProcessor.LoanResult;
import com.threepointers.recoverplus.engine.EngineModels.PtpResult;
import com.threepointers.recoverplus.engine.RulesConfig;
import com.threepointers.recoverplus.io.Book;
import com.threepointers.recoverplus.io.OutputWriter;
import com.threepointers.recoverplus.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The day-end run for one as-of date: loads the book from PostgreSQL, asks the ML service for
 * scores (falls back to rules if it fails), runs the pure engine, then in ONE transaction replaces
 * that date's positions, updates PTP statuses, writes outbox messages and pre-legal letters,
 * moves approved settlements to PAID or LAPSED, and writes the output CSVs. Re-running a date
 * gives the same result.
 */
@Service
public class DayEndService {

    private static final Logger log = LoggerFactory.getLogger(DayEndService.class);

    private final BookRepository repo;
    private final JdbcTemplate jdbc;
    private final ConfigService config;
    private final MlClient ml;
    private final AuditService audit;
    private final Path outputDir;

    public DayEndService(BookRepository repo, JdbcTemplate jdbc, ConfigService config, MlClient ml, AuditService audit,
                         @Value("${app.output-dir}") String outputDir) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.config = config;
        this.ml = ml;
        this.audit = audit;
        this.outputDir = Path.of(outputDir);
    }

    public LocalDate latestAsOf() {
        return jdbc.queryForObject("SELECT max(as_of_date) FROM loan_positions", LocalDate.class);
    }

    @Transactional
    public Map<String, Object> run(LocalDate asOf, CurrentUser user) throws Exception {
        long start = System.currentTimeMillis();
        Long runId = jdbc.queryForObject("INSERT INTO day_end_runs (as_of_date, run_by, status) VALUES (?,?, 'RUNNING') RETURNING run_id",
                Long.class, Date.valueOf(asOf), user == null ? "SYSTEM" : user.userId());

        Book book = repo.loadBook();
        RulesConfig cfg = RulesConfig.fromMap(book.config);
        Map<String, AiScore> scores = ml.score(book.loans.keySet());
        boolean aiAvailable = scores != null && !scores.isEmpty();
        List<LoanResult> results = DayEndProcessor.run(book, asOf, cfg, aiAvailable ? scores : null);

        Map<String, String> before = new HashMap<>();
        jdbc.query("SELECT loan_id, bucket, contact_strategy FROM loan_positions WHERE as_of_date = "
                + "(SELECT max(as_of_date) FROM loan_positions WHERE as_of_date < ?)", rs -> {
            before.put(rs.getString(1), rs.getString(2) + "/" + rs.getString(3));
        }, Date.valueOf(asOf));

        jdbc.update("DELETE FROM loan_positions WHERE as_of_date = ?", Date.valueOf(asOf));
        jdbc.batchUpdate("INSERT INTO loan_positions (as_of_date, loan_id, dpd, bucket, overdue_amount, penal_charges, total_dues, "
                        + "contact_strategy, queue, codes, worklist_rank, assigned_to, broken_ptp_count, p_miss_next, expected_recovery, "
                        + "ai_strategy, ai_queue, ai_codes, ai_rank, top_factors) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                results.stream().map(r -> new Object[]{Date.valueOf(asOf), r.loan().loanId(), r.position().dpd(), r.position().bucket(),
                        r.position().overdueAmount(), r.position().penalCharges(), r.position().totalDues(), r.rules().strategy(),
                        r.rules().queue(), r.rules().codesJoined(), r.rulesRank(), r.assignedTo(), r.position().brokenPtpCount(),
                        r.pMissNext(), r.expectedRecovery(), r.aiRules() == null ? null : r.aiRules().strategy(),
                        r.aiRules() == null ? null : r.aiRules().queue(), r.aiRules() == null ? null : r.aiRules().codesJoined(),
                        r.aiRank(), r.topFactors()}).toList());

        // PTP status as of this run (OPEN, KEPT, BROKEN).
        List<Object[]> ptpRows = new ArrayList<>();
        for (LoanResult r : results) {
            for (PtpResult p : r.position().ptps()) {
                ptpRows.add(new Object[]{p.status(), p.contactId()});
            }
        }
        jdbc.batchUpdate("UPDATE contacts SET ptp_status = ? WHERE contact_id = ?", ptpRows);

        int outbox = writeOutboxAndLetters(asOf, book, results);
        int settlementUpdates = updateSettlements(asOf, user);

        // Audit: the run, plus every loan whose bucket or strategy changed since the previous run.
        int changed = 0;
        for (LoanResult r : results) {
            String now = r.position().bucket() + "/" + r.rules().strategy();
            String prev = before.get(r.loan().loanId());
            if (prev != null && !prev.equals(now)) {
                audit.log(user, "LOAN", r.loan().loanId(), "DAY_END_RULE_CHANGE", prev, now, "as of " + asOf);
                changed++;
            }
        }

        OutputWriter.writeCollections(outputDir.resolve("collections_output.csv"), results);
        if (aiAvailable) {
            OutputWriter.writeRiskScores(outputDir.resolve("risk_scores.csv"), results);
        }

        Map<String, Integer> buckets = new LinkedHashMap<>();
        results.forEach(r -> buckets.merge(r.position().bucket(), 1, Integer::sum));
        long ms = System.currentTimeMillis() - start;
        jdbc.update("UPDATE day_end_runs SET finished_at = now(), loans = ?, ai_available = ?, status = 'DONE' WHERE run_id = ?",
                results.size(), aiAvailable, runId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", runId);
        summary.put("asOfDate", asOf.toString());
        summary.put("loans", results.size());
        summary.put("buckets", buckets);
        summary.put("aiAvailable", aiAvailable);
        summary.put("outboxMessages", outbox);
        summary.put("settlementStatusChanges", settlementUpdates);
        summary.put("loansChangedSincePreviousRun", changed);
        summary.put("configDefaultsUsed", cfg.defaultsUsed());
        summary.put("durationMs", ms);
        audit.log(user, "DAY_END", String.valueOf(runId), "DAY_END_RUN", null, summary, "as of " + asOf);
        log.info("Day-end {} done: {}", asOf, summary);
        return summary;
    }

    private int writeOutboxAndLetters(LocalDate asOf, Book book, List<LoanResult> results) {
        jdbc.update("DELETE FROM outbox WHERE as_of_date = ?", Date.valueOf(asOf));
        jdbc.update("DELETE FROM letters WHERE as_of_date = ? AND letter_type = 'PRE_LEGAL_NOTICE'", Date.valueOf(asOf));
        List<Object[]> msgs = new ArrayList<>();
        List<Object[]> letters = new ArrayList<>();
        for (LoanResult r : results) {
            Book.Customer c = book.customers.get(r.loan().customerId());
            String lang = c == null || c.preferredLanguage() == null ? "EN" : c.preferredLanguage();
            String strategy = r.aiRules() != null && "PRE_DUE_REMINDER".equals(r.aiRules().strategy()) ? "PRE_DUE_REMINDER" : r.rules().strategy();
            if ("DIGITAL_REMINDER".equals(strategy) || "PRE_DUE_REMINDER".equals(strategy)) {
                String text = Templates.reminder(strategy, lang, r.loan(), r.position());
                msgs.add(new Object[]{Date.valueOf(asOf), r.loan().loanId(), "SMS", lang, text});
                msgs.add(new Object[]{Date.valueOf(asOf), r.loan().loanId(), "IVR", lang, text});
            }
            if (r.rules().codes().contains("PRE_LEGAL_NOTICE")) {
                letters.add(new Object[]{Date.valueOf(asOf), r.loan().loanId(), "PRE_LEGAL_NOTICE",
                        Templates.preLegal(r.loan(), r.position(), asOf)});
            }
        }
        jdbc.batchUpdate("INSERT INTO outbox (as_of_date, loan_id, channel, language, message) VALUES (?,?,?,?,?)", msgs);
        jdbc.batchUpdate("INSERT INTO letters (as_of_date, loan_id, letter_type, body) VALUES (?,?,?,?)", letters);
        return msgs.size();
    }

    /** APPROVED settlements become PAID when payments since approval reach the offer within validity, else LAPSED. */
    private int updateSettlements(LocalDate asOf, CurrentUser user) {
        List<Map<String, Object>> approved = jdbc.queryForList(
                "SELECT request_id, loan_id, offer_amount, decided_on, valid_until FROM settlements WHERE status = 'APPROVED'");
        int changed = 0;
        for (Map<String, Object> s : approved) {
            LocalDate from = ((Date) s.get("decided_on")).toLocalDate();
            LocalDate until = ((Date) s.get("valid_until")).toLocalDate();
            LocalDate upTo = asOf.isBefore(until) ? asOf : until;
            BigDecimal paid = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM payments WHERE loan_id = ? "
                            + "AND payment_date >= ? AND payment_date <= ?", BigDecimal.class,
                    s.get("loan_id"), Date.valueOf(from), Date.valueOf(upTo));
            String next = paid.compareTo((BigDecimal) s.get("offer_amount")) >= 0 ? "PAID" : asOf.isAfter(until) ? "LAPSED" : null;
            if (next != null) {
                jdbc.update("UPDATE settlements SET status = ?, version = version + 1 WHERE request_id = ? AND status = 'APPROVED'",
                        next, s.get("request_id"));
                audit.log(user, "SETTLEMENT", (String) s.get("request_id"), "SETTLEMENT_" + next, "APPROVED", next,
                        "paid " + paid + " by " + upTo);
                changed++;
            }
        }
        return changed;
    }
}
