package com.threepointers.recoverplus.data;

import com.threepointers.recoverplus.io.Book;
import com.threepointers.recoverplus.io.BookLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Seeding from the data-pack folder and importing payment / contact files (simulated NACH/UPI feed).
 * All imports are duplicate-safe: payment_id and contact_id are primary keys.
 */
@Service
public class ImportService {

    private final BookRepository repo;
    private final Path dataDir;
    private final String demoPassword;

    public ImportService(BookRepository repo, @Value("${app.data-dir}") String dataDir,
                         @Value("${app.auth.demo-password}") String demoPassword) {
        this.repo = repo;
        this.dataDir = Path.of(dataDir);
        this.demoPassword = demoPassword;
    }

    public Path dataDir() {
        return dataDir;
    }

    /** Loads every seed file into the database. Safe to run twice. */
    @Transactional
    public Map<String, Object> seed() throws IOException {
        BookLoader loader = new BookLoader(dataDir);
        Book b = loader.loadSeed();
        repo.insertSeed(b, demoPassword);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loansInDatabase", repo.countLoans());
        out.put("log", loader.log());
        return out;
    }

    /** Imports a payment CSV (same columns as the data pack). Returns rows read and rows added. */
    @Transactional
    public Map<String, Object> importPayments(Path file, String source) throws IOException {
        Book tmp = new Book();
        int read = new BookLoader(dataDir).loadPayments(tmp, Optional.of(file));
        int added = repo.insertPayments(tmp, source);
        return Map.of("rowsRead", read, "added", added, "duplicatesSkipped", read - added);
    }

    @Transactional
    public Map<String, Object> importContacts(Path file, String source) throws IOException {
        Book tmp = new Book();
        int read = new BookLoader(dataDir).loadContacts(tmp, Optional.of(file));
        List<Object[]> rows = tmp.contacts.entrySet().stream()
                .flatMap(e -> e.getValue().values().stream().map(c -> new Object[]{e.getKey(), c})).toList();
        int added = repo.insertContacts(rows, source);
        return Map.of("rowsRead", read, "added", added, "duplicatesSkipped", read - added);
    }

    /** Imports the Hour 5:30 evaluation payments and contacts found in the data folder. */
    public Map<String, Object> importEvaluationFiles() throws IOException {
        BookLoader loader = new BookLoader(dataDir);
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<Path> p = loader.find("payments", true);
        Optional<Path> c = loader.find("contact_history", true);
        out.put("payments", p.isPresent() ? importPayments(p.get(), "eval") : "no payments_eval file");
        out.put("contacts", c.isPresent() ? importContacts(c.get(), "eval") : "no contact_history_eval file");
        return out;
    }

    public Path saveUpload(byte[] bytes, String name) throws IOException {
        Path dir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "recoverplus-uploads"));
        Path f = dir.resolve(System.currentTimeMillis() + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
        Files.write(f, bytes);
        return f;
    }
}
