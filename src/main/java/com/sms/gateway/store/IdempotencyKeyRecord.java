package com.sms.gateway.store;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Entity
@Table(name = "idempotency_keys", indexes = {
        @Index(name = "idx_idempotency_key", columnList = "key_hash", unique = true)
})
public class IdempotencyKeyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Avoid reserved keyword 'key' in MySQL
    @Setter
    @Column(name = "key_hash", nullable = false, length = 200, unique = true)
    private String keyHash;

    @Setter
    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Setter
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}
