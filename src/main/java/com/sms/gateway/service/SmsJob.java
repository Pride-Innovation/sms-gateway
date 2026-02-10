package com.sms.gateway.service;

import com.sms.gateway.carrier.Carrier;

public record SmsJob(
        String requestId,
        String toMsisdn,
        String text,
        String senderId,
        Carrier carrier
) {
}