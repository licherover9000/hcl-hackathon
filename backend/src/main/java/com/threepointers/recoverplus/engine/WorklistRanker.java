package com.threepointers.recoverplus.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Worklist ordering within each queue (rules order): escalated accounts first
 * (BROKEN_PTP_REPEAT or HIGH_VALUE), then DPD descending, overdue descending,
 * loan_id ascending. Ranks start at 1 in every queue, hold desks included.
 * Loans with no queue (CURRENT) get no rank.
 *
 * AI order: expected_recovery descending, ties broken by the rules order.
 */
public final class WorklistRanker {

    public record Item(String loanId, String queue, boolean escalated, int dpd, BigDecimal overdue,
                       BigDecimal expectedRecovery) {
    }

    private WorklistRanker() {
    }

    public static final Comparator<Item> RULES_ORDER = Comparator
            .comparing((Item i) -> !i.escalated())
            .thenComparing(Item::dpd, Comparator.reverseOrder())
            .thenComparing(Item::overdue, Comparator.reverseOrder())
            .thenComparing(Item::loanId);

    public static Map<String, Integer> rankRules(List<Item> items) {
        return rank(items, RULES_ORDER);
    }

    public static Map<String, Integer> rankAi(List<Item> items) {
        Comparator<Item> ai = Comparator
                .comparing((Item i) -> i.expectedRecovery() == null ? BigDecimal.valueOf(-1) : i.expectedRecovery(),
                        Comparator.reverseOrder())
                .thenComparing(RULES_ORDER);
        return rank(items, ai);
    }

    private static Map<String, Integer> rank(List<Item> items, Comparator<Item> order) {
        Map<String, List<Item>> byQueue = new LinkedHashMap<>();
        for (Item i : items) {
            if (i.queue() != null) {
                byQueue.computeIfAbsent(i.queue(), q -> new ArrayList<>()).add(i);
            }
        }
        Map<String, Integer> ranks = new HashMap<>();
        for (List<Item> list : byQueue.values()) {
            list.sort(order);
            for (int r = 0; r < list.size(); r++) {
                ranks.put(list.get(r).loanId(), r + 1);
            }
        }
        return ranks;
    }
}
