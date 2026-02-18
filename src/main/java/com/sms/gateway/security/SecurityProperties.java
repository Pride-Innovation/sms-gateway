package com.sms.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private final Admin admin = new Admin();
    private final Jwt jwt = new Jwt();

    public Admin getAdmin() {
        return admin;
    }

    public Jwt getJwt() { return jwt; }

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
}
