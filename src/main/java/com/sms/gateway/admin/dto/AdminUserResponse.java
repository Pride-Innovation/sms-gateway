package com.sms.gateway.admin.dto;

import java.time.Instant;

public record AdminUserResponse(
        Long id,
        String username,
        String firstName,
        String lastName,
        String email,
        String title,
        String department,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
