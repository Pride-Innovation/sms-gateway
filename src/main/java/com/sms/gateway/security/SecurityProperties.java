package com.sms.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private final Admin admin = new Admin();

    public Admin getAdmin() {
        return admin;
    }

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
}
