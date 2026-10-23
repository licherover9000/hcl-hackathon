package com.threepointers.recoverplus.io;

import com.threepointers.recoverplus.engine.EngineModels.ContactRec;
import com.threepointers.recoverplus.engine.EngineModels.CustomerFlags;
import com.threepointers.recoverplus.engine.EngineModels.Instalment;
import com.threepointers.recoverplus.engine.EngineModels.LoanTerms;
import com.threepointers.recoverplus.engine.EngineModels.PaymentRec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The whole loan book in memory: what the day-end engine needs. Filled either from the
 * data-pack CSVs (BookLoader, offline run and seeding) or from PostgreSQL (BookRepository).
 */
public final class Book {

    public record Customer(String customerId, String fullName, String mobile, String city,
                           String preferredLanguage, String occupationType, BigDecimal monthlyIncome,
                           boolean dispute, boolean deceased) {
        public CustomerFlags flags() {
            return new CustomerFlags(dispute, deceased);
        }
    }

    public record User(String userId, String fullName, String role, String queue, String city,
                       int dailyCapacity) {
    }

    public record SettlementRequest(String requestId, String loanId, LocalDate requestDate,
                                    String proposedBy, BigDecimal offerAmount) {
    }

    public final Map<String, LoanTerms> loans = new TreeMap<>();
    public final Map<String, Customer> customers = new LinkedHashMap<>();
    public final Map<String, List<Instalment>> schedules = new LinkedHashMap<>();
    /** payment_id is unique: a second payment with the same id is ignored (idempotent import). */
    public final Map<String, Map<String, PaymentRec>> payments = new LinkedHashMap<>();
    /** contact_id is unique as well. */
    public final Map<String, Map<String, ContactRec>> contacts = new LinkedHashMap<>();
    public final List<User> users = new ArrayList<>();
    public final Map<String, String> config = new LinkedHashMap<>();
    public final Map<String, SettlementRequest> settlementRequests = new LinkedHashMap<>();

    public boolean addPayment(String loanId, PaymentRec p) {
        return payments.computeIfAbsent(loanId, k -> new LinkedHashMap<>()).putIfAbsent(p.paymentId(), p) == null;
    }

    public boolean addContact(String loanId, ContactRec c) {
        return contacts.computeIfAbsent(loanId, k -> new LinkedHashMap<>()).putIfAbsent(c.contactId(), c) == null;
    }

    public List<PaymentRec> paymentsOf(String loanId) {
        Map<String, PaymentRec> m = payments.get(loanId);
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    public List<ContactRec> contactsOf(String loanId) {
        Map<String, ContactRec> m = contacts.get(loanId);
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    public List<Instalment> scheduleOf(String loanId) {
        return schedules.getOrDefault(loanId, List.of());
    }

    public Customer customerOf(String loanId) {
        LoanTerms l = loans.get(loanId);
        return l == null ? null : customers.get(l.customerId());
    }
}
