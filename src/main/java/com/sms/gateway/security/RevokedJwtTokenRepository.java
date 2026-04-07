package com.sms.gateway.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface RevokedJwtTokenRepository extends JpaRepository<RevokedJwtToken, String> {
    boolean existsByJtiAndExpiresAtAfter(String jti, Instant now);

    void deleteByExpiresAtBefore(Instant now);
}