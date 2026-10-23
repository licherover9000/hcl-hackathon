package com.threepointers.recoverplus.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threepointers.recoverplus.engine.DayEndProcessor.AiScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Calls the Python scoring service. Any failure or a response slower than the timeout
 * returns null, and the day-end keeps the rules order ("AI ranking unavailable").
 */
@Service
public class MlClient {

    private static final Logger log = LoggerFactory.getLogger(MlClient.class);

    private final String baseUrl;
    private final Duration timeout;
    private final ObjectMapper json;
    private final HttpClient http;

    public MlClient(@Value("${app.ml.url}") String baseUrl, @Value("${app.ml.timeout-ms}") long timeoutMs, ObjectMapper json) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public Map<String, AiScore> score(Collection<String> loanIds) {
        try {
            String body = json.writeValueAsString(Map.of("loan_ids", loanIds));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/score"))
                    .timeout(timeout.multipliedBy(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("ML service returned {}: AI ranking unavailable", res.statusCode());
                return null;
            }
            Map<String, AiScore> out = new HashMap<>();
            for (JsonNode s : json.readTree(res.body()).path("scores")) {
                StringBuilder factors = new StringBuilder();
                for (JsonNode f : s.path("top_factors")) {
                    factors.append(factors.length() == 0 ? "" : "|").append(f.asText());
                }
                out.put(s.path("loan_id").asText(), new AiScore(new BigDecimal(s.path("p_miss_next").asText()), factors.toString()));
            }
            return out;
        } catch (Exception e) {
            log.warn("ML service not reachable at {} ({}): AI ranking unavailable", baseUrl, e.toString());
            return null;
        }
    }
}
