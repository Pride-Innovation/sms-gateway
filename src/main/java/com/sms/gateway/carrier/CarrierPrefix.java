package com.sms.gateway.carrier;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.Audited;

import java.time.Instant;

@Getter
@Entity
@Audited
@Table(
        name = "carrier_prefixes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_carrier_prefix", columnNames = {"carrier", "prefix"})
        }
)
public class CarrierPrefix {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Carrier carrier;

    @Setter
    @Column(nullable = false, length = 32)
    private String prefix;

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Setter
    @Column(length = 64)
    private String description;

    protected CarrierPrefix() {
    }

    public CarrierPrefix(Carrier carrier, String prefix, boolean active) {
        this.carrier = carrier;
        this.prefix = prefix;
        this.active = active;
        this.description = descriptionForCarrier(carrier);
    }

    public void setCarrier(Carrier carrier) {
        this.carrier = carrier;
        this.description = descriptionForCarrier(carrier);
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.description == null || this.description.isBlank()) {
            this.description = descriptionForCarrier(this.carrier);
        }
    }

    private String descriptionForCarrier(Carrier carrier) {
        return switch (carrier) {
            case MTN -> "MTN Uganda";
            case AIRTEL -> "Airtel Uganda";
        };
    }

}
