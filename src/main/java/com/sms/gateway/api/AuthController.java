package com.sms.gateway.api;

import com.sms.gateway.adminuser.AdminUser;
import com.sms.gateway.adminuser.AdminUserLoginOtpService;
import com.sms.gateway.adminuser.AdminUserRepository;
import com.sms.gateway.adminuser.AdminUserService;
import com.sms.gateway.security.JwtTokenService;
import com.sms.gateway.security.UserPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AdminUserRepository adminUserRepository;
    private final JwtTokenService jwtTokenService;
    private final AdminUserService adminUserService;
    private final AdminUserLoginOtpService adminUserLoginOtpService;

    public AuthController(AdminUserRepository adminUserRepository,
                          JwtTokenService jwtTokenService,
                          AdminUserService adminUserService,
                          AdminUserLoginOtpService adminUserLoginOtpService) {
        this.adminUserRepository = adminUserRepository;
        this.jwtTokenService = jwtTokenService;
        this.adminUserService = adminUserService;
        this.adminUserLoginOtpService = adminUserLoginOtpService;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        boolean initiated = adminUserLoginOtpService.initiateOtpLogin(req.username(), req.password());
        if (!initiated) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        return ResponseEntity.ok(Map.of(
                "message", "OTP sent to your email",
                "otpRequired", true,
            "otpTtlMinutes", adminUserLoginOtpService.getOtpTtlMinutes()
        ));
    }

    public record VerifyLoginOtpRequest(@NotBlank String username, @NotBlank String otp) {}

    @PostMapping("/login/verify-otp")
    public ResponseEntity<?> verifyLoginOtp(@RequestBody @Valid VerifyLoginOtpRequest req) {
        var verification = adminUserLoginOtpService.verifyOtpAndGetUser(req.username(), req.otp());
        if (verification.status() == AdminUserLoginOtpService.Status.INVALID) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid OTP"));
        }
        if (verification.status() == AdminUserLoginOtpService.Status.EXPIRED) {
            return ResponseEntity.status(401).body(Map.of("error", "OTP expired"));
        }
        if (verification.status() == AdminUserLoginOtpService.Status.TOO_MANY_ATTEMPTS) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many OTP attempts"));
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
            if (user == null || !user.isEnabled()) {
                return ResponseEntity.status(401).body(Map.of("error", "User invalid"));
            }
            String access = jwtTokenService.createAccessToken(username, java.util.List.of("ADMIN"));
            return ResponseEntity.ok(Map.of("accessToken", access));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token"));
        }
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}

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
}
