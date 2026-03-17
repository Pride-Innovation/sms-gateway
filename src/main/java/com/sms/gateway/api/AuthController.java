package com.sms.gateway.api;

import com.sms.gateway.adminuser.AdminUser;
import com.sms.gateway.adminuser.AdminPasswordPolicyService;
import com.sms.gateway.adminuser.AdminUserLoginOtpService;
import com.sms.gateway.adminuser.AdminUserRepository;
import com.sms.gateway.adminuser.AdminUserService;
import com.sms.gateway.security.JwtTokenService;
import com.sms.gateway.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AdminUserRepository adminUserRepository;
    private final JwtTokenService jwtTokenService;
    private final AdminUserService adminUserService;
    private final AdminUserLoginOtpService adminUserLoginOtpService;
    private final AdminPasswordPolicyService adminPasswordPolicyService;

    public AuthController(AdminUserRepository adminUserRepository,
                          JwtTokenService jwtTokenService,
                          AdminUserService adminUserService,
                          AdminUserLoginOtpService adminUserLoginOtpService,
                          AdminPasswordPolicyService adminPasswordPolicyService) {
        this.adminUserRepository = adminUserRepository;
        this.jwtTokenService = jwtTokenService;
        this.adminUserService = adminUserService;
        this.adminUserLoginOtpService = adminUserLoginOtpService;
        this.adminPasswordPolicyService = adminPasswordPolicyService;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        var initiation = adminUserLoginOtpService.initiateOtpLogin(req.username(), req.password());
        if (initiation.status() == AdminUserLoginOtpService.Status.ACCOUNT_LOCKED) {
            return buildLockedAccountResponse("This admin account has been locked after multiple failed login attempts. Please contact your administrator to reopen it.");
        }
        if (initiation.status() == AdminUserLoginOtpService.Status.ACCOUNT_DISABLED) {
            return buildDisabledAccountResponse("This admin account has been disabled. Please contact your administrator.");
        }
        if (initiation.status() == AdminUserLoginOtpService.Status.INVALID_WARNING) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "Invalid credentials",
                    "warning", "If we receive 3 failed attempts, your account will be blocked.",
                    "attemptsRemaining", 1
            ));
        }
        if (initiation.status() == AdminUserLoginOtpService.Status.INVALID) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
        if (initiation.status() == AdminUserLoginOtpService.Status.PASSWORD_CHANGE_REQUIRED) {
            return buildPasswordChangeRequiredResponse(req.username(), initiation.passwordStatus(), true,
                    "Password change required before login");
        }
        if (initiation.status() == AdminUserLoginOtpService.Status.PASSWORD_EXPIRED) {
            return buildPasswordChangeRequiredResponse(req.username(), initiation.passwordStatus(), false,
                    "Password expired. Change your password to continue");
        }

        return ResponseEntity.ok(Map.of(
                "message", "OTP sent to your email",
                "otpRequired", true,
                "otpTtlMinutes", initiation.otpTtlMinutes()
        ));
    }

    public record VerifyLoginOtpRequest(@NotBlank String username, @NotBlank String otp) {}

    @PostMapping("/login/verify-otp")
    public ResponseEntity<?> verifyLoginOtp(@RequestBody @Valid VerifyLoginOtpRequest req) {
        var verification = adminUserLoginOtpService.verifyOtpAndGetUser(req.username(), req.otp());
        if (verification.status() == AdminUserLoginOtpService.Status.ACCOUNT_LOCKED) {
            return buildLockedAccountResponse("This admin account has been locked after multiple failed login attempts. Please contact your administrator to reopen it.");
        }
        if (verification.status() == AdminUserLoginOtpService.Status.ACCOUNT_DISABLED) {
            return buildDisabledAccountResponse("This admin account has been disabled. Please contact your administrator.");
        }
        if (verification.status() == AdminUserLoginOtpService.Status.INVALID) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid OTP"));
        }
        if (verification.status() == AdminUserLoginOtpService.Status.EXPIRED) {
            return ResponseEntity.status(401).body(Map.of("error", "OTP expired"));
        }
        if (verification.status() == AdminUserLoginOtpService.Status.TOO_MANY_ATTEMPTS) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many OTP attempts"));
        }
        if (verification.status() == AdminUserLoginOtpService.Status.PASSWORD_CHANGE_REQUIRED) {
            AdminUser user = adminUserRepository.findByUsernameIgnoreCase(req.username()).orElse(null);
            AdminPasswordPolicyService.PasswordStatus passwordStatus = user == null
                ? null
                : adminPasswordPolicyService.evaluate(user);
            return buildPasswordChangeRequiredResponse(passwordStatus, true,
                "Password change required before login", null);
        }
        if (verification.status() == AdminUserLoginOtpService.Status.PASSWORD_EXPIRED) {
            AdminUser user = adminUserRepository.findByUsernameIgnoreCase(req.username()).orElse(null);
            AdminPasswordPolicyService.PasswordStatus passwordStatus = user == null
                ? null
                : adminPasswordPolicyService.evaluate(user);
            return buildPasswordChangeRequiredResponse(passwordStatus, false,
                "Password expired. Change your password to continue", null);
        }

        AdminUser user = verification.user();
        String access = jwtTokenService.createAccessToken(user.getUsername(), java.util.List.of("ADMIN"));
        String refresh = jwtTokenService.createRefreshToken(user.getUsername());
        return ResponseEntity.ok(Map.of("accessToken", access, "refreshToken", refresh));
    }

    public record RefreshRequest(@NotBlank String refreshToken) {}

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest req) {
        try {
            var claims = jwtTokenService.parse(req.refreshToken());
            if (!"refresh".equals(claims.get("type"))) {
                return ResponseEntity.status(400).body(Map.of("error", "Invalid token type"));
            }
            String username = claims.getSubject();
            // Ensure user still exists and enabled
            AdminUser user = adminUserRepository.findByUsernameIgnoreCase(username).orElse(null);
            if (user == null) {
                return ResponseEntity.status(401).body(Map.of("error", "User invalid"));
            }
            if (user.isAccountLocked()) {
                return buildLockedAccountResponse("This admin account has been locked after multiple failed login attempts. Please contact your administrator to reopen it.");
            }
            if (!user.isEnabled()) {
                return buildDisabledAccountResponse("This admin account has been disabled. Please contact your administrator.");
            }
            AdminPasswordPolicyService.PasswordStatus passwordStatus = adminPasswordPolicyService.evaluate(user);
            if (passwordStatus.blocksAuthentication()) {
                return buildPasswordChangeRequiredResponse(passwordStatus, passwordStatus.passwordChangeRequired(),
                        passwordStatus.passwordExpired()
                                ? "Password expired. Change your password to continue"
                        : "Password change required before login", null);
            }
            String access = jwtTokenService.createAccessToken(username, java.util.List.of("ADMIN"));
            return ResponseEntity.ok(Map.of("accessToken", access));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token"));
        }
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}

    public record RequiredPasswordChangeRequest(@NotBlank String oldPassword,
                                                @NotBlank String newPassword) {}

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody @Valid ChangePasswordRequest req) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof UserPrincipal userPrincipal)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        adminUserService.changePassword(userPrincipal.getUsername(), req.oldPassword(), req.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    @PostMapping("/change-password/required")
    public ResponseEntity<?> changeRequiredPassword(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestBody @Valid RequiredPasswordChangeRequest req
    ) {
        Claims challengeClaims = jwtTokenService.parsePasswordChangeToken(extractBearerToken(authorizationHeader));
        String username = challengeClaims.getSubject();
        adminUserService.changePasswordForBlockedLogin(username, req.oldPassword(), req.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    public record ForgotPasswordRequest(@NotBlank String email) {}

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody @Valid ForgotPasswordRequest req) {
        // Always return generic success to avoid account enumeration.
        adminUserService.requestPasswordReset(req.email());
        return ResponseEntity.ok(Map.of("message", "If the account exists, a password reset link has been sent"));
    }

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank String newPassword) {}

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody @Valid ResetPasswordRequest req) {
        adminUserService.resetPasswordWithToken(req.token(), req.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
    }

    private ResponseEntity<?> buildPasswordChangeRequiredResponse(
            AdminPasswordPolicyService.PasswordStatus passwordStatus,
            boolean firstLogin,
            String message,
            String passwordChangeToken
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("passwordChangeRequired", true);
        body.put("firstLogin", firstLogin);
        body.put("passwordExpired", passwordStatus != null && passwordStatus.passwordExpired());
        body.put("passwordExpiresAt", passwordStatus == null ? null : passwordStatus.passwordExpiresAt());
        if (passwordChangeToken != null && !passwordChangeToken.isBlank()) {
            body.put("passwordChangeToken", passwordChangeToken);
        }
        return ResponseEntity.status(403).body(body);
    }

    private ResponseEntity<?> buildPasswordChangeRequiredResponse(
            String username,
            AdminPasswordPolicyService.PasswordStatus passwordStatus,
            boolean firstLogin,
            String message
    ) {
        String reason = firstLogin ? "first_login" : "password_expired";
        String passwordChangeToken = jwtTokenService.createPasswordChangeToken(username.trim(), reason);
        return buildPasswordChangeRequiredResponse(passwordStatus, firstLogin, message, passwordChangeToken);
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank() || !authorizationHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Password change token is required");
        }
        return authorizationHeader.substring(7).trim();
    }

    private ResponseEntity<?> buildDisabledAccountResponse(String message) {
        return ResponseEntity.status(403).body(Map.of(
                "error", message,
                "accountDisabled", true
        ));
    }

    private ResponseEntity<?> buildLockedAccountResponse(String message) {
        return ResponseEntity.status(423).body(Map.of(
                "error", message,
                "accountLocked", true
        ));
    }
}
