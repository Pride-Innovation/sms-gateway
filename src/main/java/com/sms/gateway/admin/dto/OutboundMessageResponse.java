package com.sms.gateway.admin.dto;

import java.time.Instant;

public record OutboundMessageResponse(
        String requestId,
        String phone,
        String message,
        String senderId,
        String status,
        Instant date
) {}
