package com.sms.gateway.carrier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

@Service
public class CarrierPrefixService {
    private final CarrierPrefixRepository repo;

    public CarrierPrefixService(CarrierPrefixRepository repo) {
        this.repo = repo;
    }

    public List<CarrierPrefix> list(Carrier carrier, boolean activeOnly) {
        return activeOnly
                ? repo.findByCarrierAndActiveTrueOrderByPrefixAsc(carrier)
                : repo.findByCarrierOrderByPrefixAsc(carrier);
    }

    public Page<CarrierPrefix> listPage(Carrier carrier, boolean activeOnly, Pageable pageable) {
        return activeOnly
                ? repo.findByCarrierAndActiveTrue(carrier, pageable)
                : repo.findByCarrier(carrier, pageable);
    }

    public Page<CarrierPrefix> listAllPage(boolean activeOnly, Pageable pageable) {
        return activeOnly
                ? repo.findByActiveTrue(pageable)
                : repo.findAllBy(pageable);
    }

    @Transactional
    public CarrierPrefix upsert(Carrier carrier, String prefix, boolean active) {
        String cleaned = cleanPrefix(prefix);
        CarrierPrefix existing = repo.findByCarrierAndPrefix(carrier, cleaned)
                .orElseGet(() -> new CarrierPrefix(carrier, cleaned, active));

        existing.setCarrier(carrier);
        existing.setPrefix(cleaned);
        existing.setActive(active);
        existing.setDescription(carrier == Carrier.MTN ? "MTN Uganda" : "Airtel Uganda");

        return repo.save(existing);
    }

    @Transactional
    public void deactivate(Carrier carrier, String prefix) {
        String cleaned = cleanPrefix(prefix);
        CarrierPrefix existing = repo.findByCarrierAndPrefix(carrier, cleaned)
                .orElseThrow(() -> new IllegalArgumentException("Prefix not found for carrier"));
        existing.setActive(false);
        repo.save(existing);
    }

    private String cleanPrefix(String prefix) {
        String digits = (prefix == null) ? "" : prefix.replaceAll("[^0-9]", "");
        if (digits.isBlank()) throw new IllegalArgumentException("prefix must not be blank");
        if (digits.length() > 32) throw new IllegalArgumentException("prefix too long");

        // Normalize local UG prefix formats, so they match SmsService.normalizeMsisdn (which produces "256..." numbers).
        // Examples:
        // - "076" => "25676" (matches 076x range)
        // - "070" => "25670"
        // - "031" => "25631"
        // - "020" => "25620"
        if (!digits.startsWith("256") && digits.startsWith("0") && digits.length() >= 3) {
            digits = "256" + digits.substring(1);
        }

        if (!digits.startsWith("256") || digits.length() < 5) {
            throw new IllegalArgumentException("prefix must be in UG format (e.g. 076, 031, 020, or 25676)");
        }
        return digits;
    }
}
