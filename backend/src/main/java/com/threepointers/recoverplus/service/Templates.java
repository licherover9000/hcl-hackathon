package com.threepointers.recoverplus.service;

import com.threepointers.recoverplus.engine.EngineModels.LoanTerms;
import com.threepointers.recoverplus.engine.EngineModels.Position;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Polite, non-threatening message and letter templates (fair-practice wording).
 * Messages are written to the outbox table; nothing is actually sent.
 */
public final class Templates {

    private Templates() {
    }

    public static String reminder(String strategy, String language, LoanTerms loan, Position pos) {
        String lang = language == null ? "EN" : language.trim().toUpperCase(Locale.ROOT);
        String amount = "₹" + (pos.overdueAmount().signum() > 0 ? pos.overdueAmount() : loan.emiAmount()).toPlainString();
        boolean preDue = "PRE_DUE_REMINDER".equals(strategy);
        if (lang.startsWith("HI")) {
            return preDue
                    ? "नमस्ते, आपके लोन " + loan.loanId() + " की अगली EMI " + amount + " जल्द देय है। कृपया समय पर भुगतान करें। - Narmada Finance Bank"
                    : "नमस्ते, आपके लोन " + loan.loanId() + " की EMI " + amount + " बकाया है। कृपया जल्द भुगतान करें। - Narmada Finance Bank";
        }
        return preDue
                ? "Dear customer, a friendly reminder that your next EMI of " + amount + " for loan " + loan.loanId()
                + " is due soon. Please keep your account funded. - Narmada Finance Bank"
                : "Dear customer, your EMI of " + amount + " for loan " + loan.loanId()
                + " is pending. Please pay at the earliest to avoid further charges. - Narmada Finance Bank";
    }

    public static String preLegal(LoanTerms loan, Position pos, LocalDate asOf) {
        return "PRE-LEGAL NOTICE (date " + asOf + ")\n\nLoan " + loan.loanId() + " is " + pos.dpd()
                + " days past due. Overdue amount ₹" + pos.overdueAmount().toPlainString() + ", penal charges ₹"
                + pos.penalCharges().toPlainString() + ", total dues ₹" + pos.totalDues().toPlainString()
                + ".\nPlease contact us or pay the overdue amount within 15 days to avoid further recovery action."
                + "\nIf you dispute this amount, please write to us and we will review it.\n- Narmada Finance Bank, Collections";
    }

    public static String settlementLetter(String requestId, String loanId, BigDecimal offer, LocalDate validUntil) {
        return "SETTLEMENT APPROVAL LETTER\n\nRequest " + requestId + " for loan " + loanId + " is approved. "
                + "On receipt of ₹" + offer.toPlainString() + " on or before " + validUntil
                + ", the loan will be treated as settled. If the amount is not received by then, this offer lapses."
                + "\n- Narmada Finance Bank, Collections";
    }
}
