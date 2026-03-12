package com.sms.gateway.admin.dto;

import com.sms.gateway.carrier.Carrier;
import com.sms.gateway.message.MessageType;
import java.time.Instant;

public record OutboundMessageResponse(
        String requestId,
        String phone,
        Long apiClientId,
        String apiClientName,
        Carrier carrier,
        String message,
        String senderId,
        MessageType messageType,
        String status,
        Integer attemptCount,
        Instant expiresAt,
        Instant nextAttemptAt,
        String lastError,
        Instant date
) {}
