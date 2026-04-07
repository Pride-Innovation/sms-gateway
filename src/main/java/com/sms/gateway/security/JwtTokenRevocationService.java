package com.sms.gateway.security;

import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenRevocationService {

    private final RevokedJwtTokenRepository revokedJwtTokenRepository;

    public JwtTokenRevocationService(RevokedJwtTokenRepository revokedJwtTokenRepository) {
        this.revokedJwtTokenRepository = revokedJwtTokenRepository;
    }

    public void revoke(Claims claims) {
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            return;
        }

        Instant expiresAt = toInstant(claims.getExpiration());
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            return;
        }

        revokedJwtTokenRepository.deleteByExpiresAtBefore(Instant.now());
        if (revokedJwtTokenRepository.existsById(jti)) {
            return;
        }

        RevokedJwtToken revokedToken = new RevokedJwtToken();
        revokedToken.setJti(jti);
        revokedToken.setTokenType(String.valueOf(claims.get("type")));
        revokedToken.setSubject(claims.getSubject());
        revokedToken.setExpiresAt(expiresAt == null ? Instant.now() : expiresAt);
        revokedToken.setRevokedAt(Instant.now());
        revokedJwtTokenRepository.save(revokedToken);
    }

    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return revokedJwtTokenRepository.existsByJtiAndExpiresAtAfter(jti, Instant.now());
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}