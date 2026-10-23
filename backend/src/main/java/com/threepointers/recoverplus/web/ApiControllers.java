package com.threepointers.recoverplus.web;

import com.threepointers.recoverplus.data.ImportService;
import com.threepointers.recoverplus.security.AuthService;
import com.threepointers.recoverplus.security.CurrentUser;
import com.threepointers.recoverplus.service.AuditService;
import com.threepointers.recoverplus.service.ConfigService;
import com.threepointers.recoverplus.service.ContactService;
import com.threepointers.recoverplus.service.DashboardService;
import com.threepointers.recoverplus.service.DayEndService;
import com.threepointers.recoverplus.service.LoanService;
import com.threepointers.recoverplus.service.SettlementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All REST endpoints. Controllers only translate HTTP to service calls; role and object checks
 * live in the services. Swagger UI: /swagger-ui.html
 */
@RestController
@RequestMapping("/api")
public class ApiControllers {

    private static final Set<String> OUTPUT_FILES = Set.of("collections_output.csv", "settlement_decisions.csv", "risk_scores.csv",
            "evaluation_report.md");

    private final AuthService auth;
    private final LoanService loans;
    private final ContactService contacts;
    private final SettlementService settlements;
    private final DayEndService dayEnd;
    private final ImportService imports;
    private final ConfigService config;
    private final DashboardService dashboard;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Path outputDir;

    public ApiControllers(AuthService auth, LoanService loans, ContactService contacts, SettlementService settlements,
                          DayEndService dayEnd, ImportService imports, ConfigService config, DashboardService dashboard,
                          AuditService audit, JdbcTemplate jdbc, @Value("${app.output-dir}") String outputDir) {
        this.auth = auth;
        this.loans = loans;
        this.contacts = contacts;
        this.settlements = settlements;
        this.dayEnd = dayEnd;
        this.imports = imports;
        this.config = config;
        this.dashboard = dashboard;
        this.audit = audit;
        this.jdbc = jdbc;
        this.outputDir = Path.of(outputDir);
    }

    // ---------- auth ----------

    public record LoginRequest(String userId, String password) {
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        AuthService.LoginResult r = auth.login(req.userId(), req.password());
        return Map.of("token", r.token(), "user", r.user());
    }

    @GetMapping("/me")
    public CurrentUser me(HttpServletRequest http) {
        return CurrentUser.from(http);
    }

    // ---------- collector work ----------

    @GetMapping("/worklist")
    public Map<String, Object> worklist(HttpServletRequest http, @RequestParam(defaultValue = "rules") String mode,
                                        @RequestParam(required = false) String queue) {
        return loans.worklist(CurrentUser.from(http), mode, queue);
    }

    @GetMapping("/queues")
    public List<Map<String, Object>> queues(HttpServletRequest http) {
        CurrentUser.from(http).require("MANAGER", "HEAD", "AUDITOR");
        return loans.queueSummary();
    }

    @GetMapping("/loans/{loanId}")
    public Map<String, Object> account(HttpServletRequest http, @PathVariable String loanId) {
        return loans.account(CurrentUser.from(http), loanId);
    }

    @PostMapping("/loans/{loanId}/contacts")
    public Map<String, Object> logContact(HttpServletRequest http, @PathVariable String loanId,
                                          @RequestBody ContactService.ContactRequest req) {
        return contacts.log(CurrentUser.from(http), loanId, req);
    }

    public record ReassignRequest(String toUserId, String reason) {
    }

    @PostMapping("/loans/{loanId}/reassign")
    public Map<String, Object> reassign(HttpServletRequest http, @PathVariable String loanId, @RequestBody ReassignRequest req) {
        CurrentUser u = CurrentUser.from(http).require("MANAGER", "HEAD");
        LocalDate asOf = dayEnd.latestAsOf();
        List<String> prev = jdbc.queryForList("SELECT assigned_to FROM loan_positions WHERE as_of_date = ? AND loan_id = ?",
                String.class, Date.valueOf(asOf), loanId);
        if (prev.isEmpty()) {
            throw ApiException.notFound("Loan " + loanId + " not in the latest day-end");
        }
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM app_users WHERE user_id = ?", Integer.class, req.toUserId());
        if (exists == null || exists == 0) {
            throw ApiException.notFound("User " + req.toUserId() + " not found");
        }
        jdbc.update("UPDATE loan_positions SET assigned_to = ? WHERE as_of_date = ? AND loan_id = ?", req.toUserId(), Date.valueOf(asOf), loanId);
        audit.log(u, "LOAN", loanId, "REASSIGNED", Map.of("assignedTo", String.valueOf(prev.get(0))),
                Map.of("assignedTo", req.toUserId()), req.reason());
        return Map.of("loanId", loanId, "assignedTo", req.toUserId());
    }

    // ---------- settlements ----------

    @GetMapping("/settlements")
    public List<Map<String, Object>> settlements(HttpServletRequest http, @RequestParam(required = false) String status) {
        return settlements.list(CurrentUser.from(http), status);
    }

    @PostMapping("/settlements")
    public Map<String, Object> propose(HttpServletRequest http, @RequestBody SettlementService.ProposeRequest req) {
        return settlements.propose(CurrentUser.from(http), req);
    }

    @PostMapping("/settlements/{requestId}/decision")
    public Map<String, Object> decide(HttpServletRequest http, @PathVariable String requestId,
                                      @RequestBody SettlementService.DecisionRequest req) {
        return settlements.decide(CurrentUser.from(http), requestId, req);
    }

    // ---------- operations: seed, imports, day-end, outputs ----------

    @PostMapping("/admin/seed")
    public Map<String, Object> seed(HttpServletRequest http) throws Exception {
        CurrentUser u = CurrentUser.from(http).require("MANAGER", "HEAD");
        Map<String, Object> r = imports.seed();
        audit.log(u, "DATA", "SEED", "SEED_LOADED", null, r, null);
        return r;
    }

    @PostMapping(value = "/admin/import/payments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importPayments(HttpServletRequest http, @RequestPart("file") MultipartFile file) throws Exception {
        CurrentUser u = CurrentUser.from(http).require("MANAGER", "HEAD");
        Path saved = imports.saveUpload(file.getBytes(), file.getOriginalFilename() == null ? "payments.csv" : file.getOriginalFilename());
        Map<String, Object> r = imports.importPayments(saved, "upload:" + file.getOriginalFilename());
        audit.log(u, "DATA", file.getOriginalFilename(), "PAYMENTS_IMPORTED", null, r, null);
        return r;
    }

    @PostMapping(value = "/admin/import/contacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importContacts(HttpServletRequest http, @RequestPart("file") MultipartFile file) throws Exception {
        CurrentUser u = CurrentUser.from(http).require("MANAGER", "HEAD");
        Path saved = imports.saveUpload(file.getBytes(), file.getOriginalFilename() == null ? "contacts.csv" : file.getOriginalFilename());
        Map<String, Object> r = imports.importContacts(saved, "upload:" + file.getOriginalFilename());
        audit.log(u, "DATA", file.getOriginalFilename(), "CONTACTS_IMPORTED", null, r, null);
        return r;
    }

    @PostMapping("/admin/import/evaluation")
    public Map<String, Object> importEvaluation(HttpServletRequest http) throws Exception {
        CurrentUser u = CurrentUser.from(http).require("MANAGER", "HEAD");
        Map<String, Object> r = imports.importEvaluationFiles();
        audit.log(u, "DATA", "EVAL", "EVALUATION_FILES_IMPORTED", null, r, null);
        return r;
    }

    public record DayEndRequest(String asOfDate) {
    }

    @PostMapping("/admin/day-end")
    public Map<String, Object> runDayEnd(HttpServletRequest http, @RequestBody DayEndRequest req) throws Exception {
        CurrentUser u = CurrentUser.from(http).require("MANAGER", "HEAD");
        return dayEnd.run(LocalDate.parse(req.asOfDate()), u);
    }

    @PostMapping("/admin/settlement-file")
    public Map<String, Object> settlementFile(HttpServletRequest http, @RequestParam(defaultValue = "false") boolean eval) throws Exception {
        CurrentUser.from(http).require("MANAGER", "HEAD");
        return settlements.evaluateFile(eval);
    }

    @GetMapping("/admin/runs")
    public List<Map<String, Object>> runs(HttpServletRequest http) {
        CurrentUser.from(http).require("MANAGER", "HEAD", "AUDITOR");
        return jdbc.queryForList("SELECT * FROM day_end_runs ORDER BY run_id DESC LIMIT 50");
    }

    @GetMapping("/admin/outbox")
    public Map<String, Object> outbox(HttpServletRequest http, @RequestParam(required = false) String asOf) {
        CurrentUser.from(http).require("MANAGER", "HEAD", "AUDITOR");
        LocalDate d = asOf == null ? dayEnd.latestAsOf() : LocalDate.parse(asOf);
        Map<String, Object> out = new LinkedHashMap<>();
        if (d == null) {
            out.put("messages", List.of());
            out.put("letters", List.of());
            return out;
        }
        out.put("asOfDate", d.toString());
        out.put("messages", jdbc.queryForList("SELECT loan_id, channel, language, message FROM outbox WHERE as_of_date = ? "
                + "ORDER BY loan_id, channel LIMIT 300", Date.valueOf(d)));
        out.put("letters", jdbc.queryForList("SELECT loan_id, letter_type, body FROM letters WHERE as_of_date = ? ORDER BY loan_id LIMIT 100",
                Date.valueOf(d)));
        out.put("settlementLetters", jdbc.queryForList("SELECT request_id, loan_id, letter_text FROM settlements WHERE letter_text IS NOT NULL "
                + "ORDER BY decided_at DESC LIMIT 50"));
        return out;
    }

    @GetMapping("/admin/outputs/{name}")
    public ResponseEntity<FileSystemResource> output(HttpServletRequest http, @PathVariable String name) {
        CurrentUser.from(http).require("MANAGER", "HEAD", "AUDITOR");
        if (!OUTPUT_FILES.contains(name)) {
            throw ApiException.notFound("Unknown output file " + name);
        }
        Path f = outputDir.resolve(name);
        if (!Files.exists(f)) {
            throw ApiException.notFound(name + " has not been generated yet");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(new FileSystemResource(f));
    }

    // ---------- config, dashboard, audit ----------

    @GetMapping("/config")
    public Map<String, Object> config(HttpServletRequest http) {
        CurrentUser.from(http);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", config.list());
        out.put("effective", config.effective());
        return out;
    }

    public record ConfigUpdate(String value, String reason) {
    }

    @PutMapping("/config/{key}")
    public Map<String, Object> updateConfig(HttpServletRequest http, @PathVariable String key, @RequestBody ConfigUpdate req) {
        return config.update(CurrentUser.from(http), key, req.value(), req.reason());
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(HttpServletRequest http, @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
                                         @RequestParam(required = false) String product) {
        CurrentUser.from(http).require("MANAGER", "HEAD", "AUDITOR");
        return dashboard.dashboard(from == null || from.isBlank() ? null : LocalDate.parse(from),
                to == null || to.isBlank() ? null : LocalDate.parse(to), blank(branch), blank(product));
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(HttpServletRequest http, @RequestParam(required = false) String entity,
                                           @RequestParam(required = false) String entityId, @RequestParam(required = false) String userId,
                                           @RequestParam(defaultValue = "200") int limit) {
        CurrentUser.from(http).require("MANAGER", "HEAD", "AUDITOR");
        return audit.search(entity, entityId, userId, limit);
    }

    @GetMapping(value = "/audit/export", produces = "text/csv")
    public ResponseEntity<byte[]> auditExport(HttpServletRequest http, @RequestParam(required = false) String entity,
                                              @RequestParam(required = false) String entityId, @RequestParam(required = false) String userId)
            throws Exception {
        CurrentUser.from(http).require("MANAGER", "HEAD", "AUDITOR");
        StringWriter w = new StringWriter();
        List<String> cols = List.of("id", "ts", "user_id", "role", "entity", "entity_id", "action", "before_json", "after_json", "comment");
        com.threepointers.recoverplus.io.CsvIo.writeRow(w, cols);
        for (Map<String, Object> r : audit.search(entity, entityId, userId, 5000)) {
            com.threepointers.recoverplus.io.CsvIo.writeRow(w, cols.stream().map(c -> r.get(c) == null ? "" : String.valueOf(r.get(c))).toList());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit_export.csv\"")
                .body(w.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
