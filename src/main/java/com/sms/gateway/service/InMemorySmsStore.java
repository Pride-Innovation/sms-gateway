package com.sms.gateway.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory SmsStore implementation.
 * <p>
 * Intended use:
 * - Dev/test environments
 * - Single-instance deployments where "best-effort" status tracking is acceptable
 * <p>
 * Production warnings:
 * - Data is lost on restart.
 * - Not shared across instances (status/idempotency breaks under horizontal scaling).
 * - Unbounded growth if you never purge old entries (memory leak risk).
 * <p>
 * Production replacement recommendation:
 * - Redis (fast + TTL + atomic ops) or a database table with indexes.
 * - If you want in-memory but bounded: use a cache with TTL/size limit (e.g., Caffeine).
 */
public class InMemorySmsStore implements SmsStore {
    private final Map<String, SmsStatus> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byIdempotency = new ConcurrentHashMap<>();

    @Override
    public void put(SmsStatus status, String idempotencyKeyHash) {
        byId.put(status.getRequestId(), status);

        // If the same idempotency key arrives again, return the first requestId.
        // putIfAbsent ensures this is stable under concurrency.
        if (idempotencyKeyHash != null) {
            byIdempotency.putIfAbsent(idempotencyKeyHash, status.getRequestId());
        }
    }

    @Override
    public SmsStatus get(String requestId) {
        return byId.get(requestId);
    }

    @Override
    public void updateState(String requestId, String state, String error) {
        SmsStatus s = byId.get(requestId);
        if (s == null) return;
        s.setState(state);
        s.setError(error);
    }

    @Override
    public void addMessageId(String requestId, String messageId) {
        SmsStatus s = byId.get(requestId);
        if (s == null) return;
        if (messageId != null && !messageId.isBlank()) {
            s.getMessageIds().add(messageId);
        }
    }

    @Override
    public String findByIdempotencyKey(String idempotencyKeyHash) {
        return byIdempotency.get(idempotencyKeyHash);
    }
}