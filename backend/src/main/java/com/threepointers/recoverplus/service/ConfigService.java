package com.threepointers.recoverplus.service;

import com.threepointers.recoverplus.engine.RulesConfig;
import com.threepointers.recoverplus.security.CurrentUser;
import com.threepointers.recoverplus.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rules configuration: read by every day-end and settlement check; only the Head of Collections edits it. */
@Service
public class ConfigService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ConfigService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Map<String, String> raw() {
        Map<String, String> m = new LinkedHashMap<>();
        jdbc.query("SELECT config_key, config_value FROM rules_config ORDER BY config_key", rs -> {
            m.put(rs.getString(1), rs.getString(2));
        });
        return m;
    }

    public RulesConfig current() {
        return RulesConfig.fromMap(raw());
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT config_key, config_value, updated_by, updated_at FROM rules_config ORDER BY config_key");
    }

    /** Effective values the engine is using, including which ones fell back to case defaults. */
    public RulesConfig effective() {
        return current();
    }

    @Transactional
    public Map<String, Object> update(CurrentUser user, String key, String value, String reason) {
        user.require("HEAD");
        if (reason == null || reason.trim().length() < 10) {
            throw ApiException.unprocessable("REASON_TOO_SHORT", "Give a reason of at least 10 characters for a rule change");
        }
        List<String> old = jdbc.queryForList("SELECT config_value FROM rules_config WHERE config_key = ?", String.class, key);
        if (old.isEmpty()) {
            throw ApiException.notFound("No rules setting named " + key);
        }
        Map<String, String> trial = raw();
        trial.put(key, value);
        try {
            RulesConfig.fromMap(trial);
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("INVALID_VALUE", "Value '" + value + "' is not valid for " + key);
        }
        jdbc.update("INSERT INTO rules_config (config_key, config_value, updated_by, updated_at) VALUES (?,?,?, now()) "
                + "ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value, "
                + "updated_by = EXCLUDED.updated_by, updated_at = now()", key, value, user.userId());
        String before = old.isEmpty() ? null : old.get(0);
        audit.log(user, "CONFIG", key, "CONFIG_CHANGED", Map.of("value", before == null ? "" : before),
                Map.of("value", value), reason);
        return Map.of("key", key, "oldValue", before == null ? "" : before, "newValue", value);
    }
}
