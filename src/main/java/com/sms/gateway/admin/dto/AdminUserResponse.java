package com.sms.gateway.admin.dto;

import java.time.Instant;

public record AdminUserResponse(
        Long id,
        String username,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
