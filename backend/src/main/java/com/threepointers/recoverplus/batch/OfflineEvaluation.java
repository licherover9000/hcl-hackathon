package com.threepointers.recoverplus.batch;

import com.threepointers.recoverplus.engine.DayEndProcessor;
import com.threepointers.recoverplus.engine.DayEndProcessor.LoanResult;
import com.threepointers.recoverplus.engine.DayEndProcessor.SettlementResult;
import com.threepointers.recoverplus.engine.RulesConfig;
import com.threepointers.recoverplus.io.Book;
import com.threepointers.recoverplus.io.BookLoader;
import com.threepointers.recoverplus.io.CsvIo;
import com.threepointers.recoverplus.io.OutputWriter;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation run with no database and no Spring: reads the data pack, runs the day-end
 * and writes /output/collections_output.csv and /output/settlement_decisions.csv.
 *
 * Usage (from backend/ after compiling, see scripts/run_evaluation.sh):
 *   java -cp target/classes com.threepointers.recoverplus.batch.OfflineEvaluation \
 *        --data ../data/input --as-of 2026-03-31 --out ../output
 *   add --eval to load payments_eval, contact_history_eval and settlement_requests_eval on top
 *   add --scores ../output/ai_scores.csv to also write risk_scores.csv (AI track)
 */
public final class OfflineEvaluation {

    private OfflineEvaluation() {
    }

    public static void main(String[] args) throws Exception {
        Path data = Path.of("../data/input");
        Path out = Path.of("../output");
        LocalDate asOf = LocalDate.of(2026, 3, 31);
        boolean eval = false;
        Path scores = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> data = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--as-of" -> asOf = LocalDate.parse(args[++i]);
                case "--eval" -> eval = true;
                case "--scores" -> scores = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        Instant start = Instant.now();
        BookLoader loader = new BookLoader(data);
        Book book = loader.loadSeed();
        if (eval) {
            loader.loadEvaluation(book);
        }
        loader.log().forEach(System.out::println);

        RulesConfig cfg = RulesConfig.fromMap(book.config);
        if (!cfg.defaultsUsed().isEmpty()) {
            System.out.println("NOTE: config keys not found, case defaults used for: " + cfg.defaultsUsed());
        }

        Map<String, DayEndProcessor.AiScore> ai = scores == null ? null : readScores(scores);
        List<LoanResult> results = DayEndProcessor.run(book, asOf, cfg, ai);
        List<SettlementResult> settlements = DayEndProcessor.settlements(book, cfg);

        OutputWriter.writeCollections(out.resolve("collections_output.csv"), results);
        OutputWriter.writeSettlements(out.resolve("settlement_decisions.csv"), settlements);
        if (ai != null) {
            OutputWriter.writeRiskScores(out.resolve("risk_scores.csv"), results);
        }

        Map<String, Integer> buckets = new HashMap<>();
        results.forEach(r -> buckets.merge(r.position().bucket(), 1, Integer::sum));
        System.out.println("Day-end as of " + asOf + ": " + results.size() + " loans " + buckets
                + ", " + settlements.size() + " settlement decisions, in "
                + Duration.between(start, Instant.now()).toMillis() + " ms");
        System.out.println("Wrote " + out.resolve("collections_output.csv").toAbsolutePath() + " and settlement_decisions.csv");
    }

    /** Reads loan_id,p_miss_next,top_factors from the ML scoring output. */
    static Map<String, DayEndProcessor.AiScore> readScores(Path file) throws Exception {
        Map<String, DayEndProcessor.AiScore> m = new HashMap<>();
        if (!Files.exists(file)) {
            System.out.println("NOTE: " + file + " not found, AI fields skipped (rules order only)");
            return null;
        }
        for (CsvIo.Row r : CsvIo.read(file)) {
            m.put(r.get("loan_id"), new DayEndProcessor.AiScore(new BigDecimal(r.get("p_miss_next")), r.get("top_factors")));
        }
        return m;
    }
}
