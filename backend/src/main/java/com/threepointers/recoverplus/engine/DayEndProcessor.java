package com.threepointers.recoverplus.engine;

import com.threepointers.recoverplus.engine.EngineModels.ContactRec;
import com.threepointers.recoverplus.engine.EngineModels.CustomerFlags;
import com.threepointers.recoverplus.engine.EngineModels.LoanTerms;
import com.threepointers.recoverplus.engine.EngineModels.PaymentRec;
import com.threepointers.recoverplus.engine.EngineModels.Position;
import com.threepointers.recoverplus.engine.EngineModels.RuleResult;
import com.threepointers.recoverplus.engine.EngineModels.SettlementDecision;
import com.threepointers.recoverplus.io.Book;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the whole day-end for one as-of date over an in-memory Book:
 * position, rules, worklist ranks, collector assignment, optional AI fields,
 * and settlement decisions. Pure Java: used by the offline evaluation run and by
 * the Spring DayEndService, so the CSV outputs and the screens can never disagree.
 */
public final class DayEndProcessor {

    public record LoanResult(LoanTerms loan, Position position, RuleResult rules, Integer rulesRank,
                             String assignedTo, BigDecimal pMissNext, BigDecimal expectedRecovery,
                             RuleResult aiRules, Integer aiRank, String topFactors) {
    }

    public record SettlementResult(Book.SettlementRequest request, Position positionAtRequest,
                                   SettlementDecision decision) {
        public String status() {
            return switch (decision.route()) {
                case "COLLECTIONS_MANAGER" -> "PENDING_MANAGER";
                case "HEAD_OF_COLLECTIONS" -> "PENDING_HEAD";
                default -> "AUTO_REJECTED";
            };
        }
    }

    public record AiScore(BigDecimal pMissNext, String topFactors) {
    }

    private DayEndProcessor() {
    }

    /** aiScores may be null (rules only, or AI service unavailable). */
    public static List<LoanResult> run(Book book, LocalDate asOf, RulesConfig cfg, Map<String, AiScore> aiScores) {
        List<LoanResult> partial = new ArrayList<>();
        List<WorklistRanker.Item> ruleItems = new ArrayList<>();
        List<WorklistRanker.Item> aiItems = new ArrayList<>();
        Map<String, Object[]> tmp = new LinkedHashMap<>();

        for (LoanTerms loan : book.loans.values()) {
            String id = loan.loanId();
            List<PaymentRec> payments = book.paymentsOf(id);
            List<ContactRec> contacts = book.contactsOf(id).stream()
                    .filter(c -> c.contactDate().isBefore(asOf)).toList();
            Position pos = PositionCalculator.calculate(loan, book.scheduleOf(id), payments, contacts, asOf, cfg);
            Book.Customer cust = book.customers.get(loan.customerId());
            CustomerFlags flags = cust == null ? new CustomerFlags(false, false) : cust.flags();
            RuleResult rules = CollectionRules.apply(pos, flags, contacts, cfg);

            AiScore ai = aiScores == null ? null : aiScores.get(id);
            BigDecimal p = ai == null ? null : ai.pMissNext();
            BigDecimal er = null;
            RuleResult aiRules = null;
            if (p != null) {
                BigDecimal emi = loan.emiAmount() == null ? BigDecimal.ZERO : loan.emiAmount();
                er = BigDecimal.ONE.subtract(p).multiply(pos.overdueAmount().add(emi)).setScale(2, RoundingMode.HALF_UP);
                aiRules = CollectionRules.applyEarlyWarning(pos, rules, p, cfg);
            }
            ruleItems.add(new WorklistRanker.Item(id, rules.queue(), rules.escalated(), pos.dpd(), pos.overdueAmount(), er));
            if (aiRules != null) {
                aiItems.add(new WorklistRanker.Item(id, aiRules.queue(), aiRules.escalated(), pos.dpd(), pos.overdueAmount(), er));
            }
            tmp.put(id, new Object[]{loan, pos, rules, p, er, aiRules, ai == null ? null : ai.topFactors(), cust});
        }

        Map<String, Integer> ruleRanks = WorklistRanker.rankRules(ruleItems);
        Map<String, Integer> aiRanks = aiItems.isEmpty() ? Map.of() : WorklistRanker.rankAi(aiItems);
        Map<String, String> assignment = assign(book, ruleItems, ruleRanks);

        for (Map.Entry<String, Object[]> e : tmp.entrySet()) {
            Object[] t = e.getValue();
            partial.add(new LoanResult((LoanTerms) t[0], (Position) t[1], (RuleResult) t[2],
                    ruleRanks.get(e.getKey()), assignment.get(e.getKey()), (BigDecimal) t[3], (BigDecimal) t[4],
                    (RuleResult) t[5], aiRanks.get(e.getKey()), (String) t[6]));
        }
        return partial;
    }

    /**
     * Round-robin assignment of ranked loans to the collectors of each queue, within each
     * collector's daily capacity. Collectors in the customer's city are preferred; if none has
     * capacity left, any collector of the queue is used; otherwise the loan stays unassigned.
     * AUTO_DIGITAL needs no collector.
     */
    static Map<String, String> assign(Book book, List<WorklistRanker.Item> items, Map<String, Integer> ranks) {
        Map<String, List<Book.User>> byQueue = new HashMap<>();
        for (Book.User u : book.users) {
            if (u.queue() != null && !u.queue().isBlank()) {
                byQueue.computeIfAbsent(u.queue().trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(u);
            }
        }
        Map<String, Integer> used = new HashMap<>();
        Map<String, Integer> pointer = new HashMap<>();
        Map<String, String> out = new HashMap<>();
        List<WorklistRanker.Item> ordered = items.stream()
                .filter(i -> i.queue() != null && !"AUTO_DIGITAL".equals(i.queue()))
                .sorted((a, b) -> {
                    int q = a.queue().compareTo(b.queue());
                    return q != 0 ? q : Integer.compare(ranks.get(a.loanId()), ranks.get(b.loanId()));
                }).toList();
        for (WorklistRanker.Item item : ordered) {
            List<Book.User> collectors = byQueue.getOrDefault(item.queue(), List.of());
            if (collectors.isEmpty()) {
                continue;
            }
            Book.Customer c = book.customerOf(item.loanId());
            String city = c == null ? null : c.city();
            List<Book.User> sameCity = collectors.stream().filter(u -> Objects.equals(norm(u.city()), norm(city))).toList();
            String chosen = pick(sameCity, item.queue() + "|" + norm(city), used, pointer);
            if (chosen == null) {
                chosen = pick(collectors, item.queue(), used, pointer);
            }
            if (chosen != null) {
                out.put(item.loanId(), chosen);
            }
        }
        return out;
    }

    private static String pick(List<Book.User> pool, String key, Map<String, Integer> used, Map<String, Integer> pointer) {
        int n = pool.size();
        int start = pointer.getOrDefault(key, 0);
        for (int k = 0; k < n; k++) {
            Book.User u = pool.get((start + k) % n);
            int cap = u.dailyCapacity() <= 0 ? Integer.MAX_VALUE : u.dailyCapacity();
            if (used.getOrDefault(u.userId(), 0) < cap) {
                used.merge(u.userId(), 1, Integer::sum);
                pointer.put(key, (start + k + 1) % n);
                return u.userId();
            }
        }
        return null;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /** Evaluates every settlement request on the loan's position at its request date. */
    public static List<SettlementResult> settlements(Book book, RulesConfig cfg) {
        List<SettlementResult> out = new ArrayList<>();
        for (Book.SettlementRequest r : book.settlementRequests.values()) {
            LoanTerms loan = book.loans.get(r.loanId());
            if (loan == null) {
                continue;
            }
            List<ContactRec> contacts = book.contactsOf(r.loanId()).stream()
                    .filter(c -> c.contactDate().isBefore(r.requestDate())).toList();
            Position pos = PositionCalculator.calculate(loan, book.scheduleOf(r.loanId()), book.paymentsOf(r.loanId()),
                    contacts, r.requestDate(), cfg);
            Book.Customer cust = book.customers.get(loan.customerId());
            CustomerFlags flags = cust == null ? new CustomerFlags(false, false) : cust.flags();
            out.add(new SettlementResult(r, pos, SettlementEvaluator.evaluate(pos, flags, r.offerAmount(), cfg)));
        }
        return out;
    }
}
