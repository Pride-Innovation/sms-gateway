package com.sms.gateway.service;

public record SmsJob(String requestId, String toMsisdn, String text, String senderId) {
}