package com.sms.gateway.admin.dto;

import java.time.Instant;

public class ApiUsageResponse {

    private Long id;
    private Long apiClientId;
    private String apiClientUsername;
    private String method;
    private String path;
    private int statusCode;
    private Instant timestamp;

    public ApiUsageResponse(Long id, Long apiClientId, String apiClientUsername, String method, String path, int statusCode, Instant timestamp) {
        this.id = id;
        this.apiClientId = apiClientId;
        this.apiClientUsername = apiClientUsername;
        this.method = method;
        this.path = path;
        this.statusCode = statusCode;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public Long getApiClientId() {
        return apiClientId;
    }

    public String getApiClientUsername() {
        return apiClientUsername;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
