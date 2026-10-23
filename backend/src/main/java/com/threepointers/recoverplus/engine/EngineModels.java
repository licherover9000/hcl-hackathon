package com.threepointers.recoverplus.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Plain data records used by the day-end engine. No Spring, no database:
 * the engine takes these in and returns results, so every rule is unit-testable.
 */
public final class EngineModels {

    private EngineModels() {
    }

    public record LoanTerms(String loanId, String customerId, String product,
                            BigDecimal principal, BigDecimal emiAmount, String branch) {
    }

    public record Instalment(int instalmentNo, LocalDate dueDate, BigDecimal emiAmount,
                             BigDecimal closingPrincipal) {
    }

    public record PaymentRec(String paymentId, LocalDate paymentDate, BigDecimal amount) {
    }

    /** contactDate is the IST calendar date of contactTs. */
    public record ContactRec(String contactId, OffsetDateTime contactTs, LocalDate contactDate,
                             String channel, String outcome, LocalDate ptpDate,
                             BigDecimal ptpAmount, String collectorId) {
    }

    public record CustomerFlags(boolean dispute, boolean deceased) {
        public boolean onHold() {
            return dispute || deceased;
        }
    }

    /** One instalment after FIFO allocation, as seen on the as-of date. */
    public record InstalmentState(Instalment instalment, BigDecimal paid, BigDecimal unpaid,
                                  boolean due, boolean overdue, long daysOverdue,
                                  BigDecimal penalUnrounded) {
    }

    public record PtpResult(String contactId, LocalDate contactDate, LocalDate ptpDate,
                            BigDecimal ptpAmount, BigDecimal paidInWindow, String status) {
    }

    /** The loan's financial position on the as-of date (section 6.1 definitions). */
    public record Position(String loanId, LocalDate asOfDate, int dpd, String bucket,
                           BigDecimal overdueAmount, BigDecimal penalCharges, BigDecimal totalDues,
                           List<InstalmentState> instalments, int brokenPtpCount,
                           List<PtpResult> ptps) {
    }

    /** Output of the collection rules (rules-only result; R13 is applied separately). */
    public record RuleResult(String strategy, String queue, List<String> codes, boolean escalated) {
        public String codesJoined() {
            return String.join("|", codes);
        }
    }

    public record SettlementDecision(boolean eligible, BigDecimal discountPct, String route,
                                     String reasonCode) {
    }
}
