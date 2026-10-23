package com.threepointers.recoverplus.security;

import com.threepointers.recoverplus.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Login and stateless tokens. A token is base64url(userId:expiryEpochSeconds) + "." +
 * base64url(HMAC-SHA256 of that part with the server secret). Changing any character
 * breaks the signature, so a client cannot forge another user's token.
 * Passwords are stored as SHA-256 hashes (demo data: every user gets the demo password).
 */
@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final byte[] secret;
    private final long tokenSeconds;

    public AuthService(JdbcTemplate jdbc,
                       @Value("${app.auth.secret}") String secret,
                       @Value("${app.auth.token-hours}") long tokenHours) {
        this.jdbc = jdbc;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tokenSeconds = tokenHours * 3600;
    }

    public record LoginResult(String token, CurrentUser user) {
    }

    public LoginResult login(String userId, String password) {
        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM app_users WHERE user_id = ?", String.class, userId);
        if (hashes.isEmpty() || !MessageDigest.isEqual(hashes.get(0).getBytes(StandardCharsets.UTF_8),
                sha256(password).getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("Wrong user ID or password");
        }
        CurrentUser u = loadUser(userId);
        return new LoginResult(issue(userId), u);
    }

    public CurrentUser verify(String token) {
        if (token == null || !token.contains(".")) {
            throw ApiException.unauthorized("Missing or malformed token");
        }
        String[] parts = token.split("\\.", 2);
        if (!MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8), sign(parts[0]).getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("Invalid token");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        int sep = payload.lastIndexOf(':');
        long expiry = Long.parseLong(payload.substring(sep + 1));
        if (Instant.now().getEpochSecond() > expiry) {
            throw ApiException.unauthorized("Session expired, please log in again");
        }
        return loadUser(payload.substring(0, sep));
    }

    CurrentUser loadUser(String userId) {
        List<CurrentUser> users = jdbc.query(
                "SELECT user_id, full_name, role, queue, city FROM app_users WHERE user_id = ?",
                (rs, i) -> new CurrentUser(rs.getString("user_id"), rs.getString("full_name"),
                        CurrentUser.normaliseRole(rs.getString("role")), rs.getString("queue"), rs.getString("city")),
                userId);
        if (users.isEmpty()) {
            throw ApiException.unauthorized("Unknown user");
        }
        return users.get(0);
    }

    private String issue(String userId) {
        String payload = userId + ":" + (Instant.now().getEpochSecond() + tokenSeconds);
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + sign(p);
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Token signing failed", e);
        }
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
