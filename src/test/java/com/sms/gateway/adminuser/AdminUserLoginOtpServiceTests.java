package com.sms.gateway.adminuser;

import com.sms.gateway.security.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserLoginOtpServiceTests {

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AdminUserLoginOtpRepository otpRepository;

    @Mock
    private AdminUserEmailService adminUserEmailService;

    private AdminUserLoginOtpService adminUserLoginOtpService;

    @BeforeEach
    void setUp() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.getAdmin().setPasswordExpiryDays(90);
        AdminPasswordPolicyService adminPasswordPolicyService = new AdminPasswordPolicyService(securityProperties);
        adminUserLoginOtpService = new AdminUserLoginOtpService(
                adminUserRepository,
                passwordEncoder,
                otpRepository,
                adminUserEmailService,
                securityProperties,
                adminPasswordPolicyService
        );
    }

    @Test
    void initiateOtpLoginBlocksFirstLoginUntilPasswordChanges() {
        AdminUser adminUser = baseAdminUser();
        adminUser.setPasswordChangeRequired(true);
        adminUser.setPasswordChangedAt(Instant.now());

        when(adminUserRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("Temp#123", "stored-hash")).thenReturn(true);

        var result = adminUserLoginOtpService.initiateOtpLogin("alice", "Temp#123");

        assertEquals(AdminUserLoginOtpService.Status.PASSWORD_CHANGE_REQUIRED, result.status());
    }

    @Test
    void initiateOtpLoginBlocksExpiredPasswords() {
        AdminUser adminUser = baseAdminUser();
        adminUser.setPasswordChangeRequired(false);
        adminUser.setPasswordChangedAt(Instant.now().minus(91, ChronoUnit.DAYS));

        when(adminUserRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("Current#123", "stored-hash")).thenReturn(true);

        var result = adminUserLoginOtpService.initiateOtpLogin("alice", "Current#123");

        assertEquals(AdminUserLoginOtpService.Status.PASSWORD_EXPIRED, result.status());
    }

    private AdminUser baseAdminUser() {
        AdminUser adminUser = new AdminUser();
        adminUser.setUsername("alice");
        adminUser.setEmail("alice@example.com");
        adminUser.setEnabled(true);
        adminUser.setPasswordHash("stored-hash");
        adminUser.setCreatedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        adminUser.setUpdatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        return adminUser;
    }
}