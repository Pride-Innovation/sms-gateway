package com.sms.gateway.api;

import com.sms.gateway.adminuser.AdminPasswordPolicyService;
import com.sms.gateway.adminuser.AdminUserLoginOtpService;
import com.sms.gateway.adminuser.AdminUserRepository;
import com.sms.gateway.adminuser.AdminUserService;
import com.sms.gateway.security.JwtTokenService;
import com.sms.gateway.security.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTests {

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private AdminUserService adminUserService;

    @Mock
    private AdminUserLoginOtpService adminUserLoginOtpService;

    @Mock
    private AdminPasswordPolicyService adminPasswordPolicyService;

    private JwtTokenService jwtTokenService;
    private AuthController authController;

    @BeforeEach
    void setUp() {
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.getJwt().setSecret("unit-test-secret-for-jwt-token-service");
        securityProperties.getAdmin().setPasswordChangeTokenTtlMinutes(10);
        jwtTokenService = new JwtTokenService(securityProperties);
        authController = new AuthController(
                adminUserRepository,
                jwtTokenService,
                adminUserService,
                adminUserLoginOtpService,
                adminPasswordPolicyService
        );
    }

    @Test
    void blockedLoginReturnsPasswordChangeChallengeToken() {
        AdminPasswordPolicyService.PasswordStatus passwordStatus =
                new AdminPasswordPolicyService.PasswordStatus(true, false, Instant.now().plusSeconds(3600));
        when(adminUserLoginOtpService.initiateOtpLogin("alice", "Temp#123"))
                .thenReturn(AdminUserLoginOtpService.LoginInitiationResult.blocked(passwordStatus));

        ResponseEntity<?> response = authController.login(new AuthController.LoginRequest("alice", "Temp#123"));

        assertEquals(403, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("passwordChangeRequired"));
        assertNotNull(body.get("passwordChangeToken"));
    }

    @Test
    void disabledLoginReturnsDedicatedDisabledResponse() {
        when(adminUserLoginOtpService.initiateOtpLogin("alice", "Temp#123"))
                .thenReturn(AdminUserLoginOtpService.LoginInitiationResult.accountDisabled());

        ResponseEntity<?> response = authController.login(new AuthController.LoginRequest("alice", "Temp#123"));

        assertEquals(403, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("accountDisabled"));
    }

    @Test
    void requiredPasswordChangeUsesUsernameFromChallengeToken() {
        String token = jwtTokenService.createPasswordChangeToken("alice", "first_login");

        ResponseEntity<?> response = authController.changeRequiredPassword(
                "Bearer " + token,
                new AuthController.RequiredPasswordChangeRequest("Temp#123", "New#123")
        );

        assertEquals(200, response.getStatusCode().value());
        verify(adminUserService).changePasswordForBlockedLogin("alice", "Temp#123", "New#123");
    }

    @Test
    void requiredPasswordChangeRejectsMissingChallengeToken() {
        assertThrows(IllegalArgumentException.class, () -> authController.changeRequiredPassword(
                null,
                new AuthController.RequiredPasswordChangeRequest("Temp#123", "New#123")
        ));
    }
}