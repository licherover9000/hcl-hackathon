package com.threepointers.recoverplus.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal RFC 4180 CSV reader and writer (quoted fields, embedded commas, quotes and
 * newlines). Pure JDK so the offline evaluation run needs no downloaded libraries.
 */
public final class CsvIo {

    private CsvIo() {
    }

    /** One parsed row; get() looks columns up by header name, ignoring case and spaces. */
    public record Row(Map<String, Integer> index, List<String> values, int lineNo) {
        public String get(String... names) {
            for (String n : names) {
                Integer i = index.get(key(n));
                if (i != null && i < values.size()) {
                    String v = values.get(i).trim();
                    return v.isEmpty() ? null : v;
                }
            }
            return null;
        }

        public boolean has(String name) {
            return index.containsKey(key(name));
        }
    }

    static String key(String header) {
        return header.replace("﻿", "").trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    public static List<Row> read(Path file) throws IOException {
        List<List<String>> records = parse(Files.readString(file, StandardCharsets.UTF_8));
        List<Row> rows = new ArrayList<>();
        if (records.isEmpty()) {
            return rows;
        }
        Map<String, Integer> index = new HashMap<>();
        List<String> header = records.get(0);
        for (int i = 0; i < header.size(); i++) {
            index.put(key(header.get(i)), i);
        }
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            if (rec.size() == 1 && rec.get(0).isBlank()) {
                continue;
            }
            rows.add(new Row(index, rec, r + 1));
        }
        return rows;
    }

    static List<List<String>> parse(String text) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                out.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
            i++;
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            out.add(row);
        }
        return out;
    }

    public static void writeRow(Writer w, List<String> values) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = values.get(i) == null ? "" : values.get(i);
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        sb.append('\n');
        w.write(sb.toString());
    }
}
