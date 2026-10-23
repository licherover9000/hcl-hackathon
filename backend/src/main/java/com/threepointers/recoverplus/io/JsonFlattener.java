package com.threepointers.recoverplus.io;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiny JSON reader that flattens a document into dotted keys:
 * {"buckets":{"sma_0":{"max":30}},"mcc":[1,2]} becomes
 * buckets.sma_0.max=30, mcc[0]=1, mcc[1]=2. Used to load the rules config JSON
 * into the rules_config table without any external library.
 */
public final class JsonFlattener {

    private final String s;
    private int i;
    private final Map<String, String> out = new LinkedHashMap<>();

    private JsonFlattener(String s) {
        this.s = s;
    }

    public static Map<String, String> flatten(String json) {
        JsonFlattener p = new JsonFlattener(json);
        p.ws();
        p.value("");
        return p.out;
    }

    private void value(String path) {
        ws();
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                value(path.isEmpty() ? k : path + "." + k);
                ws();
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return;
            }
        } else if (c == '[') {
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                out.put(path, "");
                return;
            }
            int idx = 0;
            StringBuilder joined = new StringBuilder();
            while (true) {
                int before = out.size();
                String itemPath = path + "[" + idx + "]";
                value(itemPath);
                if (out.size() == before + 1 && out.containsKey(itemPath)) {
                    if (joined.length() > 0) {
                        joined.append(',');
                    }
                    joined.append(out.get(itemPath));
                }
                idx++;
                ws();
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect(']');
                out.put(path, joined.toString());
                return;
            }
        } else if (c == '"') {
            out.put(path, string());
        } else {
            int start = i;
            while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            String lit = s.substring(start, i);
            out.put(path, "null".equals(lit) ? "" : lit);
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        i++;
        return sb.toString();
    }

    private void expect(char c) {
        if (s.charAt(i) != c) {
            throw new IllegalArgumentException("Invalid JSON at position " + i + ": expected '" + c + "'");
        }
        i++;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}
