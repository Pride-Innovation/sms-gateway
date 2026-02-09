package com.sms.gateway.service;

public interface SmsStore {
    void put(SmsStatus status, String idempotencyKeyHash);

    SmsStatus get(String requestId);

    void updateState(String requestId, String state, String error);

    void addMessageId(String requestId, String messageId);

    String findByIdempotencyKey(String idempotencyKeyHash);
}