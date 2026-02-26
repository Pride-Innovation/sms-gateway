package com.sms.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private final Admin admin = new Admin();
    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final PasswordReset passwordReset = new PasswordReset();

    public Admin getAdmin() {
        return admin;
    }

    public Jwt getJwt() { return jwt; }

    public Cors getCors() { return cors; }

    public PasswordReset getPasswordReset() { return passwordReset; }

    public static class Admin {
        /**
         * Super-admin username for accessing /api/admin/** endpoints (HTTP Basic).
         */
        private String username;

        /**
         * Super-admin password for accessing /api/admin/** endpoints (HTTP Basic).
         */
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Jwt {
        /** Secret key for signing JWTs (HS256). */
        private String secret;
        /** Access token time-to-live in seconds. */
        private long accessTtlSeconds = 900; // 15 minutes
        /** Refresh token time-to-live in seconds. */
        private long refreshTtlSeconds = 2592000; // 30 days

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getAccessTtlSeconds() { return accessTtlSeconds; }
        public void setAccessTtlSeconds(long accessTtlSeconds) { this.accessTtlSeconds = accessTtlSeconds; }
        public long getRefreshTtlSeconds() { return refreshTtlSeconds; }
        public void setRefreshTtlSeconds(long refreshTtlSeconds) { this.refreshTtlSeconds = refreshTtlSeconds; }
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
        private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        private List<String> allowedHeaders = new ArrayList<>(List.of("*"));
        private List<String> exposedHeaders = new ArrayList<>();
        private boolean allowCredentials = false;
        private long maxAgeSeconds = 3600;

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
        public List<String> getAllowedMethods() { return allowedMethods; }
        public void setAllowedMethods(List<String> allowedMethods) { this.allowedMethods = allowedMethods; }
        public List<String> getAllowedHeaders() { return allowedHeaders; }
        public void setAllowedHeaders(List<String> allowedHeaders) { this.allowedHeaders = allowedHeaders; }
        public List<String> getExposedHeaders() { return exposedHeaders; }
        public void setExposedHeaders(List<String> exposedHeaders) { this.exposedHeaders = exposedHeaders; }
        public boolean isAllowCredentials() { return allowCredentials; }
        public void setAllowCredentials(boolean allowCredentials) { this.allowCredentials = allowCredentials; }
        public long getMaxAgeSeconds() { return maxAgeSeconds; }
        public void setMaxAgeSeconds(long maxAgeSeconds) { this.maxAgeSeconds = maxAgeSeconds; }
    }

    public static class PasswordReset {
        private String frontendUrl = "http://localhost:3000/reset-password";
        private long tokenTtlMinutes = 30;

        public String getFrontendUrl() { return frontendUrl; }
        public void setFrontendUrl(String frontendUrl) { this.frontendUrl = frontendUrl; }
        public long getTokenTtlMinutes() { return tokenTtlMinutes; }
        public void setTokenTtlMinutes(long tokenTtlMinutes) { this.tokenTtlMinutes = tokenTtlMinutes; }
    }
}
