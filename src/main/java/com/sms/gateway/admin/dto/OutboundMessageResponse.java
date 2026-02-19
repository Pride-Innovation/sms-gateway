package com.sms.gateway.admin.dto;

import com.sms.gateway.carrier.Carrier;
import java.time.Instant;

public record OutboundMessageResponse(
        String requestId,
        String phone,
        Carrier carrier,
        String message,
        String senderId,
        String status,
        Instant date
) {}
