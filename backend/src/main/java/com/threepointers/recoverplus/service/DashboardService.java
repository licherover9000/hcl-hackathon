package com.threepointers.recoverplus.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manager / head dashboard: buckets, roll-rate matrix between two day-end dates, PTP kept rate, blocked contacts, settlements. */
@Service
public class DashboardService {

    private static final List<String> BUCKETS = List.of("CURRENT", "SMA_0", "SMA_1", "SMA_2", "NPA");

    private final JdbcTemplate jdbc;

    public DashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> dashboard(LocalDate from, LocalDate to, String branch, String product) {
        List<LocalDate> dates = jdbc.queryForList("SELECT DISTINCT as_of_date FROM loan_positions ORDER BY as_of_date", LocalDate.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("availableDates", dates.stream().map(LocalDate::toString).toList());
        if (dates.isEmpty()) {
            out.put("message", "No day-end has been run yet");
            return out;
        }
        LocalDate toDate = to != null ? to : dates.get(dates.size() - 1);
        LocalDate fromDate = from != null ? from : (dates.size() > 1 ? dates.get(dates.size() - 2) : toDate);
        String filter = " AND (CAST(? AS text) IS NULL OR l.branch = CAST(? AS text))"
                + " AND (CAST(? AS text) IS NULL OR l.product = CAST(? AS text))";
        Object[] f = {branch, branch, product, product};

        List<Object> a1 = new ArrayList<>(List.of(Date.valueOf(toDate)));
        a1.addAll(Arrays.asList(f));
        out.put("asOfDate", toDate.toString());
        out.put("buckets", jdbc.queryForList("SELECT p.bucket, count(*) AS loans, sum(p.total_dues) AS total_dues, sum(p.overdue_amount) AS overdue "
                + "FROM loan_positions p JOIN loans l ON l.loan_id = p.loan_id WHERE p.as_of_date = ?" + filter
                + " GROUP BY p.bucket", a1.toArray()));

        List<Object> a2 = new ArrayList<>(List.of(Date.valueOf(fromDate), Date.valueOf(toDate)));
        a2.addAll(Arrays.asList(f));
        List<Map<String, Object>> moves = jdbc.queryForList("SELECT a.bucket AS from_bucket, b.bucket AS to_bucket, count(*) AS loans "
                + "FROM loan_positions a JOIN loan_positions b ON b.loan_id = a.loan_id JOIN loans l ON l.loan_id = a.loan_id "
                + "WHERE a.as_of_date = ? AND b.as_of_date = ?" + filter + " GROUP BY a.bucket, b.bucket", a2.toArray());
        Map<String, Map<String, Long>> matrix = new LinkedHashMap<>();
        for (String r : BUCKETS) {
            Map<String, Long> row = new LinkedHashMap<>();
            BUCKETS.forEach(c -> row.put(c, 0L));
            matrix.put(r, row);
        }
        long worse = 0;
        long sma0 = 0;
        long sma0ToSma1 = 0;
        for (Map<String, Object> m : moves) {
            String fb = (String) m.get("from_bucket");
            String tb = (String) m.get("to_bucket");
            long n = ((Number) m.get("loans")).longValue();
            if (matrix.containsKey(fb) && matrix.get(fb).containsKey(tb)) {
                matrix.get(fb).put(tb, n);
            }
            if (BUCKETS.indexOf(tb) > BUCKETS.indexOf(fb)) {
                worse += n;
            }
            if ("SMA_0".equals(fb)) {
                sma0 += n;
                if ("SMA_1".equals(tb)) {
                    sma0ToSma1 += n;
                }
            }
        }
        Map<String, Object> roll = new LinkedHashMap<>();
        roll.put("fromDate", fromDate.toString());
        roll.put("toDate", toDate.toString());
        roll.put("matrix", matrix);
        roll.put("loansMovedToWorseBucket", worse);
        roll.put("rollRateSma0ToSma1Pct", sma0 == 0 ? null : Math.round(10000.0 * sma0ToSma1 / sma0) / 100.0);
        out.put("rollRate", roll);

        Map<String, Object> ptp = jdbc.queryForMap("SELECT count(*) FILTER (WHERE ptp_status = 'KEPT') AS kept, "
                + "count(*) FILTER (WHERE ptp_status = 'BROKEN') AS broken, count(*) FILTER (WHERE ptp_status = 'OPEN') AS open FROM contacts");
        long kept = ((Number) ptp.get("kept")).longValue();
        long broken = ((Number) ptp.get("broken")).longValue();
        ptp.put("keptRatePct", kept + broken == 0 ? null : Math.round(10000.0 * kept / (kept + broken)) / 100.0);
        out.put("ptp", ptp);
        out.put("contactsBlocked", jdbc.queryForList("SELECT split_part(comment, ':', 1) AS reason, count(*) AS attempts FROM audit_log "
                + "WHERE action = 'CONTACT_BLOCKED' GROUP BY 1 ORDER BY 2 DESC"));
        out.put("settlementsByStatus", jdbc.queryForList("SELECT status, count(*) AS requests, sum(offer_amount) AS offer_total "
                + "FROM settlements GROUP BY status ORDER BY status"));
        out.put("branches", jdbc.queryForList("SELECT DISTINCT branch FROM loans WHERE branch IS NOT NULL ORDER BY branch", String.class));
        out.put("products", jdbc.queryForList("SELECT DISTINCT product FROM loans WHERE product IS NOT NULL ORDER BY product", String.class));
        return out;
    }
}
