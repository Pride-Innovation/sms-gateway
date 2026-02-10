package com.sms.gateway.config;

import com.sms.gateway.smpp.SmppSessionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmppProvidersConfig {
    @Bean("airtelSmppSessionManager")
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${sms.airtel.smpp.host:}') && ${sms.airtel.smpp.port:0} > 0 && T(org.springframework.util.StringUtils).hasText('${sms.airtel.smpp.systemId:}')")
    public SmppSessionManager airtelSmppSessionManager(AirtelSmppProperties props) {
        // Separate SMPP session manager for Airtel.
        // MTN continues to use the default SmppSessionManager bean bound to sms.smpp.
        SmppProperties p = new SmppProperties();
        p.setHost(props.getHost());
        p.setPort(props.getPort());
        p.setSystemId(props.getSystemId());
        p.setPassword(props.getPassword());
        p.setSystemType(props.getSystemType());
        p.setBindType(props.getBindType());
        p.setInterfaceVersion(props.getInterfaceVersion());
        p.setConnectTimeoutMs(props.getConnectTimeoutMs());
        p.setRequestExpiryTimeoutMs(props.getRequestExpiryTimeoutMs());
        p.setBindTimeoutMs(props.getBindTimeoutMs());
        p.setWindowSize(props.getWindowSize());
        p.setEnquireLinkIntervalMs(props.getEnquireLinkIntervalMs());
        p.setReconnectDelayMs(props.getReconnectDelayMs());
        p.setRegisteredDelivery(props.isRegisteredDelivery());
        p.setDefaultSenderId(props.getDefaultSenderId());
        p.setTps(props.getTps());
        p.setSessions(props.getSessions());
        return new SmppSessionManager(p);
    }
}
