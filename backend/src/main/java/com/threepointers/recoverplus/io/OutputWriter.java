package com.threepointers.recoverplus.io;

import com.threepointers.recoverplus.engine.DayEndProcessor.LoanResult;
import com.threepointers.recoverplus.engine.DayEndProcessor.SettlementResult;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes the scored output files exactly as section 6.3 specifies.
 * collections_output.csv is rules-only (no R13); amounts with 2 decimals; codes joined by |;
 * queue and worklist_rank empty for CURRENT.
 */
public final class OutputWriter {

    public static final List<String> COLLECTIONS_HEADER = List.of("loan_id", "dpd", "bucket", "overdue_amount",
            "penal_charges", "total_dues", "contact_strategy", "queue", "codes", "worklist_rank");
    public static final List<String> SETTLEMENT_HEADER = List.of("request_id", "eligible", "discount_pct", "route",
            "reason_code");
    public static final List<String> RISK_HEADER = List.of("loan_id", "p_miss_next", "expected_recovery", "ai_rank",
            "top_factors");

    private OutputWriter() {
    }

    public static void writeCollections(Path file, List<LoanResult> results) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        List<LoanResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing(r -> r.loan().loanId()));
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            CsvIo.writeRow(w, COLLECTIONS_HEADER);
            for (LoanResult r : sorted) {
                CsvIo.writeRow(w, List.of(
                        r.loan().loanId(),
                        String.valueOf(r.position().dpd()),
                        r.position().bucket(),
                        money(r.position().overdueAmount()),
                        money(r.position().penalCharges()),
                        money(r.position().totalDues()),
                        r.rules().strategy(),
                        nz(r.rules().queue()),
                        r.rules().codesJoined(),
                        r.rulesRank() == null ? "" : String.valueOf(r.rulesRank())));
            }
        }
    }

    public static void writeSettlements(Path file, List<SettlementResult> results) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            CsvIo.writeRow(w, SETTLEMENT_HEADER);
            for (SettlementResult s : results) {
                CsvIo.writeRow(w, List.of(
                        s.request().requestId(),
                        s.decision().eligible() ? "Y" : "N",
                        money(s.decision().discountPct()),
                        s.decision().route(),
                        nz(s.decision().reasonCode())));
            }
        }
    }

    /** AI track: one row per loan; ai_rank within the rules queue, empty for CURRENT. */
    public static void writeRiskScores(Path file, List<LoanResult> results) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        List<LoanResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing(r -> r.loan().loanId()));
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            CsvIo.writeRow(w, RISK_HEADER);
            for (LoanResult r : sorted) {
                if (r.pMissNext() == null) {
                    continue;
                }
                CsvIo.writeRow(w, List.of(
                        r.loan().loanId(),
                        r.pMissNext().setScale(4, RoundingMode.HALF_UP).toPlainString(),
                        money(r.expectedRecovery()),
                        "CURRENT".equals(r.position().bucket()) || r.aiRank() == null ? "" : String.valueOf(r.aiRank()),
                        nz(r.topFactors())));
            }
        }
    }

    static String money(BigDecimal v) {
        return v == null ? "" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
