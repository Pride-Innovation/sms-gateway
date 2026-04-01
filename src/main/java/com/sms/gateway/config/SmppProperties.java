package com.sms.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sms.smpp")
@Data
public class SmppProperties {
    private String host;
    private int port;
    private String systemId;
    private String password;
    private String systemType;
    private String bindType; // TRANSCEIVER/TRANSMITTER/RECEIVER
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

    // TPS in "segments per second"
    private int tps;

    // New: number of sessions to bind
    private int sessions = 1;
}