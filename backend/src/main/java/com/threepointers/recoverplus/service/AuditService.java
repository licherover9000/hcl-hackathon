package com.threepointers.recoverplus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threepointers.recoverplus.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Writes the append-only audit trail. There is no update or delete method on purpose. */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void log(CurrentUser user, String entity, String entityId, String action, Object before, Object after, String comment) {
        jdbc.update("INSERT INTO audit_log (user_id, role, entity, entity_id, action, before_json, after_json, comment) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                user == null ? "SYSTEM" : user.userId(), user == null ? "SYSTEM" : user.role(),
                entity, entityId, action, toJson(before), toJson(after), comment);
    }

    public List<Map<String, Object>> search(String entity, String entityId, String userId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, ts, user_id, role, entity, entity_id, action, before_json, after_json, comment "
                + "FROM audit_log WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (entity != null && !entity.isBlank()) {
            sql.append(" AND entity = ?");
            args.add(entity);
        }
        if (entityId != null && !entityId.isBlank()) {
            sql.append(" AND entity_id = ?");
            args.add(entityId);
        }
        if (userId != null && !userId.isBlank()) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 5000));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String s) {
            return s;
        }
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
