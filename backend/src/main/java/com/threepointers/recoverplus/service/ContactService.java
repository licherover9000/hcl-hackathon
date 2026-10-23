package com.threepointers.recoverplus.service;

import com.threepointers.recoverplus.engine.RulesConfig;
import com.threepointers.recoverplus.io.BookLoader;
import com.threepointers.recoverplus.security.CurrentUser;
import com.threepointers.recoverplus.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contact logging with the fair-practice checks enforced on the server (HTTP 422 with a reason):
 * contact window 08:00-19:00 IST, max calls and field visits per loan per day, no contact on hold,
 * PTP date within 15 days of the contact, PTP amount above 0 and not above total dues.
 * Every blocked attempt is audited too (CONTACT_BLOCKED), so the dashboard can count them.
 */
@Service
public class ContactService {

    public static final Set<String> CHANNELS = Set.of("CALL", "FIELD_VISIT", "SMS", "IVR");
    public static final Set<String> OUTCOMES = Set.of("DELIVERED", "PTP", "NO_ANSWER", "REFUSED", "CALLBACK", "UNREACHABLE", "WRONG_NUMBER");

    private final JdbcTemplate jdbc;
    private final ConfigService config;
    private final LoanService loans;
    private final AuditService audit;

    public ContactService(JdbcTemplate jdbc, ConfigService config, LoanService loans, AuditService audit) {
        this.jdbc = jdbc;
        this.config = config;
        this.loans = loans;
        this.audit = audit;
    }

    public record ContactRequest(String channel, String contactTs, String outcome, String ptpDate, BigDecimal ptpAmount, String notes) {
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> log(CurrentUser user, String loanId, ContactRequest req) {
        user.require("COLLECTOR", "MANAGER");
        LocalDate asOf = loans.requireLatest();
        loans.checkAccess(user, loanId, asOf);
        RulesConfig cfg = config.current();

        String channel = upper(req.channel());
        String outcome = upper(req.outcome());
        if (!CHANNELS.contains(channel)) {
            throw ApiException.badRequest("channel must be one of " + CHANNELS);
        }
        if (outcome == null || !OUTCOMES.contains(outcome)) {
            throw ApiException.badRequest("outcome must be one of " + OUTCOMES);
        }
        OffsetDateTime ts = req.contactTs() == null || req.contactTs().isBlank()
                ? OffsetDateTime.now(BookLoader.IST) : BookLoader.timestamp(req.contactTs(), null);
        LocalTime local = ts.atZoneSameInstant(BookLoader.IST).toLocalTime();
        LocalDate day = ts.atZoneSameInstant(BookLoader.IST).toLocalDate();

        Map<String, Object> pos = jdbc.queryForList("SELECT contact_strategy, total_dues FROM loan_positions WHERE as_of_date = ? AND loan_id = ?",
                Date.valueOf(asOf), loanId).get(0);

        if ("HOLD".equals(pos.get("contact_strategy"))) {
            block(user, loanId, "ON_HOLD", "Account is on hold (dispute or deceased): no contact allowed", req);
        }
        if (local.isBefore(cfg.contactWindowStart()) || local.isAfter(cfg.contactWindowEnd())) {
            block(user, loanId, "OUTSIDE_CONTACT_HOURS", "Contact allowed only between " + cfg.contactWindowStart() + " and "
                    + cfg.contactWindowEnd() + " IST (attempted " + local + ")", req);
        }
        Integer sameDay = jdbc.queryForObject("SELECT count(*) FROM contacts WHERE loan_id = ? AND channel = ? "
                + "AND (contact_ts AT TIME ZONE 'Asia/Kolkata')::date = ?", Integer.class, loanId, channel, Date.valueOf(day));
        if ("CALL".equals(channel) && sameDay != null && sameDay >= cfg.maxCallsPerDay()) {
            block(user, loanId, "CALL_LIMIT", "Already " + sameDay + " calls on " + day + " (limit " + cfg.maxCallsPerDay() + " per loan per day)", req);
        }
        if ("FIELD_VISIT".equals(channel) && sameDay != null && sameDay >= cfg.maxFieldVisitsPerDay()) {
            block(user, loanId, "VISIT_LIMIT", "Already " + sameDay + " field visit on " + day + " (limit "
                    + cfg.maxFieldVisitsPerDay() + " per loan per day)", req);
        }
        LocalDate ptpDate = null;
        if ("PTP".equals(outcome)) {
            if (req.ptpDate() == null || req.ptpAmount() == null) {
                block(user, loanId, "PTP_INCOMPLETE", "A promise to pay needs a PTP date and amount", req);
            }
            ptpDate = LocalDate.parse(req.ptpDate());
            if (ptpDate.isBefore(day) || ptpDate.isAfter(day.plusDays(cfg.ptpMaxDaysAhead()))) {
                block(user, loanId, "PTP_DATE", "PTP date must be within " + cfg.ptpMaxDaysAhead() + " days of the contact date", req);
            }
            BigDecimal dues = (BigDecimal) pos.get("total_dues");
            if (req.ptpAmount().signum() <= 0 || req.ptpAmount().compareTo(dues) > 0) {
                block(user, loanId, "PTP_AMOUNT", "PTP amount must be above 0 and not above total dues ₹" + dues.toPlainString(), req);
            }
        }

        String id = "CT-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO contacts (contact_id, loan_id, contact_ts, channel, outcome, ptp_date, ptp_amount, collector_id, notes, "
                        + "ptp_status, source) VALUES (?,?,?,?,?,?,?,?,?,?, 'app')",
                id, loanId, Timestamp.from(ts.toInstant()), channel, outcome, ptpDate == null ? null : Date.valueOf(ptpDate),
                "PTP".equals(outcome) ? req.ptpAmount() : null, user.userId(), req.notes(), "PTP".equals(outcome) ? "OPEN" : null);
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("contactId", id);
        saved.put("loanId", loanId);
        saved.put("channel", channel);
        saved.put("outcome", outcome);
        saved.put("contactTs", ts.toString());
        saved.put("ptpDate", ptpDate == null ? null : ptpDate.toString());
        saved.put("ptpAmount", req.ptpAmount());
        audit.log(user, "CONTACT", id, "CONTACT_LOGGED", null, saved, "loan " + loanId);
        return saved;
    }

    private void block(CurrentUser user, String loanId, String code, String message, ContactRequest req) {
        audit.log(user, "CONTACT", loanId, "CONTACT_BLOCKED", null, req, code + ": " + message);
        throw ApiException.unprocessable(code, message);
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
