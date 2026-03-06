package com.sms.gateway.admin.dto;

import java.time.Instant;

public record AuditTrailResponse(
        Integer revisionId,
        Instant changedAt,
        String actorType,
        String actorId,
        String entityType,
        String entityId,
        String operation,
        String requestId,
        String ipAddress,
        String userAgent
) {
}
