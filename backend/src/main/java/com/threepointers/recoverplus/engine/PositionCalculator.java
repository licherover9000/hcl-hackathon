package com.threepointers.recoverplus.engine;

import com.threepointers.recoverplus.engine.EngineModels.ContactRec;
import com.threepointers.recoverplus.engine.EngineModels.Instalment;
import com.threepointers.recoverplus.engine.EngineModels.InstalmentState;
import com.threepointers.recoverplus.engine.EngineModels.LoanTerms;
import com.threepointers.recoverplus.engine.EngineModels.PaymentRec;
import com.threepointers.recoverplus.engine.EngineModels.Position;
import com.threepointers.recoverplus.engine.EngineModels.PtpResult;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Computes a loan's position on an as-of date, exactly as section 6.1 defines it:
 * FIFO allocation, overdue instalments (unpaid above the tolerance), DPD, bucket,
 * overdue amount, penal charges (simple, rounded once), total dues and PTP status.
 */
public final class PositionCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);

    private PositionCalculator() {
    }

    public static Position calculate(LoanTerms loan, List<Instalment> schedule, List<PaymentRec> payments,
                                     List<ContactRec> contacts, LocalDate asOf, RulesConfig cfg) {
        List<Instalment> sorted = new ArrayList<>(schedule);
        sorted.sort(Comparator.comparingInt(Instalment::instalmentNo));

        // 1. FIFO allocation: payments dated on or before as-of, in payment_date then payment_id order,
        //    go to the earliest instalment with an unpaid amount (not-yet-due ones too). Excess is ignored.
        BigDecimal[] unpaid = new BigDecimal[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            unpaid[i] = sorted.get(i).emiAmount();
        }
        List<PaymentRec> usable = payments.stream()
                .filter(p -> !p.paymentDate().isAfter(asOf))
                .sorted(Comparator.comparing(PaymentRec::paymentDate).thenComparing(PaymentRec::paymentId))
                .toList();
        for (PaymentRec p : usable) {
            BigDecimal remaining = p.amount();
            for (int i = 0; i < unpaid.length && remaining.signum() > 0; i++) {
                if (unpaid[i].signum() > 0) {
                    BigDecimal take = remaining.min(unpaid[i]);
                    unpaid[i] = unpaid[i].subtract(take);
                    remaining = remaining.subtract(take);
                }
            }
        }

        // 2. Overdue instalments, DPD, overdue amount, penal charges.
        List<InstalmentState> states = new ArrayList<>();
        BigDecimal overdue = BigDecimal.ZERO;
        BigDecimal penal = BigDecimal.ZERO;
        LocalDate oldestOverdueDue = null;
        BigDecimal closingPrincipalLastDue = null;
        BigDecimal rate = cfg.penalRatePct().divide(HUNDRED, MC);
        for (int i = 0; i < sorted.size(); i++) {
            Instalment inst = sorted.get(i);
            boolean due = inst.dueDate().isBefore(asOf);
            boolean isOverdue = due && unpaid[i].compareTo(cfg.toleranceAmount()) > 0;
            long days = isOverdue ? ChronoUnit.DAYS.between(inst.dueDate(), asOf) : 0;
            BigDecimal instPenal = BigDecimal.ZERO;
            if (isOverdue) {
                overdue = overdue.add(unpaid[i]);
                instPenal = unpaid[i].multiply(rate, MC)
                        .multiply(BigDecimal.valueOf(days), MC)
                        .divide(DAYS_IN_YEAR, MC);
                penal = penal.add(instPenal, MC);
                if (oldestOverdueDue == null || inst.dueDate().isBefore(oldestOverdueDue)) {
                    oldestOverdueDue = inst.dueDate();
                }
            }
            if (due) {
                closingPrincipalLastDue = inst.closingPrincipal();
            }
            states.add(new InstalmentState(inst, inst.emiAmount().subtract(unpaid[i]), unpaid[i],
                    due, isOverdue, days, instPenal));
        }
        int dpd = oldestOverdueDue == null ? 0 : (int) ChronoUnit.DAYS.between(oldestOverdueDue, asOf);
        BigDecimal penalRounded = penal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal principalNotYetDue = closingPrincipalLastDue != null ? closingPrincipalLastDue : loan.principal();
        BigDecimal totalDues = overdue.add(penalRounded).add(principalNotYetDue).setScale(2, RoundingMode.HALF_UP);

        // 3. Promises to pay.
        List<PtpResult> ptps = evaluatePtps(contacts, payments, asOf, cfg);
        int broken = (int) ptps.stream()
                .filter(r -> "BROKEN".equals(r.status()))
                .filter(r -> !r.ptpDate().isBefore(asOf.minusDays(cfg.brokenPtpWindowDays()))
                        && r.ptpDate().isBefore(asOf))
                .count();

        return new Position(loan.loanId(), asOf, dpd, bucketFor(dpd, cfg),
                overdue.setScale(2, RoundingMode.HALF_UP), penalRounded, totalDues, states, broken, ptps);
    }

    public static String bucketFor(int dpd, RulesConfig cfg) {
        if (dpd <= 0) {
            return "CURRENT";
        }
        if (dpd <= cfg.sma0MaxDpd()) {
            return "SMA_0";
        }
        if (dpd <= cfg.sma1MaxDpd()) {
            return "SMA_1";
        }
        if (dpd <= cfg.sma2MaxDpd()) {
            return "SMA_2";
        }
        return "NPA";
    }

    /**
     * A PTP is BROKEN when ptp_date + grace is before as-of and payments dated from the contact date
     * to ptp_date + grace total less than the promised amount; KEPT when they reach it; otherwise OPEN.
     * Only contacts dated before as-of are considered.
     */
    public static List<PtpResult> evaluatePtps(List<ContactRec> contacts, List<PaymentRec> payments,
                                               LocalDate asOf, RulesConfig cfg) {
        List<PtpResult> out = new ArrayList<>();
        for (ContactRec c : contacts) {
            if (c.ptpDate() == null || c.ptpAmount() == null || !c.contactDate().isBefore(asOf)) {
                continue;
            }
            if (c.outcome() != null && !"PTP".equalsIgnoreCase(c.outcome())) {
                continue;
            }
            LocalDate windowEnd = c.ptpDate().plusDays(cfg.ptpGraceDays());
            LocalDate paidUpTo = windowEnd.isBefore(asOf) ? windowEnd : asOf;
            BigDecimal paid = payments.stream()
                    .filter(p -> !p.paymentDate().isBefore(c.contactDate()) && !p.paymentDate().isAfter(paidUpTo))
                    .map(PaymentRec::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String status;
            if (paid.compareTo(c.ptpAmount()) >= 0) {
                status = "KEPT";
            } else if (windowEnd.isBefore(asOf)) {
                status = "BROKEN";
            } else {
                status = "OPEN";
            }
            out.add(new PtpResult(c.contactId(), c.contactDate(), c.ptpDate(), c.ptpAmount(), paid, status));
        }
        return out;
    }
}
