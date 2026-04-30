
package com.sms.gateway.audit;

import org.springframework.stereotype.Component;

@Component
public class AuditQuery {

    /* =========================
       UNION QUERIES
       ========================= */

    public static final String UNION_QUERY_MYSQL = """
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
            SELECT r.rev, r.rev_tstmp, r.actor_type, r.actor_id, r.request_id,
                   r.ip_address, r.user_agent,
                   'API_CLIENT', CAST(a.id AS CHAR(64)), a.revtype
              FROM api_clients_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            UNION ALL
            SELECT r.rev, r.rev_tstmp, r.actor_type, r.actor_id, r.request_id,
                   r.ip_address, r.user_agent,
                   'CARRIER_PREFIX', CAST(a.id AS CHAR(64)), a.revtype
              FROM carrier_prefixes_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            UNION ALL
            SELECT r.rev, r.rev_tstmp, r.actor_type, r.actor_id, r.request_id,
                   r.ip_address, r.user_agent,
                   'OUTBOUND_MESSAGE', CAST(a.id AS CHAR(64)), a.revtype
              FROM outbound_messages_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            """;

    public static final String UNION_QUERY_MSSQL = """
            SELECT r.rev AS revision_id,
                   r.rev_tstmp AS revision_timestamp,
                   r.actor_type,
                   r.actor_id,
                   r.request_id,
                   r.ip_address,
                   r.user_agent,
                   'ADMIN_USER' AS entity_type,
                   CAST(a.id AS VARCHAR(64)) AS entity_id,
                   a.revtype AS revision_type
              FROM admin_users_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            UNION ALL
            SELECT r.rev, r.rev_tstmp, r.actor_type, r.actor_id, r.request_id,
                   r.ip_address, r.user_agent,
                   'API_CLIENT', CAST(a.id AS VARCHAR(64)), a.revtype
              FROM api_clients_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            UNION ALL
            SELECT r.rev, r.rev_tstmp, r.actor_type, r.actor_id, r.request_id,
                   r.ip_address, r.user_agent,
                   'CARRIER_PREFIX', CAST(a.id AS VARCHAR(64)), a.revtype
              FROM carrier_prefixes_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            UNION ALL
            SELECT r.rev, r.rev_tstmp, r.actor_type, r.actor_id, r.request_id,
                   r.ip_address, r.user_agent,
                   'OUTBOUND_MESSAGE', CAST(a.id AS VARCHAR(64)), a.revtype
              FROM outbound_messages_aud a
              JOIN audit_revision_info r ON r.rev = a.rev
            """;

    /* =========================
       PAGINATION
       ========================= */

    public static final String PAGE_MYSQL =
            "LIMIT :limit OFFSET :offset";

    public static final String PAGE_MSSQL =
            "OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY";

    /* =========================
       BASE SELECT
       ========================= */

    public static final String SELECT_COLUMNS = """
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
            """;

    /* =========================
       FILTER TEMPLATE
       ========================= */

    public static final String FILTER_TEMPLATE = """
            FROM (
            %s
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
}
