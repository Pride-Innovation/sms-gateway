package com.sms.gateway.admin.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateDescriptionRequest {

    @NotBlank
    private String description;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
