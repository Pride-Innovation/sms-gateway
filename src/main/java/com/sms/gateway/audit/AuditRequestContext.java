package com.sms.gateway.audit;

public record AuditRequestContext(String requestId, String ipAddress, String userAgent) {
}
