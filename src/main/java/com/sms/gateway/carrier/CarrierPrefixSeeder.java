package com.sms.gateway.carrier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CarrierPrefixSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(CarrierPrefixSeeder.class);

    private final CarrierPrefixService service;

    public CarrierPrefixSeeder(CarrierPrefixService service) {
        this.service = service;
    }

    @Override
    public void run(String... args) {
        // Prefixes are stored in normalized digits-only form (matching SmsService.normalizeMsisdn):
        // "0xx" local prefixes become "256xx" after normalization.
        seedCarrier(Carrier.MTN, List.of(
                "25676", // 076x
                "25677", // 077x
                "25678", // 078x
                "25679", // 079x
                "25631", // 031x
                "25639"  // 039x
        ));

        seedCarrier(Carrier.AIRTEL, List.of(
                "25670", // 070x
                "25674", // 074x
                "25675", // 075x
                "25620"  // 020x
        ));

        log.info("Carrier prefix seed complete");
    }

    private void seedCarrier(Carrier carrier, List<String> prefixes) {
        for (String p : prefixes) {
            try {
                service.upsert(carrier, p, true);
            } catch (Exception e) {
                log.warn("Seed prefix failed carrier={} prefix={} reason={}", carrier, p, e.getMessage());
            }
        }
    }
}
