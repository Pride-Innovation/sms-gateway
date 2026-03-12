package com.sms.gateway.message;

import com.sms.gateway.carrier.Carrier;
import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.Audited;

import java.time.Instant;

@Getter
@Entity
@Setter
@Audited
@Table(name = "outbound_messages", indexes = {
        @Index(name = "idx_outbound_messages_request_id", columnList = "requestId", unique = true),
        @Index(name = "idx_outbound_messages_phone", columnList = "phone"),
    @Index(name = "idx_outbound_messages_carrier", columnList = "carrier"),
    @Index(name = "idx_outbound_messages_queue_lookup", columnList = "status, carrier, priority, nextAttemptAt, id"),
    @Index(name = "idx_outbound_messages_status_locked", columnList = "status, lockedAt"),
    @Index(name = "idx_outbound_messages_expires_at", columnList = "expiresAt")
})
public class OutboundMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String requestId;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column
    private Long apiClientId;

    @Column(length = 100)
    private String apiClientName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Carrier carrier;

    @Column(nullable = false, length = 2000)
    private String message;

    @Size(max = 20)
    @Column(length = 20)
    private String senderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageType messageType;

    // Status: QUEUED / PROCESSING / RETRY / SENT / FAILED / EXPIRED
    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private Integer attemptCount;

    @Column
    private Integer segmentCount;

    @Column
    private Instant expiresAt;

    @Column
    private Instant nextAttemptAt;

    @Column
    private Instant lockedAt;

    @Column(length = 100)
    private String lockedBy;

    @Column(length = 500)
    private String lastError;

    // Timestamp when the message send completed (SENT/FAILED). Null while QUEUED.
    @Column(name = "date")
    private Instant date;

}
