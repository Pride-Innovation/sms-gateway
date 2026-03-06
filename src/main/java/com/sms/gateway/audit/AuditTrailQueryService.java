package com.sms.gateway.audit;

import com.sms.gateway.admin.dto.AuditTrailResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class AuditTrailQueryService {

    private static final String UNION_QUERY = """
            SELECT r.rev AS revision_id,
                   r.rev_tstmp AS revision_timestamp,
                   r.actor_type,
                   r.actor_id,
                   r.request_id,
                   r.ip_address,
                   r.user_agent,
                   'ADMIN_USER' AS entity_type,
                   CAST(a.id AS CHAR(64)) AS entity_id,
                   a.revtype AS revision_type
              FROM admin_users_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            UNION ALL
            SELECT r.rev AS revision_id,
                   r.rev_tstmp AS revision_timestamp,
                   r.actor_type,
                   r.actor_id,
                   r.request_id,
                   r.ip_address,
                   r.user_agent,
                   'API_CLIENT' AS entity_type,
                   CAST(a.id AS CHAR(64)) AS entity_id,
                   a.revtype AS revision_type
              FROM api_clients_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            UNION ALL
            SELECT r.rev AS revision_id,
                   r.rev_tstmp AS revision_timestamp,
                   r.actor_type,
                   r.actor_id,
                   r.request_id,
                   r.ip_address,
                   r.user_agent,
                   'CARRIER_PREFIX' AS entity_type,
                   CAST(a.id AS CHAR(64)) AS entity_id,
                   a.revtype AS revision_type
              FROM carrier_prefixes_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            UNION ALL
            SELECT r.rev AS revision_id,
                   r.rev_tstmp AS revision_timestamp,
                   r.actor_type,
                   r.actor_id,
                   r.request_id,
                   r.ip_address,
                   r.user_agent,
                   'OUTBOUND_MESSAGE' AS entity_type,
                   CAST(a.id AS CHAR(64)) AS entity_id,
                   a.revtype AS revision_type
              FROM outbound_messages_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AuditTrailQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Page<AuditTrailResponse> search(
            String entityType,
            String entityId,
            String actorType,
            String actorId,
            String operation,
            String requestId,
            Instant from,
            Instant to,
            Pageable pageable
    ) {
        Integer revisionType = parseRevisionType(operation);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("entityType", normalize(entityType))
                .addValue("entityId", blankToNull(entityId))
                .addValue("actorType", normalize(actorType))
                .addValue("actorId", blankToNull(actorId))
                .addValue("revisionType", revisionType)
                .addValue("requestId", blankToNull(requestId))
                .addValue("fromTs", from == null ? null : from.toEpochMilli())
                .addValue("toTs", to == null ? null : to.toEpochMilli())
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());

        String filteredFrom = """
                FROM (
                """ + UNION_QUERY + """
                ) t
                WHERE (:entityType IS NULL OR t.entity_type = :entityType)
                  AND (:entityId IS NULL OR t.entity_id = :entityId)
                  AND (:actorType IS NULL OR t.actor_type = :actorType)
                  AND (:actorId IS NULL OR t.actor_id = :actorId)
                  AND (:revisionType IS NULL OR t.revision_type = :revisionType)
                  AND (:requestId IS NULL OR t.request_id = :requestId)
                  AND (:fromTs IS NULL OR t.revision_timestamp >= :fromTs)
                  AND (:toTs IS NULL OR t.revision_timestamp <= :toTs)
                """;

        List<AuditTrailResponse> content = jdbcTemplate.query(
                """
                SELECT t.revision_id,
                       t.revision_timestamp,
                       t.actor_type,
                       t.actor_id,
                       t.entity_type,
                       t.entity_id,
                       t.revision_type,
                       t.request_id,
                       t.ip_address,
                       t.user_agent
                """ + filteredFrom + """
                ORDER BY t.revision_timestamp DESC, t.revision_id DESC
                LIMIT :limit OFFSET :offset
                """,
                params,
                this::mapRow
        );

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + filteredFrom,
                params,
                Long.class
        );

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    private AuditTrailResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuditTrailResponse(
                rs.getInt("revision_id"),
                Instant.ofEpochMilli(rs.getLong("revision_timestamp")),
                rs.getString("actor_type"),
                rs.getString("actor_id"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                mapOperation(rs.getInt("revision_type")),
                rs.getString("request_id"),
                rs.getString("ip_address"),
                rs.getString("user_agent")
        );
    }

    private Integer parseRevisionType(String operation) {
        if (!StringUtils.hasText(operation)) {
            return null;
        }
        String normalized = operation.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CREATE", "ADD", "INSERT" -> 0;
            case "UPDATE", "MOD", "MODIFY" -> 1;
            case "DELETE", "DEL", "REMOVE" -> 2;
            default -> throw new IllegalArgumentException("Invalid operation. Use CREATE, UPDATE or DELETE");
        };
    }

    private String mapOperation(int revisionType) {
        return switch (revisionType) {
            case 0 -> "CREATE";
            case 1 -> "UPDATE";
            case 2 -> "DELETE";
            default -> "UNKNOWN";
        };
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
