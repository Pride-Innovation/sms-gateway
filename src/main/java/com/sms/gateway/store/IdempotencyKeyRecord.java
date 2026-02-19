package com.sms.gateway.store;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "idempotency_keys", indexes = {
    @Index(name = "idx_idempotency_key", columnList = "key_hash", unique = true)
})
public class IdempotencyKeyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Avoid reserved keyword 'key' in MySQL
    @Column(name = "key_hash", nullable = false, length = 200, unique = true)
    private String keyHash;

    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
