package com.sms.gateway.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class CreateApiClientRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    @NotBlank
    private String description;

}
