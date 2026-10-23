package com.threepointers.recoverplus.batch;

import com.threepointers.recoverplus.data.ImportService;
import com.threepointers.recoverplus.service.DayEndService;
import com.threepointers.recoverplus.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Command-line jobs against the database (same services as the API), then exit:
 *   java -jar target/recoverplus-1.0.0.jar --job=seed
 *   java -jar target/recoverplus-1.0.0.jar --job=evaluate --as-of=2026-03-31
 *   java -jar target/recoverplus-1.0.0.jar --job=evaluate --as-of=2026-04-15 --eval
 * Without --job the app starts normally as the API server.
 */
@Component
public class BatchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    private final ImportService imports;
    private final DayEndService dayEnd;
    private final SettlementService settlements;
    private final ApplicationContext context;

    public BatchRunner(ImportService imports, DayEndService dayEnd, SettlementService settlements, ApplicationContext context) {
        this.imports = imports;
        this.dayEnd = dayEnd;
        this.settlements = settlements;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("job")) {
            return;
        }
        String job = args.getOptionValues("job").get(0);
        log.info("Seed: {}", imports.seed());
        if ("evaluate".equals(job)) {
            boolean eval = args.containsOption("eval");
            if (eval) {
                log.info("Evaluation files: {}", imports.importEvaluationFiles());
            }
            LocalDate asOf = LocalDate.parse(args.containsOption("as-of") ? args.getOptionValues("as-of").get(0) : "2026-03-31");
            log.info("Day-end: {}", dayEnd.run(asOf, null));
            log.info("Settlements: {}", settlements.evaluateFile(eval));
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
