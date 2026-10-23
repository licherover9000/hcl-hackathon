package com.threepointers.recoverplus.io;

import com.threepointers.recoverplus.engine.EngineModels.ContactRec;
import com.threepointers.recoverplus.engine.EngineModels.Instalment;
import com.threepointers.recoverplus.engine.EngineModels.LoanTerms;
import com.threepointers.recoverplus.engine.EngineModels.PaymentRec;
import com.threepointers.recoverplus.io.CsvIo.Row;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads the case_03 data pack from a folder into a Book. Files are found by name
 * ("loans", "customers", ...); seed files are those without "eval" in the name,
 * evaluation files (payments_eval, contact_history_eval, settlement_requests_eval)
 * are loaded on top by loadEvaluation(). Every row problem names the file and line.
 */
public final class BookLoader {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Path dir;
    private final List<String> log = new ArrayList<>();

    public BookLoader(Path dir) {
        this.dir = dir;
    }

    public List<String> log() {
        return log;
    }

    public Book loadSeed() throws IOException {
        Book b = new Book();
        loadConfig(b);
        for (Row r : rows("customers", false)) {
            b.customers.put(r.get("customer_id"), new Book.Customer(r.get("customer_id"), r.get("full_name", "name"),
                    r.get("mobile"), r.get("city"), r.get("preferred_language"), r.get("occupation_type"),
                    dec(r.get("monthly_income")), yes(r.get("dispute_flag")), yes(r.get("deceased_flag"))));
        }
        for (Row r : rows("loans", false)) {
            b.loans.put(r.get("loan_id"), new LoanTerms(r.get("loan_id"), r.get("customer_id"), r.get("product"),
                    dec(r.get("principal")), dec(r.get("emi_amount")), r.get("branch")));
        }
        for (Row r : rows("repayment_schedule", false)) {
            b.schedules.computeIfAbsent(r.get("loan_id"), k -> new ArrayList<>()).add(new Instalment(
                    Integer.parseInt(r.get("instalment_no")), date(r.get("due_date"), r),
                    dec(r.get("emi_amount")), dec(r.get("closing_principal"))));
        }
        b.schedules.values().forEach(l -> l.sort(Comparator.comparingInt(Instalment::instalmentNo)));
        int p = loadPayments(b, find("payments", false));
        int c = loadContacts(b, find("contact_history", false));
        for (Row r : rows("users", false)) {
            String cap = r.get("daily_capacity");
            b.users.add(new Book.User(r.get("user_id"), r.get("full_name", "name"), r.get("role"), r.get("queue"),
                    r.get("city"), cap == null ? 0 : new BigDecimal(cap).intValue()));
        }
        Optional<Path> req = find("settlement_requests", false);
        if (req.isPresent()) {
            loadSettlementRequests(b, req.get());
        }
        log.add("Seed loaded: " + b.loans.size() + " loans, " + b.customers.size() + " customers, "
                + b.schedules.values().stream().mapToInt(List::size).sum() + " instalments, " + p + " payments, "
                + c + " contacts, " + b.users.size() + " users, " + b.config.size() + " config keys, "
                + b.settlementRequests.size() + " sample settlement requests");
        return b;
    }

    /** Adds the evaluation payments and contacts, and replaces settlement requests with the evaluation ones. */
    public void loadEvaluation(Book b) throws IOException {
        int before = b.payments.values().stream().mapToInt(m -> m.size()).sum();
        int p = loadPayments(b, find("payments", true));
        int c = loadContacts(b, find("contact_history", true));
        Optional<Path> req = find("settlement_requests", true);
        if (req.isPresent()) {
            b.settlementRequests.clear();
            loadSettlementRequests(b, req.get());
        }
        int after = b.payments.values().stream().mapToInt(m -> m.size()).sum();
        log.add("Evaluation loaded: " + p + " payment rows (" + (after - before) + " new, "
                + (p - (after - before)) + " duplicates skipped), " + c + " contact rows, "
                + b.settlementRequests.size() + " settlement requests");
    }

    private void loadConfig(Book b) throws IOException {
        Optional<Path> f = find("rules_config", false);
        if (f.isEmpty()) {
            log.add("WARNING: no rules_config JSON found, using case defaults");
            return;
        }
        b.config.putAll(JsonFlattener.flatten(Files.readString(f.get(), StandardCharsets.UTF_8)));
    }

    public int loadPayments(Book b, Optional<Path> file) throws IOException {
        if (file.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Row r : CsvIo.read(file.get())) {
            b.addPayment(r.get("loan_id"), new PaymentRec(r.get("payment_id"), date(r.get("payment_date"), r),
                    dec(r.get("amount"))));
            n++;
        }
        return n;
    }

    public int loadContacts(Book b, Optional<Path> file) throws IOException {
        if (file.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Row r : CsvIo.read(file.get())) {
            OffsetDateTime ts = timestamp(r.get("contact_ts"), r);
            b.addContact(r.get("loan_id"), new ContactRec(r.get("contact_id"), ts,
                    ts.atZoneSameInstant(IST).toLocalDate(), upper(r.get("channel")), upper(r.get("outcome")),
                    r.get("ptp_date") == null ? null : date(r.get("ptp_date"), r),
                    r.get("ptp_amount") == null ? null : dec(r.get("ptp_amount")), r.get("collector_id")));
            n++;
        }
        return n;
    }

    private void loadSettlementRequests(Book b, Path file) throws IOException {
        for (Row r : CsvIo.read(file)) {
            b.settlementRequests.put(r.get("request_id"), new Book.SettlementRequest(r.get("request_id"),
                    r.get("loan_id"), date(r.get("request_date"), r), r.get("proposed_by"), dec(r.get("offer_amount"))));
        }
    }

    private List<Row> rows(String key, boolean eval) throws IOException {
        Optional<Path> f = find(key, eval);
        if (f.isEmpty()) {
            log.add("WARNING: no file found for '" + key + "' in " + dir);
            return List.of();
        }
        return CsvIo.read(f.get());
    }

    /** Finds e.g. case_03_payments_V1.0.csv (eval=false) or case_03_payments_eval_V1.0.csv (eval=true). */
    public Optional<Path> find(String key, boolean eval) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.contains(key) && n.contains("eval") == eval && !n.contains("expected")
                        && (n.endsWith(".csv") || n.endsWith(".json"));
            }).sorted().findFirst();
        }
    }

    // ---- value parsing ----

    static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v.replace(",", "").replace("₹", "").trim());
    }

    static boolean yes(String v) {
        return v != null && (v.equalsIgnoreCase("Y") || v.equalsIgnoreCase("YES") || v.equalsIgnoreCase("TRUE") || v.equals("1"));
    }

    static String upper(String v) {
        return v == null ? null : v.trim().toUpperCase(Locale.ROOT);
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH));

    public static LocalDate date(String v, Row r) {
        if (v == null) {
            throw new IllegalArgumentException("Missing date at line " + (r == null ? "?" : r.lineNo()));
        }
        String d = v.length() > 10 && v.charAt(4) == '-' ? v.substring(0, 10) : v;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(d, f);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        throw new IllegalArgumentException("Bad date '" + v + "' at line " + (r == null ? "?" : r.lineNo()));
    }

    public static OffsetDateTime timestamp(String v, Row r) {
        if (v == null) {
            throw new IllegalArgumentException("Missing timestamp at line " + (r == null ? "?" : r.lineNo()));
        }
        String t = v.trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(t);
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(t).atZone(IST).toOffsetDateTime();
        }
    }
}
