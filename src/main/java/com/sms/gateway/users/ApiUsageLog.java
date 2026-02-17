package com.sms.gateway.users;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Entity
@Table(name = "api_usage_logs", indexes = {
        @Index(name = "idx_api_usage_logs_client_ts", columnList = "api_client_id,timestamp"),
        @Index(name = "idx_api_usage_logs_ts", columnList = "timestamp")
})
public class ApiUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "api_client_id", nullable = false)
    private ApiClient apiClient;

    @Setter
    @Column(nullable = false, length = 10)
    private String method;

    @Setter
    @Column(nullable = false, length = 200)
    private String path;

    @Setter
    @Column(nullable = false)
    private int statusCode;

    @Column(nullable = false)
    private Instant timestamp;

    @PrePersist
    void prePersist() {
        this.timestamp = Instant.now();
    }

}
