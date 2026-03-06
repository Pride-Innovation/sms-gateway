package com.sms.gateway.adminuser;

import com.sms.gateway.security.SecurityProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class AdminUserLoginOtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminUserLoginOtpRepository otpRepository;
    private final AdminUserEmailService adminUserEmailService;
    private final SecurityProperties securityProperties;

    public AdminUserLoginOtpService(
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder,
            AdminUserLoginOtpRepository otpRepository,
            AdminUserEmailService adminUserEmailService,
            SecurityProperties securityProperties
    ) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.otpRepository = otpRepository;
        this.adminUserEmailService = adminUserEmailService;
        this.securityProperties = securityProperties;
    }

    @Transactional
    public boolean initiateOtpLogin(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return false;
        }

        AdminUser user = adminUserRepository.findByUsernameIgnoreCase(username.trim()).orElse(null);
        if (user == null || !user.isEnabled() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return false;
        }

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalArgumentException("No email configured for this admin user");
        }

        Instant now = Instant.now();
        for (AdminUserLoginOtp existing : otpRepository.findByAdminUserAndUsedAtIsNull(user)) {
            if (!existing.isExpired(now)) {
                existing.setUsedAt(now);
                otpRepository.save(existing);
            }
        }

        int ttlMinutes = Math.max(1, securityProperties.getLoginOtp().getTtlMinutes());
        String otp = generateSixDigitOtp();

        AdminUserLoginOtp loginOtp = new AdminUserLoginOtp();
        loginOtp.setAdminUser(user);
        loginOtp.setOtpHash(hashOtp(user.getUsername(), otp));
        loginOtp.setExpiresAt(now.plusSeconds(ttlMinutes * 60L));
        loginOtp.setAttempts(0);
        otpRepository.save(loginOtp);

        boolean sent = adminUserEmailService.sendLoginOtpEmail(user, otp, ttlMinutes);
        if (!sent) {
            throw new IllegalStateException("Unable to send OTP email at the moment");
        }
        return true;
    }

    @Transactional
    public OtpVerificationResult verifyOtpAndGetUser(String username, String otp) {
        if (username == null || username.isBlank() || otp == null || otp.isBlank()) {
            return OtpVerificationResult.invalid();
        }

        AdminUser user = adminUserRepository.findByUsernameIgnoreCase(username.trim()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return OtpVerificationResult.invalid();
        }

        AdminUserLoginOtp loginOtp = otpRepository
                .findTopByAdminUserAndUsedAtIsNullOrderByCreatedAtDesc(user)
                .orElse(null);
        if (loginOtp == null) {
            return OtpVerificationResult.invalid();
        }

        Instant now = Instant.now();
        if (loginOtp.isExpired(now)) {
            loginOtp.setUsedAt(now);
            otpRepository.save(loginOtp);
            return OtpVerificationResult.expired();
        }

        int maxAttempts = Math.max(1, securityProperties.getLoginOtp().getMaxAttempts());
        if (loginOtp.getAttempts() >= maxAttempts) {
            loginOtp.setUsedAt(now);
            otpRepository.save(loginOtp);
            return OtpVerificationResult.tooManyAttempts();
        }

        String expectedHash = hashOtp(user.getUsername(), otp.trim());
        if (!MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                loginOtp.getOtpHash().getBytes(StandardCharsets.UTF_8))) {
            loginOtp.setAttempts(loginOtp.getAttempts() + 1);
            if (loginOtp.getAttempts() >= maxAttempts) {
                loginOtp.setUsedAt(now);
            }
            otpRepository.save(loginOtp);
            return OtpVerificationResult.invalid();
        }

        loginOtp.setUsedAt(now);
        otpRepository.save(loginOtp);
        return OtpVerificationResult.success(user);
    }

    public int getOtpTtlMinutes() {
        return Math.max(1, securityProperties.getLoginOtp().getTtlMinutes());
    }

    private String generateSixDigitOtp() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format("%06d", value);
    }

    private String hashOtp(String username, String otp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((username.toLowerCase() + ":" + otp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash OTP", e);
        }
    }

    public record OtpVerificationResult(Status status, AdminUser user) {
        public static OtpVerificationResult success(AdminUser user) {
            return new OtpVerificationResult(Status.SUCCESS, user);
        }

        public static OtpVerificationResult invalid() {
            return new OtpVerificationResult(Status.INVALID, null);
        }

        public static OtpVerificationResult expired() {
            return new OtpVerificationResult(Status.EXPIRED, null);
        }

        public static OtpVerificationResult tooManyAttempts() {
            return new OtpVerificationResult(Status.TOO_MANY_ATTEMPTS, null);
        }
    }

    public enum Status {
        SUCCESS,
        INVALID,
        EXPIRED,
        TOO_MANY_ATTEMPTS
    }
}
