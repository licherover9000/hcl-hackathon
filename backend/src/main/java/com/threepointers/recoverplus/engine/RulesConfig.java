package com.threepointers.recoverplus.engine;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every threshold the rules use. Values come from the rules_config table
 * (loaded from case_03_rules_config_V1.0.json); the defaults below are the
 * case document's defaults and are used only when a key is missing.
 *
 * Keys in the JSON are flattened to dotted paths ("buckets.sma_0.max").
 * A setting is found when a flattened key ends with one of its candidate
 * names (compared lower case, letters and digits only). defaultsUsed()
 * lists every setting that fell back to its default, so the day-end log
 * shows immediately if a key name did not match.
 */
public record RulesConfig(
        BigDecimal toleranceAmount,
        BigDecimal penalRatePct,
        int sma0MaxDpd,
        int sma1MaxDpd,
        int sma2MaxDpd,
        int digitalMaxDpd,
        int brokenPtpWindowDays,
        int ptpGraceDays,
        int brokenPtpEscalationCount,
        BigDecimal highValueDues,
        int preLegalDpd,
        int settlementMinDpdSma2,
        BigDecimal managerLimitPct,
        BigDecimal headLimitPct,
        int settlementValidityDays,
        LocalTime contactWindowStart,
        LocalTime contactWindowEnd,
        int maxCallsPerDay,
        int maxFieldVisitsPerDay,
        int ptpMaxDaysAhead,
        BigDecimal aiEarlyWarningThreshold,
        List<String> defaultsUsed) {

    public static RulesConfig defaults() {
        return fromMap(Map.of());
    }

    public static RulesConfig fromMap(Map<String, String> raw) {
        Lookup l = new Lookup(raw);
        return new RulesConfig(
                l.dec("toleranceAmount", "100", "tolerance", "unpaid_tolerance", "tolerance_amount", "tolerance_inr", "unpaid_tolerance_inr"),
                l.dec("penalRatePct", "24", "penal_rate", "penal_rate_pct", "penal_rate_pa", "penal_charge_rate", "penal_rate_percent", "penal_interest_rate"),
                l.integer("sma0MaxDpd", "30", "buckets.SMA_0[1]", "sma_0_max", "sma0_max", "sma_0_max_dpd", "sma0_max_dpd", "sma_0.max"),
                l.integer("sma1MaxDpd", "60", "buckets.SMA_1[1]", "sma_1_max", "sma1_max", "sma_1_max_dpd", "sma1_max_dpd", "sma_1.max"),
                l.integer("sma2MaxDpd", "90", "buckets.SMA_2[1]", "sma_2_max", "sma2_max", "sma_2_max_dpd", "sma2_max_dpd", "sma_2.max", "npa_min_dpd_exclusive"),
                l.integer("digitalMaxDpd", "7", "digital_max_dpd", "digital_reminder_max_dpd"),
                l.integer("brokenPtpWindowDays", "60", "broken_ptp_window_days", "broken_ptp_window", "ptp_window_days"),
                l.integer("ptpGraceDays", "2", "ptp_grace_days", "broken_ptp_grace_days", "grace_days", "ptp_grace"),
                l.integer("brokenPtpEscalationCount", "2", "broken_ptp_escalation_count", "escalation_count", "broken_ptp_escalate_count", "broken_ptp_repeat_count"),
                l.dec("highValueDues", "500000", "high_value_total_dues", "high_value_dues", "high_value_threshold", "high_value"),
                l.integer("preLegalDpd", "75", "pre_legal_notice_dpd", "pre_legal_dpd", "prelegal_dpd"),
                l.integer("settlementMinDpdSma2", "75", "settlement_min_dpd_sma2", "settlement_min_dpd", "settlement_sma2_min_dpd"),
                l.dec("managerLimitPct", "20", "manager_discount_limit", "manager_limit_pct", "manager_max_discount_pct", "manager_limit", "collections_manager_limit_pct"),
                l.dec("headLimitPct", "40", "head_discount_limit", "head_limit_pct", "head_max_discount_pct", "head_limit", "head_of_collections_limit_pct", "max_discount_pct"),
                l.integer("settlementValidityDays", "30", "settlement_validity_days", "validity_days"),
                l.time("contactWindowStart", "08:00", "contact_window_start", "contact_start", "contact_hours_start"),
                l.time("contactWindowEnd", "19:00", "contact_window_end", "contact_end", "contact_hours_end"),
                l.integer("maxCallsPerDay", "3", "max_calls_per_day", "calls_per_day", "max_calls_per_loan_per_day"),
                l.integer("maxFieldVisitsPerDay", "1", "max_field_visits_per_day", "field_visits_per_day", "max_visits_per_day"),
                l.integer("ptpMaxDaysAhead", "15", "ptp_max_days", "ptp_max_days_ahead", "ptp_window_max_days"),
                l.dec("aiEarlyWarningThreshold", "0.60", "ai_early_warning_threshold", "early_warning_threshold", "p_miss_threshold", "ai_threshold"),
                Collections.unmodifiableList(l.defaultsUsed));
    }

    private static final class Lookup {
        private final Map<String, String> raw;
        private final List<String> defaultsUsed = new ArrayList<>();

        Lookup(Map<String, String> raw) {
            this.raw = raw;
        }

        private static String norm(String s) {
            return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }

        private String find(String name, String def, String... candidates) {
            for (String c : candidates) {
                String nc = norm(c);
                for (Map.Entry<String, String> e : raw.entrySet()) {
                    String nk = norm(e.getKey());
                    if ((nk.equals(nc) || nk.endsWith(nc)) && e.getValue() != null && !e.getValue().isBlank()) {
                        return e.getValue().trim();
                    }
                }
            }
            defaultsUsed.add(name);
            return def;
        }

        BigDecimal dec(String name, String def, String... c) {
            String v = find(name, def, c).replace("%", "").replace(",", "");
            return new BigDecimal(v);
        }

        int integer(String name, String def, String... c) {
            return new BigDecimal(find(name, def, c).replace(",", "")).intValueExact();
        }

        LocalTime time(String name, String def, String... c) {
            String v = find(name, def, c);
            if (v.length() == 4) {
                v = "0" + v;
            }
            return LocalTime.parse(v.length() > 5 ? v.substring(0, 5) : v);
        }
    }
}
