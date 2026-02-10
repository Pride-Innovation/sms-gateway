package com.sms.gateway.carrier;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Entity
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

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Carrier carrier;

    @Setter
    @Column(nullable = false, length = 32)
    private String prefix;

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    protected CarrierPrefix() {
    }

    public CarrierPrefix(Carrier carrier, String prefix, boolean active) {
        this.carrier = carrier;
        this.prefix = prefix;
        this.active = active;
    }

}
