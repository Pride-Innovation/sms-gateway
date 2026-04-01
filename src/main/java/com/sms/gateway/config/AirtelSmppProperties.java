package com.sms.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sms.airtel.smpp")
@Data
public class AirtelSmppProperties {
    private String host;
    private int port;
    private String systemId;
    private String password;
    private String systemType;
    private String bindType;
    private int interfaceVersion;

    private int connectTimeoutMs;
    private int requestExpiryTimeoutMs;
    private int bindTimeoutMs;

    private int windowSize;
    private int enquireLinkIntervalMs;
    private int reconnectDelayMs;
    private int healthCheckIntervalMs = 30000;
    private int forceRebindIntervalMs;

    private boolean registeredDelivery;
    private String defaultSenderId;

    private int tps;
    private int sessions = 1;
}
