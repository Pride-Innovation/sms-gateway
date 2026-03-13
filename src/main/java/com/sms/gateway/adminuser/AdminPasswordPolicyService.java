package com.sms.gateway.adminuser;

import com.sms.gateway.security.SecurityProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class AdminPasswordPolicyService {

    private final SecurityProperties securityProperties;

    public AdminPasswordPolicyService(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public PasswordStatus evaluate(AdminUser adminUser) {
        Instant expiresAt = getPasswordExpiresAt(adminUser);
        boolean expired = expiresAt != null && !Instant.now().isBefore(expiresAt);
        return new PasswordStatus(adminUser.isPasswordChangeRequired(), expired, expiresAt);
    }

    public void markTemporaryPassword(AdminUser adminUser) {
        Instant now = Instant.now();
        adminUser.setPasswordChangedAt(now);
        adminUser.setPasswordChangeRequired(true);
    }

    public void markPasswordChanged(AdminUser adminUser) {
        Instant now = Instant.now();
        adminUser.setPasswordChangedAt(now);
        adminUser.setPasswordChangeRequired(false);
    }

    private Instant getPasswordExpiresAt(AdminUser adminUser) {
        Instant referenceTime = adminUser.getPasswordChangedAt();
        if (referenceTime == null) {
            referenceTime = adminUser.getUpdatedAt();
        }
        if (referenceTime == null) {
            referenceTime = adminUser.getCreatedAt();
        }
        if (referenceTime == null) {
            return null;
        }
        return referenceTime.plus(getPasswordMaxAge());
    }

    private Duration getPasswordMaxAge() {
        long expiryDays = Math.max(1L, securityProperties.getAdmin().getPasswordExpiryDays());
        return Duration.ofDays(expiryDays);
    }

    public record PasswordStatus(boolean passwordChangeRequired, boolean passwordExpired, Instant passwordExpiresAt) {
        public boolean blocksAuthentication() {
            return passwordChangeRequired || passwordExpired;
        }
    }
}