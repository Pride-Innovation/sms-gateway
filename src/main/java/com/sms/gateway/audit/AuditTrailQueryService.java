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

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DatabaseDialectResolver dialect;

    public AuditTrailQueryService(
            NamedParameterJdbcTemplate jdbcTemplate,
            DatabaseDialectResolver dialect
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
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

        boolean mssql = dialect.isSqlServer();

        String unionQuery = mssql
                ? AuditQuery.UNION_QUERY_MSSQL
                : AuditQuery.UNION_QUERY_MYSQL;

        String paging = mssql
                ? AuditQuery.PAGE_MSSQL
                : AuditQuery.PAGE_MYSQL;

        String filteredFrom =
                AuditQuery.FILTER_TEMPLATE.formatted(unionQuery);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("entityType", normalize(entityType))
                .addValue("entityId", blankToNull(entityId))
                .addValue("actorType", normalize(actorType))
                .addValue("actorId", blankToNull(actorId))
                .addValue("revisionType", parseRevisionType(operation))
                .addValue("requestId", blankToNull(requestId))
                .addValue("fromTs", from == null ? null : from.toEpochMilli())
                .addValue("toTs", to == null ? null : to.toEpochMilli())
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());

        List<AuditTrailResponse> content = jdbcTemplate.query(
                AuditQuery.SELECT_COLUMNS +
                        filteredFrom +
                        " ORDER BY t.revision_timestamp DESC, t.revision_id DESC " +
                        paging,
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

    /* ===== Helpers (unchanged logic) ===== */

    private Integer parseRevisionType(String operation) {
        if (!StringUtils.hasText(operation)) return null;
        return switch (operation.trim().toUpperCase(Locale.ROOT)) {
            case "CREATE", "ADD", "INSERT" -> 0;
            case "UPDATE", "MOD", "MODIFY" -> 1;
            case "DELETE", "DEL", "REMOVE" -> 2;
            default -> throw new IllegalArgumentException("Invalid operation");
        };
    }

    private String mapOperation(int rt) {
        return switch (rt) {
            case 0 -> "CREATE";
            case 1 -> "UPDATE";
            case 2 -> "DELETE";
            default -> "UNKNOWN";
        };
    }

    private String normalize(String v) {
        return StringUtils.hasText(v) ? v.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String blankToNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }
}
