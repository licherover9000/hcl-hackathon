package com.threepointers.recoverplus.engine;

import com.threepointers.recoverplus.engine.EngineModels.ContactRec;
import com.threepointers.recoverplus.engine.EngineModels.CustomerFlags;
import com.threepointers.recoverplus.engine.EngineModels.Instalment;
import com.threepointers.recoverplus.engine.EngineModels.LoanTerms;
import com.threepointers.recoverplus.engine.EngineModels.PaymentRec;
import com.threepointers.recoverplus.engine.EngineModels.Position;
import com.threepointers.recoverplus.engine.EngineModels.RuleResult;
import com.threepointers.recoverplus.engine.EngineModels.SettlementDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worked examples 1 to 14 of case_03 section 6.4, built from the stated defaults:
 * PERSONAL_LOAN of 3,00,000 at 14% for 36 months, EMI 10,253, disbursed 2025-03-20,
 * every instalment paid in full on its due date except as stated, as of 2026-03-31.
 */
class WorkedExamplesTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 3, 31);
    static final RulesConfig CFG = RulesConfig.defaults();
    static final CustomerFlags NO_HOLD = new CustomerFlags(false, false);

    /** Reducing-balance schedule, interest rounded to paise each month (matches 2,13,556.57). */
    static List<Instalment> schedule(BigDecimal principal, int tenure, BigDecimal emi, int dueDay) {
        List<Instalment> list = new ArrayList<>();
        BigDecimal rate = new BigDecimal("14").divide(new BigDecimal("1200"), 20, RoundingMode.HALF_UP);
        BigDecimal bal = principal;
        LocalDate due = LocalDate.of(2025, 4, dueDay);
        for (int i = 1; i <= tenure; i++) {
            BigDecimal interest = bal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            bal = bal.subtract(emi.subtract(interest));
            list.add(new Instalment(i, due, emi, bal.max(BigDecimal.ZERO)));
            due = due.plusMonths(1);
        }
        return list;
    }

    static LoanTerms loan(String id, String principal) {
        return new LoanTerms(id, "C" + id, "PERSONAL_LOAN", new BigDecimal(principal), new BigDecimal("10253"), "B1");
    }

    /** Payments in full on each due date up to as-of, except the listed unpaid due dates. */
    static List<PaymentRec> paidExcept(List<Instalment> sched, Set<LocalDate> unpaid, Map<LocalDate, BigDecimal> partial) {
        List<PaymentRec> out = new ArrayList<>();
        int n = 0;
        for (Instalment i : sched) {
            if (i.dueDate().isAfter(AS_OF) || unpaid.contains(i.dueDate())) {
                continue;
            }
            BigDecimal amt = partial.getOrDefault(i.dueDate(), i.emiAmount());
            out.add(new PaymentRec("P" + (++n), i.dueDate(), amt));
        }
        return out;
    }

    static ContactRec ptp(String id, LocalDate contactDate, LocalDate ptpDate, String amount) {
        OffsetDateTime ts = OffsetDateTime.of(contactDate, LocalTime.of(11, 0), ZoneOffset.ofHoursMinutes(5, 30));
        return new ContactRec(id, ts, contactDate, "CALL", "PTP", ptpDate, new BigDecimal(amount), "U401");
    }

    static Position pos(LoanTerms l, List<Instalment> s, List<PaymentRec> p, List<ContactRec> c) {
        return PositionCalculator.calculate(l, s, p, c, AS_OF, CFG);
    }

    static final BigDecimal EMI = new BigDecimal("10253");
    static final BigDecimal P3L = new BigDecimal("300000");

    @Test
    void example1_allPaid_current() {
        var s = schedule(P3L, 36, EMI, 15);
        Position p = pos(loan("L900001", "300000"), s, paidExcept(s, Set.of(), Map.of()), List.of());
        assertEquals(0, p.dpd());
        assertEquals("CURRENT", p.bucket());
        assertEquals(new BigDecimal("0.00"), p.overdueAmount());
        assertEquals(new BigDecimal("0.00"), p.penalCharges());
        assertEquals(new BigDecimal("213556.57"), p.totalDues());
        RuleResult r = CollectionRules.apply(p, NO_HOLD, List.of(), CFG);
        assertEquals("NONE", r.strategy());
        assertNull(r.queue());
    }

    @Test
    void example2_marchUnpaid_telecall() {
        var s = schedule(P3L, 36, EMI, 15);
        Position p = pos(loan("L900002", "300000"), s, paidExcept(s, Set.of(LocalDate.of(2026, 3, 15)), Map.of()), List.of());
        assertEquals(16, p.dpd());
        assertEquals("SMA_0", p.bucket());
        assertEquals(new BigDecimal("10253.00"), p.overdueAmount());
        assertEquals(new BigDecimal("107.87"), p.penalCharges());
        assertEquals(new BigDecimal("223917.44"), p.totalDues());
        RuleResult r = CollectionRules.apply(p, NO_HOLD, List.of(), CFG);
        assertEquals("TELECALL", r.strategy());
        assertEquals("TELECALLER", r.queue());
    }

    @Test
    void example3_dueDay25_digitalReminder() {
        var s = schedule(P3L, 36, EMI, 25);
        Position p = pos(loan("L900003", "300000"), s, paidExcept(s, Set.of(LocalDate.of(2026, 3, 25)), Map.of()), List.of());
        assertEquals(6, p.dpd());
        assertEquals("SMA_0", p.bucket());
        assertEquals(new BigDecimal("40.45"), p.penalCharges());
        RuleResult r = CollectionRules.apply(p, NO_HOLD, List.of(), CFG);
        assertEquals("DIGITAL_REMINDER", r.strategy());
        assertEquals("AUTO_DIGITAL", r.queue());
    }

    @Test
    void example4_twoUnpaid_sma1() {
        var s = schedule(P3L, 36, EMI, 15);
        Position p = pos(loan("L900004", "300000"), s,
                paidExcept(s, Set.of(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 15)), Map.of()), List.of());
        assertEquals(44, p.dpd());
        assertEquals("SMA_1", p.bucket());
        assertEquals(new BigDecimal("20506.00"), p.overdueAmount());
        assertEquals(new BigDecimal("404.50"), p.penalCharges());
        assertEquals("TELECALL", CollectionRules.apply(p, NO_HOLD, List.of(), CFG).strategy());
    }

    @Test
    void example5_oneBrokenPtp_fieldVisit() {
        var s = schedule(P3L, 36, EMI, 15);
        var c = List.of(ptp("C1", LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 14), "10253"));
        Position p = pos(loan("L900005", "300000"), s,
                paidExcept(s, Set.of(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 15)), Map.of()), c);
        assertEquals(1, p.brokenPtpCount());
        RuleResult r = CollectionRules.apply(p, NO_HOLD, c, CFG);
        assertEquals("FIELD_VISIT", r.strategy());
        assertEquals("FIELD", r.queue());
    }

    @Test
    void example6_sma2_preLegal() {
        var s = schedule(P3L, 36, EMI, 10);
        Position p = pos(loan("L900006", "300000"), s, paidExcept(s,
                Set.of(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 10)), Map.of()), List.of());
        assertEquals(80, p.dpd());
        assertEquals("SMA_2", p.bucket());
        assertEquals(new BigDecimal("30759.00"), p.overdueAmount());
        assertEquals(new BigDecimal("1011.25"), p.penalCharges());
        RuleResult r = CollectionRules.apply(p, NO_HOLD, List.of(), CFG);
        assertEquals("FIELD_VISIT", r.strategy());
        assertEquals(List.of("PRE_LEGAL_NOTICE"), r.codes());
    }

    static Position example7Position(LocalDate asOf) {
        BigDecimal emi = new BigDecimal("24594");
        var s = schedule(new BigDecimal("900000"), 48, emi, 5);
        LoanTerms l = new LoanTerms("L900007", "C7", "PERSONAL_LOAN", new BigDecimal("900000"), emi, "B1");
        var unpaid = Set.of(LocalDate.of(2025, 11, 5), LocalDate.of(2025, 12, 5), LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 2, 5), LocalDate.of(2026, 3, 5));
        return PositionCalculator.calculate(l, s, paidExcept(s, unpaid, Map.of()), List.of(), asOf, CFG);
    }

    @Test
    void example7_npa_highValue() {
        Position p = example7Position(AS_OF);
        assertEquals(146, p.dpd());
        assertEquals("NPA", p.bucket());
        assertEquals(new BigDecimal("122970.00"), p.overdueAmount());
        assertEquals(new BigDecimal("6905.19"), p.penalCharges());
        assertEquals(new BigDecimal("849461.47"), p.totalDues());
        RuleResult r = CollectionRules.apply(p, NO_HOLD, List.of(), CFG);
        assertEquals("RECOVERY", r.strategy());
        assertEquals("SENIOR_COLLECTOR", r.queue());
        assertEquals("HIGH_VALUE|NPA_CLASSIFIED", r.codesJoined());
    }

    @Test
    void example8_fiftyShort_withinTolerance() {
        var s = schedule(P3L, 36, EMI, 15);
        Position p = pos(loan("L900008", "300000"), s,
                paidExcept(s, Set.of(), Map.of(LocalDate.of(2026, 3, 15), new BigDecimal("10203"))), List.of());
        assertEquals(0, p.dpd());
        assertEquals("CURRENT", p.bucket());
        assertEquals("NONE", CollectionRules.apply(p, NO_HOLD, List.of(), CFG).strategy());
    }

    @Test
    void example9_dispute_hold() {
        var s = schedule(P3L, 36, EMI, 15);
        Position p = pos(loan("L900009", "300000"), s, paidExcept(s, Set.of(LocalDate.of(2026, 3, 15)), Map.of()), List.of());
        RuleResult r = CollectionRules.apply(p, new CustomerFlags(true, false), List.of(), CFG);
        assertEquals(16, p.dpd());
        assertEquals("HOLD", r.strategy());
        assertEquals("DISPUTE_DESK", r.queue());
        assertEquals("HOLD_DISPUTE", r.codesJoined());
    }

    @Test
    void example10_twoBrokenPtps_escalate() {
        var s = schedule(P3L, 36, EMI, 15);
        var c = List.of(ptp("C1", LocalDate.of(2026, 2, 20), LocalDate.of(2026, 2, 24), "10253"),
                ptp("C2", LocalDate.of(2026, 3, 18), LocalDate.of(2026, 3, 22), "10253"));
        Position p = pos(loan("L900010", "300000"), s, paidExcept(s, Set.of(LocalDate.of(2026, 3, 15)), Map.of()), c);
        assertEquals(2, p.brokenPtpCount());
        RuleResult r = CollectionRules.apply(p, NO_HOLD, c, CFG);
        assertEquals("FIELD_VISIT", r.strategy());
        assertEquals("FIELD", r.queue());
        assertEquals("BROKEN_PTP_REPEAT", r.codesJoined());
    }

    @Test
    void examples11to13_settlements() {
        Position p7 = example7Position(AS_OF);
        SettlementDecision s1 = SettlementEvaluator.evaluate(p7, NO_HOLD, new BigDecimal("722042"), CFG);
        assertTrue(s1.eligible());
        assertEquals(new BigDecimal("15.00"), s1.discountPct());
        assertEquals("COLLECTIONS_MANAGER", s1.route());
        SettlementDecision s2 = SettlementEvaluator.evaluate(p7, NO_HOLD, new BigDecimal("594623"), CFG);
        assertEquals("HEAD_OF_COLLECTIONS", s2.route());
        SettlementDecision s3 = SettlementEvaluator.evaluate(p7, NO_HOLD, new BigDecimal("467204"), CFG);
        assertEquals("REJECT", s3.route());
        assertEquals("DISCOUNT_ABOVE_LIMIT", s3.reasonCode());

        var s = schedule(P3L, 36, EMI, 15);
        Position p4 = pos(loan("L900004", "300000"), s,
                paidExcept(s, Set.of(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 15)), Map.of()), List.of());
        SettlementDecision s4 = SettlementEvaluator.evaluate(p4, NO_HOLD, new BigDecimal("200000"), CFG);
        assertFalse(s4.eligible());
        assertEquals("NOT_ELIGIBLE_BUCKET", s4.reasonCode());

        var s10 = schedule(P3L, 36, EMI, 10);
        Position p6 = pos(loan("L900006", "300000"), s10, paidExcept(s10,
                Set.of(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 10)), Map.of()), List.of());
        BigDecimal offer10 = p6.totalDues().multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP);
        assertEquals("COLLECTIONS_MANAGER", SettlementEvaluator.evaluate(p6, NO_HOLD, offer10, CFG).route());

        Position p9 = pos(loan("L900009", "300000"), s, paidExcept(s, Set.of(LocalDate.of(2026, 3, 15)), Map.of()), List.of());
        SettlementDecision s6 = SettlementEvaluator.evaluate(p9, new CustomerFlags(true, false), new BigDecimal("200000"), CFG);
        assertEquals("ACCOUNT_ON_HOLD", s6.reasonCode());
    }

    @Test
    void example14_aiEarlyWarning() {
        var s = schedule(P3L, 36, EMI, 15);
        Position p = pos(loan("L900001", "300000"), s, paidExcept(s, Set.of(), Map.of()), List.of());
        RuleResult rules = CollectionRules.apply(p, NO_HOLD, List.of(), CFG);
        RuleResult ai = CollectionRules.applyEarlyWarning(p, rules, new BigDecimal("0.65"), CFG);
        assertEquals("PRE_DUE_REMINDER", ai.strategy());
        assertEquals("AUTO_DIGITAL", ai.queue());
        assertEquals("EARLY_WARNING_AI", ai.codesJoined());
        BigDecimal expected = BigDecimal.ONE.subtract(new BigDecimal("0.65"))
                .multiply(p.overdueAmount().add(EMI)).setScale(2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("3588.55"), expected);
    }

    @Test
    void worklistRank_escalatedFirst() {
        var items = List.of(
                new WorklistRanker.Item("L900005", "FIELD", false, 44, new BigDecimal("20506"), null),
                new WorklistRanker.Item("L900006", "FIELD", false, 80, new BigDecimal("30759"), null),
                new WorklistRanker.Item("L900010", "FIELD", true, 16, new BigDecimal("10253"), null));
        Map<String, Integer> r = WorklistRanker.rankRules(items);
        assertEquals(1, r.get("L900010"));
        assertEquals(2, r.get("L900006"));
        assertEquals(3, r.get("L900005"));
    }
}
