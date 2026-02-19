package com.sms.gateway.message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Long> {
    Optional<OutboundMessage> findByRequestId(String requestId);

    Page<OutboundMessage> findAllByOrderByDateDesc(Pageable pageable);
    Page<OutboundMessage> findByPhoneOrderByDateDesc(String phone, Pageable pageable);
    Page<OutboundMessage> findByDateBetweenOrderByDateDesc(java.time.Instant from, java.time.Instant to, Pageable pageable);
    Page<OutboundMessage> findByPhoneAndDateBetweenOrderByDateDesc(String phone, java.time.Instant from, java.time.Instant to, Pageable pageable);
}
