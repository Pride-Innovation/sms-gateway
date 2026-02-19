package com.sms.gateway.store;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SmsMessageIdRepository extends JpaRepository<SmsMessageIdRecord, Long> {
    List<SmsMessageIdRecord> findByRequestId(String requestId);
}
