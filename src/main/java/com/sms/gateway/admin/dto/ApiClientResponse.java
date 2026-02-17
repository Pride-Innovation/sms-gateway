package com.sms.gateway.admin.dto;

import java.time.Instant;

public class ApiClientResponse {

    private Long id;
    private String username;
    private String description;
    private boolean blocked;
    private Instant createdAt;
    private Instant updatedAt;

    public ApiClientResponse(Long id, String username, String description, boolean blocked, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.username = username;
        this.description = description;
        this.blocked = blocked;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDescription() {
        return description;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
