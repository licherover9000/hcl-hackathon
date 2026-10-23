package com.threepointers.recoverplus.security;

import com.threepointers.recoverplus.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.Locale;

/**
 * The logged-in user, attached to each request by AuthInterceptor.
 * role is normalised to COLLECTOR, MANAGER, HEAD or AUDITOR.
 */
public record CurrentUser(String userId, String fullName, String role, String queue, String city) {

    public static final String ATTR = "currentUser";

    public static CurrentUser from(HttpServletRequest req) {
        Object u = req.getAttribute(ATTR);
        if (!(u instanceof CurrentUser cu)) {
            throw ApiException.unauthorized("Not logged in");
        }
        return cu;
    }

    public static String normaliseRole(String raw) {
        String r = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        if (r.contains("HEAD")) {
            return "HEAD";
        }
        if (r.contains("MANAGER")) {
            return "MANAGER";
        }
        if (r.contains("AUDIT")) {
            return "AUDITOR";
        }
        return "COLLECTOR";
    }

    public boolean is(String... roles) {
        return Arrays.asList(roles).contains(role);
    }

    /** Throws 403 unless the user has one of the roles. */
    public CurrentUser require(String... roles) {
        if (!is(roles)) {
            throw ApiException.forbidden("Role " + role + " cannot do this (needs " + String.join(" or ", roles) + ")");
        }
        return this;
    }
}
