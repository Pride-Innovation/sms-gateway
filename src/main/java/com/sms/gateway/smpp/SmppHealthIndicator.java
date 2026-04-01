package com.sms.gateway.smpp;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("smpp")
public class SmppHealthIndicator implements HealthIndicator {
    // Expose provider-level SMPP state through /actuator/health so operations can see whether
    // MTN and Airtel are currently bound, retrying, or waiting for the next recovery attempt.
    private final SmppSessionManager mtnSmppSessionManager;
    private final ObjectProvider<SmppSessionManager> airtelSmppSessionManagerProvider;

    public SmppHealthIndicator(
            @Qualifier("mtnSmppSessionManager") SmppSessionManager mtnSmppSessionManager,
            @Qualifier("airtelSmppSessionManager") ObjectProvider<SmppSessionManager> airtelSmppSessionManagerProvider
    ) {
        this.mtnSmppSessionManager = mtnSmppSessionManager;
        this.airtelSmppSessionManagerProvider = airtelSmppSessionManagerProvider;
    }

    @Override
    public Health health() {
        SmppSessionManager.SmppManagerHealthSnapshot mtn = mtnSmppSessionManager.healthSnapshot();
        SmppSessionManager airtelManager = airtelSmppSessionManagerProvider.getIfAvailable();
        SmppSessionManager.SmppManagerHealthSnapshot airtel = airtelManager == null ? null : airtelManager.healthSnapshot();

        // The component is DOWN if MTN is down, or if Airtel is configured and down.
        boolean up = mtn.up() && (airtel == null || airtel.up());
        Health.Builder builder = up ? Health.up() : Health.down();
        builder.withDetail("mtn", mtn.toHealthDetails());
        if (airtel != null) {
            builder.withDetail("airtel", airtel.toHealthDetails());
        } else {
            // Airtel is optional in this deployment, so report its absence explicitly instead of failing health.
            builder.withDetail("airtel", "not-configured");
        }

        return builder.build();
    }
}