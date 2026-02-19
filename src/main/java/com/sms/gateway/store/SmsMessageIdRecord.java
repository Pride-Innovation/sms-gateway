package com.sms.gateway.store;

import jakarta.persistence.*;

@Entity
@Table(name = "sms_message_ids", indexes = {
        @Index(name = "idx_sms_message_ids_request", columnList = "request_id")
})
public class SmsMessageIdRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Column(name = "message_id", nullable = false, length = 200)
    private String messageId;

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
}
