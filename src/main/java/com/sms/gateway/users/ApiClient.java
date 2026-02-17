package com.sms.gateway.users;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Entity
@Table(
        name = "api_clients",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_api_clients_username", columnNames = "username")
        }
)
public class ApiClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(nullable = false, length = 100)
    private String username;

    @Setter
    @Column(nullable = false, length = 200)
    private String passwordHash;

    @Setter
    @Column(nullable = false, length = 255)
    private String description;

    @Setter
    @Column(nullable = false)
    private boolean blocked = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

}
