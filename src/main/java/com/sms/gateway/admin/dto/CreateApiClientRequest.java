package com.sms.gateway.admin.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateApiClientRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    @NotBlank
    private String description;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
