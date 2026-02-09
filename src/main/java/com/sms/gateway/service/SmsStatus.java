package com.sms.gateway.service;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks lifecycle/state of an SMS request.
 * <p>
 * Thread-safety:
 * - This object is stored in a ConcurrentHashMap and mutated by worker threads.
 * - state/error are volatile so reads see the latest updates without external synchronization.
 * - messageIds uses CopyOnWriteArrayList because number of messageIds is small (1.N segments),
 * and writes are infrequent compared to reads (status polling).
 * <p>
 * Production note:
 * - For a real gateway, this should be persisted (DB/Redis) so status survives restarts
 * and works across multiple app instances.
 */
@Getter
@ToString
public class SmsStatus {
    private final String requestId;
    private final String toMsisdn;
    private final String senderId;
    private final Instant createdAt;

    // Mutable fields updated as the job progresses:
    @Setter
    private volatile String state; // QUEUED/SENDING/SENT/FAILED/REJECTED
    @Setter
    private volatile String error; // optional

    /**
     * messageIds returned by SMSC per segment (multipart) or per message (single part).
     * Thread-safe list for concurrent add + read.
     */
    private final List<String> messageIds = new CopyOnWriteArrayList<>();

    public SmsStatus(String requestId,
                     String toMsisdn,
                     String senderId,
                     Instant createdAt,
                     String state,
                     String error) {
        this.requestId = requestId;
        this.toMsisdn = toMsisdn;
        this.senderId = senderId;
        this.createdAt = createdAt;
        this.state = state;
        this.error = error;
    }

}