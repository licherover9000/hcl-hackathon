package com.threepointers.recoverplus.service;

import com.threepointers.recoverplus.data.BookRepository;
import com.threepointers.recoverplus.engine.DayEndProcessor;
import com.threepointers.recoverplus.engine.DayEndProcessor.SettlementResult;
import com.threepointers.recoverplus.engine.RulesConfig;
import com.threepointers.recoverplus.io.Book;
import com.threepointers.recoverplus.io.BookLoader;
import com.threepointers.recoverplus.io.OutputWriter;
import com.threepointers.recoverplus.security.CurrentUser;
import com.threepointers.recoverplus.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Settlement maker-checker (section 6.1.1 lifecycle):
 * PROPOSED -> PENDING_MANAGER (<= 20%) / PENDING_HEAD (<= 40%) / AUTO_REJECTED, then APPROVED or REJECTED by a
 * different user, then PAID or LAPSED at day-end. The proposer can never approve (403). Two approvers acting
 * at once: the update is conditional on status and version, so only one succeeds; the other gets 409.
 */
@Service
public class SettlementService {

    private final JdbcTemplate jdbc;
    private final BookRepository repo;
    private final ConfigService config;
    private final LoanService loans;
    private final AuditService audit;
    private final Path dataDir;
    private final Path outputDir;

    public SettlementService(JdbcTemplate jdbc, BookRepository repo, ConfigService config, LoanService loans, AuditService audit,
                             @Value("${app.data-dir}") String dataDir, @Value("${app.output-dir}") String outputDir) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.config = config;
        this.loans = loans;
        this.audit = audit;
        this.dataDir = Path.of(dataDir);
        this.outputDir = Path.of(outputDir);
    }

    public record ProposeRequest(String loanId, BigDecimal offerAmount, String reason) {
    }

    public record DecisionRequest(String decision, String comment, Integer version) {
    }

    @Transactional
    public Map<String, Object> propose(CurrentUser user, ProposeRequest req) {
        user.require("COLLECTOR", "MANAGER");
        if (req.offerAmount() == null || req.offerAmount().signum() <= 0) {
            throw ApiException.unprocessable("OFFER_INVALID", "Offer amount must be greater than 0");
        }
        LocalDate asOf = loans.requireLatest();
        loans.checkAccess(user, req.loanId(), asOf);
        String id = "S-" + System.currentTimeMillis();
        Book.SettlementRequest r = new Book.SettlementRequest(id, req.loanId(), asOf, user.userId(), req.offerAmount());
        SettlementResult res = evaluate(List.of(r)).get(0);
        insert(res, req.reason());
        audit.log(user, "SETTLEMENT", id, "SETTLEMENT_PROPOSED", null, toMap(res), req.reason());
        return toMap(res);
    }

    /** Evaluates requests on each loan's position at its request date, using the same engine as the batch. */
    List<SettlementResult> evaluate(List<Book.SettlementRequest> requests) {
        Book book = repo.loadBook();
        RulesConfig cfg = RulesConfig.fromMap(book.config);
        requests.forEach(r -> book.settlementRequests.put(r.requestId(), r));
        return DayEndProcessor.settlements(book, cfg);
    }

    private void insert(SettlementResult s, String reason) {
        jdbc.update("INSERT INTO settlements (request_id, loan_id, request_date, proposed_by, offer_amount, dues_at_request, "
                        + "discount_pct, eligible, route, reason_code, status, proposal_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (request_id) DO NOTHING",
                s.request().requestId(), s.request().loanId(), Date.valueOf(s.request().requestDate()), s.request().proposedBy(),
                s.request().offerAmount(), s.positionAtRequest().totalDues(), s.decision().discountPct(), s.decision().eligible(),
                s.decision().route(), emptyToNull(s.decision().reasonCode()), s.status(), reason);
    }

    @Transactional
    public Map<String, Object> decide(CurrentUser user, String requestId, DecisionRequest req) {
        user.require("MANAGER", "HEAD");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM settlements WHERE request_id = ?", requestId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Settlement " + requestId + " not found");
        }
        Map<String, Object> s = rows.get(0);
        String status = (String) s.get("status");
        if (user.userId().equals(s.get("proposed_by"))) {
            audit.log(user, "SETTLEMENT", requestId, "APPROVAL_BLOCKED_SELF", null, null, "Proposer tried to decide own request");
            throw ApiException.forbidden("You proposed this settlement, so you cannot approve or reject it (maker and checker must differ)");
        }
        if ("PENDING_HEAD".equals(status) && !user.is("HEAD")) {
            throw ApiException.forbidden("Discount above the manager limit: only the Head of Collections can decide this one");
        }
        if (!"PENDING_MANAGER".equals(status) && !"PENDING_HEAD".equals(status)) {
            throw ApiException.conflict("Settlement is already " + status);
        }
        String decision = req.decision() == null ? "" : req.decision().trim().toUpperCase(Locale.ROOT);
        if (!decision.equals("APPROVE") && !decision.equals("REJECT")) {
            throw ApiException.badRequest("decision must be APPROVE or REJECT");
        }
        if (req.comment() == null || req.comment().trim().length() < 10) {
            throw ApiException.unprocessable("COMMENT_TOO_SHORT", "A comment of at least 10 characters is required");
        }
        int version = req.version() == null ? ((Number) s.get("version")).intValue() : req.version();
        LocalDate businessDate = Optional.ofNullable(loans.requireLatest()).orElse(LocalDate.now(BookLoader.IST));
        String newStatus = decision.equals("APPROVE") ? "APPROVED" : "REJECTED";
        int validity = config.current().settlementValidityDays();
        LocalDate validUntil = decision.equals("APPROVE") ? businessDate.plusDays(validity) : null;
        String letter = decision.equals("APPROVE")
                ? Templates.settlementLetter(requestId, (String) s.get("loan_id"), (BigDecimal) s.get("offer_amount"), validUntil) : null;

        int updated = jdbc.update("UPDATE settlements SET status = ?, decided_by = ?, decided_on = ?, decided_at = now(), "
                        + "decision_comment = ?, letter_text = ?, valid_until = ?, version = version + 1 "
                        + "WHERE request_id = ? AND status = ? AND version = ?",
                newStatus, user.userId(), Date.valueOf(businessDate), req.comment().trim(), letter,
                validUntil == null ? null : Date.valueOf(validUntil), requestId, status, version);
        if (updated == 0) {
            throw ApiException.conflict("Someone else already acted on this settlement. Refresh to see its current status.");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("requestId", requestId);
        after.put("status", newStatus);
        after.put("decidedBy", user.userId());
        after.put("validUntil", validUntil == null ? null : validUntil.toString());
        after.put("letter", letter);
        audit.log(user, "SETTLEMENT", requestId, "SETTLEMENT_" + newStatus, Map.of("status", status), after, req.comment());
        return after;
    }

    public List<Map<String, Object>> list(CurrentUser user, String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM settlements WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (user.is("COLLECTOR")) {
            sql.append(" AND proposed_by = ?");
            args.add(user.userId());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY request_date DESC, request_id DESC LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** Batch: evaluates the sample or evaluation request file, stores it, writes settlement_decisions.csv. */
    @Transactional
    public Map<String, Object> evaluateFile(boolean evalFile) throws IOException {
        BookLoader loader = new BookLoader(dataDir);
        Book tmp = loader.loadSeed();
        if (evalFile) {
            loader.loadEvaluation(tmp);
        }
        List<SettlementResult> results = evaluate(new ArrayList<>(tmp.settlementRequests.values()));
        results.forEach(r -> insert(r, "imported from data pack"));
        OutputWriter.writeSettlements(outputDir.resolve("settlement_decisions.csv"), results);
        audit.log(null, "SETTLEMENT", evalFile ? "EVAL_FILE" : "SAMPLE_FILE", "SETTLEMENT_FILE_EVALUATED", null,
                Map.of("requests", results.size()), null);
        return Map.of("requests", results.size(), "file", outputDir.resolve("settlement_decisions.csv").toString());
    }

    private static Map<String, Object> toMap(SettlementResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", r.request().requestId());
        m.put("loanId", r.request().loanId());
        m.put("requestDate", r.request().requestDate().toString());
        m.put("offerAmount", r.request().offerAmount());
        m.put("totalDues", r.positionAtRequest().totalDues());
        m.put("bucket", r.positionAtRequest().bucket());
        m.put("dpd", r.positionAtRequest().dpd());
        m.put("eligible", r.decision().eligible());
        m.put("discountPct", r.decision().discountPct());
        m.put("route", r.decision().route());
        m.put("reasonCode", r.decision().reasonCode());
        m.put("status", r.status());
        return m;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
