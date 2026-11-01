package com.dypcet.vayora.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The record of what administrators did. Written in the same transaction as the action,
 * so there is no action without its entry. Unlike the event log, a failure here fails
 * the action: an unrecorded administrative act is worse than a refused one.
 */
@Service
public class AuditService {

    public record Entry(long id, long actorId, String actorName, String action, String targetType,
            Long targetId, String detail, Instant createdAt) {
    }

    private static final int LISTED = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(long actorId, String action, String targetType, Long targetId,
            Map<String, Object> detail) {
        String json;
        try {
            json = mapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit detail cannot be written as JSON", e);
        }
        jdbc.update("""
                INSERT INTO audit_log (actor_id, action, target_type, target_id, detail)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb))""",
                actorId, action, targetType, targetId, json);
    }

    /** The newest entries. */
    public List<Entry> latest() {
        return jdbc.query("""
                SELECT a.id, a.actor_id, u.full_name, a.action, a.target_type, a.target_id,
                       a.detail::text AS detail, a.created_at
                FROM audit_log a JOIN users u ON u.id = a.actor_id
                ORDER BY a.id DESC LIMIT ?""",
                (rs, n) -> new Entry(rs.getLong("id"), rs.getLong("actor_id"),
                        rs.getString("full_name"), rs.getString("action"),
                        rs.getString("target_type"), (Long) rs.getObject("target_id"),
                        rs.getString("detail"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                LISTED);
    }
}
