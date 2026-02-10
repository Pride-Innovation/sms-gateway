package com.sms.gateway.carrier;

import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class CarrierRouter {
    private final CarrierPrefixRepository repo;

    public CarrierRouter(CarrierPrefixRepository repo) {
        this.repo = repo;
    }

    /**
     * Resolve carrier by longest-prefix match.
     * Expects a normalized digits-only MSISDN (for this project: "256" + 9 digits).
     */
    public Carrier resolveOrThrow(String normalizedMsisdn) {
        String v = (normalizedMsisdn == null) ? "" : normalizedMsisdn.trim();
        if (v.isBlank()) throw new IllegalArgumentException("toMsisdn is blank");

        String carrier = repo.resolveCarrierNative(v);
        if (carrier == null || carrier.isBlank()) {
            throw new IllegalArgumentException("Unknown carrier for destination: " + v);
        }

        return Carrier.valueOf(carrier.trim().toUpperCase(Locale.ROOT));
    }
}
