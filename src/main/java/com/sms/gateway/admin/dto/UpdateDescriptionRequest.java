package com.sms.gateway.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class UpdateDescriptionRequest {

    @NotBlank
    private String description;
    private String username;
}
