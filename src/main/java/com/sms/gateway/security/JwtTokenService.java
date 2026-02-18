package com.sms.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class JwtTokenService {

    private final SecretKey secretKey;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtTokenService(SecurityProperties securityProperties) {
        SecurityProperties.Jwt jwt = securityProperties.getJwt();
        String configured = jwt.getSecret();
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("app.security.jwt.secret must be configured");
        }
        this.secretKey = buildSecretKey(configured);
        this.accessTtlSeconds = jwt.getAccessTtlSeconds();
        this.refreshTtlSeconds = jwt.getRefreshTtlSeconds();
    }

    public String createAccessToken(String subject, List<String> roles) {
        return createToken(subject, roles, accessTtlSeconds, Map.of("type", "access"));
    }

    public String createRefreshToken(String subject) {
        return createToken(subject, List.of(), refreshTtlSeconds, Map.of("type", "refresh"));
    }

    private String createToken(String subject, List<String> roles, long ttlSeconds, Map<String, Object> extraClaims) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(ttlSeconds)))
                .addClaims(extraClaims)
                .claim("roles", roles)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private SecretKey buildSecretKey(String secret) {
        // Support explicit prefixes for clarity
        if (secret.startsWith("base64:")) {
            byte[] keyBytes = Decoders.BASE64.decode(secret.substring("base64:".length()));
            return Keys.hmacShaKeyFor(keyBytes);
        }
        if (secret.startsWith("hex:")) {
            byte[] keyBytes = decodeHex(secret.substring("hex:".length()));
            return Keys.hmacShaKeyFor(keyBytes);
        }

        // Try Base64 first; if it fails, derive a 256-bit key from the string
        try {
            byte[] keyBytes = Decoders.BASE64.decode(secret);
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception ignored) {
            // Derive a stable 256-bit key using SHA-256 of the provided string
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(secret.getBytes(StandardCharsets.UTF_8));
                return Keys.hmacShaKeyFor(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Failed to initialize JWT secret", e);
            }
        }
    }

    private byte[] decodeHex(String hex) {
        String s = hex.replace(" ", "");
        int len = s.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex length");
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            if (hi == -1 || lo == -1) {
                throw new IllegalArgumentException("Invalid hex character");
            }
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }
}
