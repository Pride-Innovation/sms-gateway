package com.sms.gateway.api;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SendSmsResponse {
    private String requestId;
    private String status; // QUEUED / SENT / FAILED
}
