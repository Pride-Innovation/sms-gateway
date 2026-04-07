package com.sms.gateway.api;

import com.sms.gateway.adminuser.AdminPasswordPolicyService;
import com.sms.gateway.adminuser.AdminUserLoginOtpService;
import com.sms.gateway.adminuser.AdminUserRepository;
import com.sms.gateway.adminuser.AdminUserService;
import com.sms.gateway.security.JwtTokenRevocationService;
import com.sms.gateway.security.JwtTokenService;
import com.sms.gateway.security.RevokedJwtToken;
import com.sms.gateway.security.RevokedJwtTokenRepository;
import com.sms.gateway.security.SecurityProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private RevokedJwtTokenRepository revokedJwtTokenRepository;

    private JwtTokenService jwtTokenService;
    private JwtTokenRevocationService jwtTokenRevocationService;
    private AuthController authController;
    private Map<String, RevokedJwtToken> revokedTokens;

    @BeforeEach
    void setUp() {
        revokedTokens = new HashMap<>();
        lenient().when(revokedJwtTokenRepository.existsByJtiAndExpiresAtAfter(anyString(), any()))
            .thenAnswer(invocation -> {
                String jti = invocation.getArgument(0);
                java.time.Instant now = invocation.getArgument(1);
                RevokedJwtToken revokedJwtToken = revokedTokens.get(jti);
                return revokedJwtToken != null && revokedJwtToken.getExpiresAt().isAfter(now);
            });
        lenient().when(revokedJwtTokenRepository.existsById(anyString()))
            .thenAnswer(invocation -> revokedTokens.containsKey(invocation.getArgument(0)));
        lenient().when(revokedJwtTokenRepository.save(any(RevokedJwtToken.class)))
            .thenAnswer(invocation -> {
                RevokedJwtToken token = invocation.getArgument(0);
                revokedTokens.put(token.getJti(), token);
                return token;
            });
        lenient().doAnswer(invocation -> {
            java.time.Instant cutoff = invocation.getArgument(0);
            revokedTokens.entrySet().removeIf(entry -> entry.getValue().getExpiresAt().isBefore(cutoff));
            return null;
        }).when(revokedJwtTokenRepository).deleteByExpiresAtBefore(any());

        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.getJwt().setSecret("unit-test-secret-for-jwt-token-service");
        securityProperties.getAdmin().setPasswordChangeTokenTtlMinutes(10);
        jwtTokenRevocationService = new JwtTokenRevocationService(revokedJwtTokenRepository);
        jwtTokenService = new JwtTokenService(securityProperties, jwtTokenRevocationService);
        authController = new AuthController(
                adminUserRepository,
                jwtTokenService,
            jwtTokenRevocationService,
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
    void warningLoginReturnsFailedAttemptWarning() {
        when(adminUserLoginOtpService.initiateOtpLogin("alice", "wrong-password"))
                .thenReturn(AdminUserLoginOtpService.LoginInitiationResult.invalidCredentialsWarning());

        ResponseEntity<?> response = authController.login(new AuthController.LoginRequest("alice", "wrong-password"));

        assertEquals(401, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(1, body.get("attemptsRemaining"));
    }

    @Test
    void lockedLoginReturnsDedicatedLockedResponse() {
        when(adminUserLoginOtpService.initiateOtpLogin("alice", "wrong-password"))
                .thenReturn(AdminUserLoginOtpService.LoginInitiationResult.accountLocked());

        ResponseEntity<?> response = authController.login(new AuthController.LoginRequest("alice", "wrong-password"));

        assertEquals(423, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("accountLocked"));
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

    @Test
    void logoutRevokesAccessAndRefreshTokens() {
        String accessToken = jwtTokenService.createAccessToken("alice", java.util.List.of("ADMIN"));
        String refreshToken = jwtTokenService.createRefreshToken("alice");

        ResponseEntity<?> response = authController.logout(
                "Bearer " + accessToken,
                new AuthController.LogoutRequest(refreshToken)
        );

        assertEquals(200, response.getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> jwtTokenService.parse(accessToken));
        assertThrows(IllegalArgumentException.class, () -> jwtTokenService.parse(refreshToken));
    }

    @Test
    void logoutRejectsMixedUserTokens() {
        String accessToken = jwtTokenService.createAccessToken("alice", java.util.List.of("ADMIN"));
        String refreshToken = jwtTokenService.createRefreshToken("bob");

        ResponseEntity<?> response = authController.logout(
                "Bearer " + accessToken,
                new AuthController.LogoutRequest(refreshToken)
        );

        assertEquals(400, response.getStatusCode().value());
    }
}