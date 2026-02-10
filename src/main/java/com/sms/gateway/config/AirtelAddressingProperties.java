package com.sms.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sms.airtel.addressing")
@Data
public class AirtelAddressingProperties {
    private int sourceTonAlphanumeric;
    private int sourceNpiAlphanumeric;

    private int sourceTonInternational;
    private int sourceNpiE164;

    private int destTonInternational;
    private int destNpiE164;
}
