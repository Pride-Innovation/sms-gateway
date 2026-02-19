package com.sms.gateway.store;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sms_status")
public class SmsStatusRecord {

    @Id
    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Column(name = "to_msisdn", nullable = false, length = 20)
    private String toMsisdn;

    @Column(name = "sender_id", nullable = false, length = 20)
    private String senderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "state", nullable = false, length = 20)
    private String state;

    @Column(name = "error", length = 500)
    private String error;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getToMsisdn() { return toMsisdn; }
    public void setToMsisdn(String toMsisdn) { this.toMsisdn = toMsisdn; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
