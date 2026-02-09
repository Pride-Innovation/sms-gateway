package com.sms.gateway.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sms.gateway")
@Data
public class SmsGatewayProperties {
    private int queueCapacity = 20000;
    private int workerThreads = 16;
    private int maxTextChars = 2000;

    /**
     * Alphanumeric sender IDs are often limited by SMSCs (commonly 11).
     * If your provider allows 12+ for branded sender IDs, increase this.
     */
    private int maxAlphanumericSenderIdLength = 11;
}
