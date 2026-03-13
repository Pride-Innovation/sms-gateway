package com.sms.gateway.security;

import lombok.Getter;
import lombok.Setter;
import lombok.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private final Admin admin = new Admin();
    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final PasswordReset passwordReset = new PasswordReset();
    private final Login login = new Login();
    private final LoginOtp loginOtp = new LoginOtp();

    @Setter
    @Getter
    public static class Admin {
        /**
         * Super-admin username for accessing /api/admin/** endpoints (HTTP Basic).
         */
        private String username;

        /**
         * Super-admin password for accessing /api/admin/** endpoints (HTTP Basic).
         */
        private String password;

    }

    @Setter
    @Getter
    public static class Jwt {
        /**
         * Secret key for signing JWTs (HS256).
         */
        private String secret;
        /**
         * Access token time-to-live in seconds.
         */
        private long accessTtlSeconds = 900; // 15 minutes
        /**
         * Refresh token time-to-live in seconds.
         */
        private long refreshTtlSeconds = 2592000; // 30 days

    }

    @Setter
    @Getter
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
        private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        private List<String> allowedHeaders = new ArrayList<>(List.of("*"));
        private List<String> exposedHeaders = new ArrayList<>();
        private boolean allowCredentials = false;
        private long maxAgeSeconds = 3600;

    }

    @Setter
    @Getter
    public static class PasswordReset {
        private String frontendUrl = "http://localhost:3000/reset-password";
        private long tokenTtlMinutes = 30;

    }

    @Setter
    @Getter
    public static class Login {
        private String frontendUrl = "http://localhost:3000/login";

    }

    @Setter
    @Getter
    public static class LoginOtp {
        private int ttlMinutes = 5;
        private int maxAttempts = 5;

    }
}
