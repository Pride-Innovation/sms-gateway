package com.sms.gateway.message;

import com.sms.gateway.carrier.Carrier;
import jakarta.persistence.*;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Entity
@Table(name = "outbound_messages", indexes = {
        @Index(name = "idx_outbound_messages_request_id", columnList = "requestId", unique = true),
    @Index(name = "idx_outbound_messages_phone", columnList = "phone"),
    @Index(name = "idx_outbound_messages_carrier", columnList = "carrier")
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

    // Status: QUEUED / SENT / FAILED
    @Column(nullable = false, length = 20)
    private String status;

    // Timestamp when the message send completed (SENT/FAILED). Null while QUEUED.
    @Column(name = "date")
    private Instant date;

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Long getApiClientId() { return apiClientId; }
    public void setApiClientId(Long apiClientId) { this.apiClientId = apiClientId; }
    public String getApiClientName() { return apiClientName; }
    public void setApiClientName(String apiClientName) { this.apiClientName = apiClientName; }
    public Carrier getCarrier() { return carrier; }
    public void setCarrier(Carrier carrier) { this.carrier = carrier; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getDate() { return date; }
    public void setDate(Instant date) { this.date = date; }
}
