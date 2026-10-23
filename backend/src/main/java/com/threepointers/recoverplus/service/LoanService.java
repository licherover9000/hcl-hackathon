package com.threepointers.recoverplus.service;

import com.threepointers.recoverplus.engine.EngineModels.ContactRec;
import com.threepointers.recoverplus.engine.EngineModels.InstalmentState;
import com.threepointers.recoverplus.engine.EngineModels.LoanTerms;
import com.threepointers.recoverplus.engine.EngineModels.PaymentRec;
import com.threepointers.recoverplus.engine.EngineModels.Position;
import com.threepointers.recoverplus.engine.PositionCalculator;
import com.threepointers.recoverplus.engine.RulesConfig;
import com.threepointers.recoverplus.engine.EngineModels.Instalment;
import com.threepointers.recoverplus.io.BookLoader;
import com.threepointers.recoverplus.security.CurrentUser;
import com.threepointers.recoverplus.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Worklists and the account 360 view, with object-level access checks on the server. */
@Service
public class LoanService {

    private final JdbcTemplate jdbc;
    private final ConfigService config;
    private final DayEndService dayEnd;

    public LoanService(JdbcTemplate jdbc, ConfigService config, DayEndService dayEnd) {
        this.jdbc = jdbc;
        this.config = config;
        this.dayEnd = dayEnd;
    }

    public static String maskMobile(String m) {
        if (m == null || m.length() < 4) {
            return m;
        }
        return m.substring(0, 2) + "X".repeat(m.length() - 4) + m.substring(m.length() - 2);
    }

    LocalDate requireLatest() {
        LocalDate d = dayEnd.latestAsOf();
        if (d == null) {
            throw ApiException.notFound("No day-end has been run yet. Run the day-end first (Day-end and data screen).");
        }
        return d;
    }

    /**
     * Collector: may open a loan assigned to them, or in their queue in their city.
     * Manager, head, auditor: any loan. Anything else is 403.
     */
    public void checkAccess(CurrentUser user, String loanId, LocalDate asOf) {
        if (!user.is("COLLECTOR")) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.assigned_to, p.queue, c.city FROM loan_positions p JOIN loans l ON l.loan_id = p.loan_id "
                        + "JOIN customers c ON c.customer_id = l.customer_id WHERE p.as_of_date = ? AND p.loan_id = ?",
                Date.valueOf(asOf), loanId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Loan " + loanId + " not found");
        }
        Map<String, Object> r = rows.get(0);
        boolean assigned = user.userId().equals(r.get("assigned_to"));
        boolean sameQueue = user.queue() != null && user.queue().equalsIgnoreCase(String.valueOf(r.get("queue")))
                && (user.city() == null || user.city().equalsIgnoreCase(String.valueOf(r.get("city"))));
        if (!assigned && !sameQueue) {
            throw ApiException.forbidden("Loan " + loanId + " is outside your queue and city");
        }
    }

    public Map<String, Object> worklist(CurrentUser user, String mode, String queue) {
        LocalDate asOf = requireLatest();
        boolean ai = "ai".equalsIgnoreCase(mode);
        Boolean aiAvailable = jdbc.queryForObject("SELECT coalesce(bool_or(p_miss_next IS NOT NULL), false) FROM loan_positions WHERE as_of_date = ?",
                Boolean.class, Date.valueOf(asOf));
        boolean useAi = ai && Boolean.TRUE.equals(aiAvailable);
        String rankCol = useAi ? "coalesce(p.ai_rank, p.worklist_rank)" : "p.worklist_rank";
        String queueCol = useAi ? "coalesce(p.ai_queue, p.queue)" : "p.queue";
        StringBuilder sql = new StringBuilder("SELECT p.loan_id, c.full_name, c.mobile, c.city, l.product, l.branch, p.bucket, p.dpd, "
                + "p.overdue_amount, p.penal_charges, p.total_dues, p.contact_strategy, p.queue, p.codes, p.worklist_rank, p.assigned_to, "
                + "p.broken_ptp_count, p.p_miss_next, p.expected_recovery, p.ai_rank, p.ai_strategy, p.ai_queue, p.ai_codes, p.top_factors, "
                + rankCol + " AS shown_rank, " + queueCol + " AS shown_queue "
                + "FROM loan_positions p JOIN loans l ON l.loan_id = p.loan_id JOIN customers c ON c.customer_id = l.customer_id "
                + "WHERE p.as_of_date = ? AND " + queueCol + " IS NOT NULL");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(asOf));
        if (user.is("COLLECTOR")) {
            sql.append(" AND p.assigned_to = ?");
            args.add(user.userId());
        } else if (queue != null && !queue.isBlank()) {
            sql.append(" AND ").append(queueCol).append(" = ?");
            args.add(queue);
        }
        sql.append(" ORDER BY shown_queue, shown_rank NULLS LAST, p.loan_id LIMIT 1000");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        rows.forEach(r -> r.put("mobile", maskMobile((String) r.get("mobile"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOfDate", asOf.toString());
        out.put("mode", useAi ? "ai" : "rules");
        out.put("aiAvailable", aiAvailable);
        out.put("message", ai && !useAi ? "AI ranking unavailable: showing rules order" : null);
        out.put("rows", rows);
        return out;
    }

    public List<Map<String, Object>> queueSummary() {
        LocalDate asOf = requireLatest();
        return jdbc.queryForList("SELECT queue, count(*) AS loans, sum(total_dues) AS total_dues, count(assigned_to) AS assigned "
                + "FROM loan_positions WHERE as_of_date = ? AND queue IS NOT NULL GROUP BY queue ORDER BY queue", Date.valueOf(asOf));
    }

    public Map<String, Object> account(CurrentUser user, String loanId) {
        LocalDate asOf = requireLatest();
        checkAccess(user, loanId, asOf);
        List<Map<String, Object>> loanRows = jdbc.queryForList("SELECT l.*, c.full_name, c.mobile, c.city, c.preferred_language, "
                + "c.occupation_type, c.dispute_flag, c.deceased_flag FROM loans l JOIN customers c ON c.customer_id = l.customer_id "
                + "WHERE l.loan_id = ?", loanId);
        if (loanRows.isEmpty()) {
            throw ApiException.notFound("Loan " + loanId + " not found");
        }
        Map<String, Object> loan = loanRows.get(0);
        loan.put("mobile", maskMobile((String) loan.get("mobile")));
        Map<String, Object> position = jdbc.queryForList("SELECT * FROM loan_positions WHERE as_of_date = ? AND loan_id = ?",
                Date.valueOf(asOf), loanId).stream().findFirst().orElse(Map.of());

        // Recompute the instalment-level view (FIFO allocation and penal per instalment) with the same engine.
        RulesConfig cfg = config.current();
        LoanTerms terms = new LoanTerms(loanId, (String) loan.get("customer_id"), (String) loan.get("product"),
                (BigDecimal) loan.get("principal"), (BigDecimal) loan.get("emi_amount"), (String) loan.get("branch"));
        List<Instalment> schedule = jdbc.query("SELECT * FROM repayment_schedule WHERE loan_id = ? ORDER BY instalment_no",
                (rs, i) -> new Instalment(rs.getInt("instalment_no"), rs.getDate("due_date").toLocalDate(),
                        rs.getBigDecimal("emi_amount"), rs.getBigDecimal("closing_principal")), loanId);
        List<PaymentRec> payments = jdbc.query("SELECT * FROM payments WHERE loan_id = ? ORDER BY payment_date, payment_id",
                (rs, i) -> new PaymentRec(rs.getString("payment_id"), rs.getDate("payment_date").toLocalDate(), rs.getBigDecimal("amount")), loanId);
        List<ContactRec> contacts = jdbc.query("SELECT * FROM contacts WHERE loan_id = ? ORDER BY contact_ts",
                (rs, i) -> {
                    OffsetDateTime ts = rs.getObject("contact_ts", OffsetDateTime.class);
                    return new ContactRec(rs.getString("contact_id"), ts, ts.atZoneSameInstant(BookLoader.IST).toLocalDate(),
                            rs.getString("channel"), rs.getString("outcome"),
                            rs.getDate("ptp_date") == null ? null : rs.getDate("ptp_date").toLocalDate(),
                            rs.getBigDecimal("ptp_amount"), rs.getString("collector_id"));
                }, loanId);
        List<ContactRec> before = contacts.stream().filter(c -> c.contactDate().isBefore(asOf)).toList();
        Position pos = PositionCalculator.calculate(terms, schedule, payments, before, asOf, cfg);

        List<Map<String, Object>> instalments = new ArrayList<>();
        for (InstalmentState s : pos.instalments()) {
            if (s.instalment().dueDate().isAfter(asOf.plusMonths(2))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instalmentNo", s.instalment().instalmentNo());
            m.put("dueDate", s.instalment().dueDate().toString());
            m.put("emi", s.instalment().emiAmount());
            m.put("paid", s.paid());
            m.put("unpaid", s.unpaid());
            m.put("status", s.overdue() ? "OVERDUE" : !s.due() ? "NOT_DUE" : s.unpaid().signum() > 0 ? "WITHIN_TOLERANCE" : "PAID");
            m.put("daysOverdue", s.daysOverdue());
            m.put("penal", s.penalUnrounded().setScale(2, java.math.RoundingMode.HALF_UP));
            instalments.add(m);
        }
        List<Map<String, Object>> contactRows = jdbc.queryForList("SELECT contact_id, contact_ts, channel, outcome, ptp_date, ptp_amount, "
                + "ptp_status, collector_id, notes FROM contacts WHERE loan_id = ? ORDER BY contact_ts DESC", loanId);
        List<Map<String, Object>> settlements = jdbc.queryForList("SELECT request_id, request_date, offer_amount, discount_pct, route, "
                + "reason_code, status, proposed_by, decided_by FROM settlements WHERE loan_id = ? ORDER BY request_date DESC", loanId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOfDate", asOf.toString());
        out.put("loan", loan);
        out.put("position", position);
        out.put("explanation", explain(position, cfg));
        out.put("instalments", instalments);
        out.put("payments", payments.stream().map(p -> Map.of("paymentId", p.paymentId(), "date", p.paymentDate().toString(),
                "amount", p.amount())).toList());
        out.put("contacts", contactRows);
        out.put("settlements", settlements);
        out.put("settlementEligible", isSettlementEligible(position, cfg));
        return out;
    }

    static boolean isSettlementEligible(Map<String, Object> p, RulesConfig cfg) {
        if (p.isEmpty() || "HOLD".equals(p.get("contact_strategy"))) {
            return false;
        }
        String bucket = (String) p.get("bucket");
        int dpd = ((Number) p.get("dpd")).intValue();
        return "NPA".equals(bucket) || ("SMA_2".equals(bucket) && dpd >= cfg.settlementMinDpdSma2());
    }

    /** Plain-language reasons for the strategy and every code, for the "Why this action" panel. */
    static List<String> explain(Map<String, Object> p, RulesConfig cfg) {
        List<String> out = new ArrayList<>();
        if (p.isEmpty()) {
            return out;
        }
        String bucket = (String) p.get("bucket");
        int dpd = ((Number) p.get("dpd")).intValue();
        out.add("Bucket " + bucket + " (" + dpd + " days past due).");
        String strategy = (String) p.get("contact_strategy");
        String codes = Objects.toString(p.get("codes"), "");
        switch (bucket) {
            case "CURRENT" -> out.add("No action: the loan is not overdue.");
            case "SMA_0" -> out.add(dpd <= cfg.digitalMaxDpd() ? "SMA_0 up to " + cfg.digitalMaxDpd() + " days: digital reminder (SMS and IVR)."
                    : "SMA_0 after " + cfg.digitalMaxDpd() + " days: telecall.");
            case "SMA_1" -> out.add(((Number) p.get("broken_ptp_count")).intValue() >= 1
                    ? "SMA_1 with a broken promise to pay: field visit." : "SMA_1: telecall.");
            case "SMA_2" -> out.add("SMA_2: field visit.");
            default -> out.add("NPA (over " + cfg.sma2MaxDpd() + " days): recovery.");
        }
        if (codes.contains("BROKEN_PTP_REPEAT")) {
            out.add(p.get("broken_ptp_count") + " broken promises in the last " + cfg.brokenPtpWindowDays() + " days: escalated one step.");
        }
        if (codes.contains("HIGH_VALUE")) {
            out.add("Total dues at or above ₹" + cfg.highValueDues().toPlainString() + ": senior collector queue.");
        }
        if (codes.contains("PRE_LEGAL_NOTICE")) {
            out.add(cfg.preLegalDpd() + "+ days past due in SMA_2: pre-legal notice generated.");
        }
        if (codes.contains("SKIP_TRACE")) {
            out.add("Last 3 contacts were wrong number or unreachable: flagged for skip tracing.");
        }
        if (codes.contains("HOLD_DISPUTE")) {
            out.add("Customer has raised a dispute: account on hold, no contact allowed.");
        }
        if (codes.contains("HOLD_DECEASED")) {
            out.add("Customer is deceased: account on hold with the claims desk, no contact allowed.");
        }
        out.add("Final action: " + strategy + (p.get("queue") == null ? "" : ", queue " + p.get("queue")) + ".");
        return out;
    }
}
