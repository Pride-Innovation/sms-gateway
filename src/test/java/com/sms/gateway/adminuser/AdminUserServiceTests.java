package com.sms.gateway.adminuser;

import com.sms.gateway.security.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTests {

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AdminUserEmailService adminUserEmailService;

    @Mock
    private AdminUserPasswordResetTokenRepository passwordResetTokenRepository;

    private AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.getAdmin().setPasswordExpiryDays(90);
        AdminPasswordPolicyService adminPasswordPolicyService = new AdminPasswordPolicyService(securityProperties);
        adminUserService = new AdminUserService(
                adminUserRepository,
                passwordEncoder,
                adminUserEmailService,
                passwordResetTokenRepository,
                securityProperties,
                adminPasswordPolicyService
        );
    }

    @Test
    void createMarksGeneratedPasswordForMandatoryChange() {
        when(adminUserRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.empty());
        when(adminUserRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Temp#123")).thenReturn("encoded-temp");
        when(adminUserRepository.save(any(AdminUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adminUserEmailService.sendWelcomeEmail(any(AdminUser.class), any(String.class))).thenReturn(true);

        AdminUser created = adminUserService.create(
                "alice",
                "Temp#123",
                true,
                "Alice",
                "Admin",
                "alice@example.com",
                "Manager",
                "IT"
        );

        assertTrue(created.isPasswordChangeRequired());
        assertNotNull(created.getPasswordChangedAt());
    }

    @Test
    void changePasswordRejectsReusingCurrentPassword() {
        AdminUser adminUser = new AdminUser();
        adminUser.setUsername("alice");
        adminUser.setPasswordHash("stored-hash");

        when(adminUserRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("Current#123", "stored-hash")).thenReturn(true);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> adminUserService.changePassword("alice", "Current#123", "Current#123"));

        assertTrue(exception.getMessage().contains("different from the current password"));
    }
}