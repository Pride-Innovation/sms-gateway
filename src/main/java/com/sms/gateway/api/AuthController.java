package com.sms.gateway.api;

import com.sms.gateway.adminuser.AdminUser;
import com.sms.gateway.adminuser.AdminUserRepository;
import com.sms.gateway.adminuser.AdminUserService;
import com.sms.gateway.security.JwtTokenService;
import com.sms.gateway.security.UserPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final AdminUserService adminUserService;

    public AuthController(AdminUserRepository adminUserRepository,
                          PasswordEncoder passwordEncoder,
                          JwtTokenService jwtTokenService,
                          AdminUserService adminUserService) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.adminUserService = adminUserService;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        AdminUser user = adminUserRepository.findByUsernameIgnoreCase(req.username())
                .orElse(null);
        if (user == null || !user.isEnabled() || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

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
    public ResponseEntity<?> changePassword(@RequestBody @Valid ChangePasswordRequest req, Authentication authentication) {
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
