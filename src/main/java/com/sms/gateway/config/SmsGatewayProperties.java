package com.sms.gateway.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sms.gateway")
@Data
public class SmsGatewayProperties {
    private int queueCapacity = 20000;
    private int workerThreads = 16;
    private int maxTextChars = 2000;
    private int otpPriority = 100;
    private int notificationPriority = 10;
    private int otpDefaultTtlSeconds = 300;
    private int maxRetryAttempts = 5;
    private int retryBaseDelaySeconds = 5;
    private int retryMaxDelaySeconds = 300;
    private int workerIdleSleepMs = 100;
    private int recoveryIntervalSeconds = 30;
    private int staleProcessingTimeoutSeconds = 120;

    /**
     * Alphanumeric sender IDs are often limited by SMSCs (commonly 11).
     * If your provider allows 12+ for branded sender IDs, increase this.
     */
    private int maxAlphanumericSenderIdLength = 11;
}
