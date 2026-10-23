package com.threepointers.recoverplus.data;

import com.threepointers.recoverplus.engine.EngineModels.ContactRec;
import com.threepointers.recoverplus.engine.EngineModels.Instalment;
import com.threepointers.recoverplus.engine.EngineModels.LoanTerms;
import com.threepointers.recoverplus.engine.EngineModels.PaymentRec;
import com.threepointers.recoverplus.io.Book;
import com.threepointers.recoverplus.io.BookLoader;
import com.threepointers.recoverplus.security.AuthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Moves the loan book between PostgreSQL and the in-memory Book the engine uses.
 * Every insert is ON CONFLICT DO NOTHING, so seeding or importing twice adds nothing.
 */
@Repository
public class BookRepository {

    private final JdbcTemplate jdbc;

    public BookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- read: DB -> Book ----------------

    public Book loadBook() {
        Book b = new Book();
        jdbc.query("SELECT * FROM customers", rs -> {
            b.customers.put(rs.getString("customer_id"), new Book.Customer(rs.getString("customer_id"),
                    rs.getString("full_name"), rs.getString("mobile"), rs.getString("city"),
                    rs.getString("preferred_language"), rs.getString("occupation_type"), rs.getBigDecimal("monthly_income"),
                    rs.getBoolean("dispute_flag"), rs.getBoolean("deceased_flag")));
        });
        jdbc.query("SELECT * FROM loans", rs -> {
            b.loans.put(rs.getString("loan_id"), new LoanTerms(rs.getString("loan_id"), rs.getString("customer_id"),
                    rs.getString("product"), rs.getBigDecimal("principal"), rs.getBigDecimal("emi_amount"), rs.getString("branch")));
        });
        jdbc.query("SELECT * FROM repayment_schedule ORDER BY loan_id, instalment_no", rs -> {
            b.schedules.computeIfAbsent(rs.getString("loan_id"), k -> new ArrayList<>()).add(new Instalment(
                    rs.getInt("instalment_no"), rs.getDate("due_date").toLocalDate(), rs.getBigDecimal("emi_amount"),
                    rs.getBigDecimal("closing_principal")));
        });
        jdbc.query("SELECT * FROM payments", rs -> {
            b.addPayment(rs.getString("loan_id"), new PaymentRec(rs.getString("payment_id"),
                    rs.getDate("payment_date").toLocalDate(), rs.getBigDecimal("amount")));
        });
        jdbc.query("SELECT * FROM contacts", rs -> {
            b.addContact(rs.getString("loan_id"), toContact(rs.getString("contact_id"), rs.getObject("contact_ts", OffsetDateTime.class),
                    rs.getString("channel"), rs.getString("outcome"), rs.getDate("ptp_date"), rs.getBigDecimal("ptp_amount"),
                    rs.getString("collector_id")));
        });
        jdbc.query("SELECT * FROM app_users", rs -> {
            b.users.add(new Book.User(rs.getString("user_id"), rs.getString("full_name"), rs.getString("role"),
                    rs.getString("queue"), rs.getString("city"), rs.getInt("daily_capacity")));
        });
        jdbc.query("SELECT config_key, config_value FROM rules_config", rs -> {
            b.config.put(rs.getString(1), rs.getString(2));
        });
        return b;
    }

    static ContactRec toContact(String id, OffsetDateTime ts, String channel, String outcome, Date ptpDate,
                                java.math.BigDecimal ptpAmount, String collector) {
        return new ContactRec(id, ts, ts.atZoneSameInstant(BookLoader.IST).toLocalDate(), channel, outcome,
                ptpDate == null ? null : ptpDate.toLocalDate(), ptpAmount, collector);
    }

    // ---------------- write: Book -> DB ----------------

    public int countLoans() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM loans", Integer.class);
        return n == null ? 0 : n;
    }

    /** Inserts the whole seed book. Users get the demo password. Config rows are only added if missing. */
    public void insertSeed(Book b, String demoPassword) {
        jdbc.batchUpdate("INSERT INTO customers VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                b.customers.values().stream().map(c -> new Object[]{c.customerId(), c.fullName(), c.mobile(), c.city(),
                        c.preferredLanguage(), c.occupationType(), c.monthlyIncome(), c.dispute(), c.deceased()}).toList());
        jdbc.batchUpdate("INSERT INTO loans VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                b.loans.values().stream().map(l -> new Object[]{l.loanId(), l.customerId(), l.product(), l.principal(),
                        l.emiAmount(), l.branch()}).toList());
        List<Object[]> sched = new ArrayList<>();
        b.schedules.forEach((loanId, list) -> list.forEach(i -> sched.add(new Object[]{loanId, i.instalmentNo(),
                Date.valueOf(i.dueDate()), i.emiAmount(), i.closingPrincipal()})));
        jdbc.batchUpdate("INSERT INTO repayment_schedule VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING", sched);
        insertPayments(b, "seed");
        insertContacts(b.contacts.entrySet().stream()
                .flatMap(e -> e.getValue().values().stream().map(c -> new Object[]{e.getKey(), c})).toList(), "seed");
        String hash = AuthService.sha256(demoPassword);
        jdbc.batchUpdate("INSERT INTO app_users VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                b.users.stream().map(u -> new Object[]{u.userId(), u.fullName(), u.role(), blankToNull(u.queue()),
                        u.city(), u.dailyCapacity(), hash}).toList());
        jdbc.batchUpdate("INSERT INTO rules_config (config_key, config_value, updated_by) VALUES (?,?, 'SEED') ON CONFLICT DO NOTHING",
                b.config.entrySet().stream().map(e -> new Object[]{e.getKey(), e.getValue()}).toList());
    }

    /** Returns how many payments were new (duplicates by payment_id are skipped). */
    public int insertPayments(Book b, String source) {
        List<Object[]> rows = new ArrayList<>();
        b.payments.forEach((loanId, m) -> m.values().forEach(p -> rows.add(new Object[]{p.paymentId(), loanId,
                Date.valueOf(p.paymentDate()), p.amount(), null, source})));
        return sum(jdbc.batchUpdate("INSERT INTO payments VALUES (?,?,?,?,?,?) ON CONFLICT (payment_id) DO NOTHING", rows));
    }

    /** rows: {loanId, ContactRec}. Returns how many contacts were new. */
    public int insertContacts(Collection<Object[]> rows, String source) {
        return sum(jdbc.batchUpdate(
                "INSERT INTO contacts (contact_id, loan_id, contact_ts, channel, outcome, ptp_date, ptp_amount, collector_id, source) "
                        + "VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (contact_id) DO NOTHING",
                rows.stream().map(r -> {
                    ContactRec c = (ContactRec) r[1];
                    return new Object[]{c.contactId(), r[0], Timestamp.from(c.contactTs().toInstant()), c.channel(), c.outcome(),
                            c.ptpDate() == null ? null : Date.valueOf(c.ptpDate()), c.ptpAmount(), c.collectorId(), source};
                }).toList()));
    }

    private static int sum(int[] counts) {
        int n = 0;
        for (int c : counts) {
            n += Math.max(c, 0);
        }
        return n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
