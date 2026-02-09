package com.sms.gateway.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SendSmsRequest {
    @NotBlank
    private String to;

    @NotBlank
    @Size(max = 2000)
    private String text;

    @Size(max = 20)
    private String senderId;

    @Size(max = 200)
    private String idempotencyKey;
}