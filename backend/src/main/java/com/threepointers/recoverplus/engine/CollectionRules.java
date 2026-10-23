package com.threepointers.recoverplus.engine;

import com.threepointers.recoverplus.engine.EngineModels.ContactRec;
import com.threepointers.recoverplus.engine.EngineModels.CustomerFlags;
import com.threepointers.recoverplus.engine.EngineModels.Position;
import com.threepointers.recoverplus.engine.EngineModels.RuleResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collection rules R1 to R12 (rules-only result) and R13 (AI early warning), in the order
 * section 6.1 gives: base strategy R3 to R8, R9 escalation, R10 queue override, R11, R12,
 * then holds R1/R2, which replace strategy and queue but keep the other codes.
 */
public final class CollectionRules {

    /** Fixed reporting order of codes, as section 6.1 lists it. */
    public static final List<String> CODE_ORDER = List.of(
            "HOLD_DISPUTE", "HOLD_DECEASED", "BROKEN_PTP_REPEAT", "HIGH_VALUE",
            "PRE_LEGAL_NOTICE", "NPA_CLASSIFIED", "SKIP_TRACE", "EARLY_WARNING_AI");

    private static final List<String> LADDER = List.of("DIGITAL_REMINDER", "TELECALL", "FIELD_VISIT", "RECOVERY");

    /**
     * Assumption A1: a hold replaces strategy and queue even for a CURRENT loan, so such a loan
     * sits on the hold desk. Flip to false if the expected sample file shows CURRENT holds with
     * an empty queue.
     */
    public static final boolean HOLD_APPLIES_TO_CURRENT = true;

    private CollectionRules() {
    }

    public static RuleResult apply(Position pos, CustomerFlags flags, List<ContactRec> contactsBeforeAsOf,
                                   RulesConfig cfg) {
        Set<String> codes = new LinkedHashSet<>();
        String bucket = pos.bucket();
        int dpd = pos.dpd();

        // R3 to R8: base strategy from the bucket.
        String strategy;
        switch (bucket) {
            case "CURRENT" -> strategy = "NONE";
            case "SMA_0" -> strategy = dpd <= cfg.digitalMaxDpd() ? "DIGITAL_REMINDER" : "TELECALL";
            case "SMA_1" -> strategy = pos.brokenPtpCount() >= 1 ? "FIELD_VISIT" : "TELECALL";
            case "SMA_2" -> strategy = "FIELD_VISIT";
            default -> {
                strategy = "RECOVERY";
                codes.add("NPA_CLASSIFIED");
            }
        }

        // R9: two or more broken PTPs escalate one step up the ladder (RECOVERY stays).
        if (pos.brokenPtpCount() >= cfg.brokenPtpEscalationCount() && LADDER.contains(strategy)) {
            int i = LADDER.indexOf(strategy);
            strategy = LADDER.get(Math.min(i + 1, LADDER.size() - 1));
            codes.add("BROKEN_PTP_REPEAT");
        }

        String queue = queueFor(strategy);

        // R10: high-value SMA_2 or NPA goes to the senior collector queue; strategy unchanged.
        if (("SMA_2".equals(bucket) || "NPA".equals(bucket))
                && pos.totalDues().compareTo(cfg.highValueDues()) >= 0) {
            queue = "SENIOR_COLLECTOR";
            codes.add("HIGH_VALUE");
        }

        // R11: pre-legal notice for SMA_2 at or above the configured DPD.
        if ("SMA_2".equals(bucket) && dpd >= cfg.preLegalDpd()) {
            codes.add("PRE_LEGAL_NOTICE");
        }

        // R12: skip trace when the last 3 contacts were all wrong number or unreachable.
        if (!"CURRENT".equals(bucket) && lastThreeUnreachable(contactsBeforeAsOf)) {
            codes.add("SKIP_TRACE");
        }

        boolean escalated = codes.contains("BROKEN_PTP_REPEAT") || codes.contains("HIGH_VALUE");

        // R1 / R2: holds come last and replace strategy and queue.
        if (flags.onHold() && (HOLD_APPLIES_TO_CURRENT || !"CURRENT".equals(bucket))) {
            strategy = "HOLD";
            queue = flags.dispute() ? "DISPUTE_DESK" : "CLAIMS_DESK";
            if (flags.dispute()) {
                codes.add("HOLD_DISPUTE");
            }
            if (flags.deceased()) {
                codes.add("HOLD_DECEASED");
            }
        }

        return new RuleResult(strategy, queue, ordered(codes), escalated);
    }

    /**
     * R13 (AI track only): a CURRENT loan not on hold with p_miss_next at or above the threshold
     * gets a pre-due reminder. Returns the rules result unchanged otherwise.
     */
    public static RuleResult applyEarlyWarning(Position pos, RuleResult rules, BigDecimal pMissNext,
                                               RulesConfig cfg) {
        if (pMissNext == null || !"CURRENT".equals(pos.bucket()) || "HOLD".equals(rules.strategy())
                || pMissNext.compareTo(cfg.aiEarlyWarningThreshold()) < 0) {
            return rules;
        }
        Set<String> codes = new LinkedHashSet<>(rules.codes());
        codes.add("EARLY_WARNING_AI");
        return new RuleResult("PRE_DUE_REMINDER", "AUTO_DIGITAL", ordered(codes), rules.escalated());
    }

    public static String queueFor(String strategy) {
        return switch (strategy) {
            case "DIGITAL_REMINDER", "PRE_DUE_REMINDER" -> "AUTO_DIGITAL";
            case "TELECALL" -> "TELECALLER";
            case "FIELD_VISIT" -> "FIELD";
            case "RECOVERY" -> "RECOVERY_TEAM";
            default -> null;
        };
    }

    private static boolean lastThreeUnreachable(List<ContactRec> contacts) {
        if (contacts.size() < 3) {
            return false;
        }
        List<ContactRec> sorted = new ArrayList<>(contacts);
        sorted.sort(Comparator.comparing(ContactRec::contactTs).thenComparing(ContactRec::contactId).reversed());
        return sorted.subList(0, 3).stream().allMatch(c ->
                "WRONG_NUMBER".equalsIgnoreCase(c.outcome()) || "UNREACHABLE".equalsIgnoreCase(c.outcome()));
    }

    private static List<String> ordered(Set<String> codes) {
        return CODE_ORDER.stream().filter(codes::contains).toList();
    }
}
