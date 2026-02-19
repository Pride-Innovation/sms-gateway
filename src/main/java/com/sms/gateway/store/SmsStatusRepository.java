package com.sms.gateway.store;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsStatusRepository extends JpaRepository<SmsStatusRecord, String> {
}
