package com.threepointers.recoverplus.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One error format for every endpoint: {"status", "code", "message", "timestamp"}.
 * Internal errors return a generic message (details only in the server log).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handle(MethodArgumentNotValidException e) {
        StringBuilder sb = new StringBuilder();
        e.getBindingResult().getFieldErrors().forEach(f ->
                sb.append(sb.length() == 0 ? "" : "; ").append(f.getField()).append(": ").append(f.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", sb.toString());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadInput(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid request: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong; see the server log.");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.value());
        m.put("code", code);
        m.put("message", message);
        m.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(m);
    }
}
