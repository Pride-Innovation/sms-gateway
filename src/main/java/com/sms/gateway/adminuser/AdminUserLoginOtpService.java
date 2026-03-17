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
    private final AdminPasswordPolicyService adminPasswordPolicyService;

    public AdminUserLoginOtpService(
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder,
            AdminUserLoginOtpRepository otpRepository,
            AdminUserEmailService adminUserEmailService,
            SecurityProperties securityProperties,
            AdminPasswordPolicyService adminPasswordPolicyService
    ) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.otpRepository = otpRepository;
        this.adminUserEmailService = adminUserEmailService;
        this.securityProperties = securityProperties;
        this.adminPasswordPolicyService = adminPasswordPolicyService;
    }

    @Transactional
    public LoginInitiationResult initiateOtpLogin(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return LoginInitiationResult.invalidCredentials();
        }

        AdminUser user = adminUserRepository.findByUsernameIgnoreCase(username.trim()).orElse(null);
        if (user == null) {
            return LoginInitiationResult.invalidCredentials();
        }
        if (!user.isEnabled()) {
            return LoginInitiationResult.accountDisabled();
        }
        if (user.isAccountLocked()) {
            return LoginInitiationResult.accountLocked();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            return registerFailedAttempt(user);
        }

        resetFailedAttemptsIfNeeded(user);

        AdminPasswordPolicyService.PasswordStatus passwordStatus = adminPasswordPolicyService.evaluate(user);
        if (passwordStatus.blocksAuthentication()) {
            return LoginInitiationResult.blocked(passwordStatus);
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
        return LoginInitiationResult.otpSent(ttlMinutes);
    }

    @Transactional
    public OtpVerificationResult verifyOtpAndGetUser(String username, String otp) {
        if (username == null || username.isBlank() || otp == null || otp.isBlank()) {
            return OtpVerificationResult.invalid();
        }

        AdminUser user = adminUserRepository.findByUsernameIgnoreCase(username.trim()).orElse(null);
        if (user == null) {
            return OtpVerificationResult.invalid();
        }
        if (!user.isEnabled()) {
            return OtpVerificationResult.accountDisabled();
        }
        if (user.isAccountLocked()) {
            return OtpVerificationResult.accountLocked();
        }

        AdminPasswordPolicyService.PasswordStatus passwordStatus = adminPasswordPolicyService.evaluate(user);
        if (passwordStatus.blocksAuthentication()) {
            return OtpVerificationResult.blocked(passwordStatus);
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

        public static OtpVerificationResult blocked(AdminPasswordPolicyService.PasswordStatus passwordStatus) {
            if (passwordStatus.passwordExpired()) {
                return new OtpVerificationResult(Status.PASSWORD_EXPIRED, null);
            }
            return new OtpVerificationResult(Status.PASSWORD_CHANGE_REQUIRED, null);
        }

        public static OtpVerificationResult accountDisabled() {
            return new OtpVerificationResult(Status.ACCOUNT_DISABLED, null);
        }

        public static OtpVerificationResult accountLocked() {
            return new OtpVerificationResult(Status.ACCOUNT_LOCKED, null);
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

    public record LoginInitiationResult(Status status, AdminPasswordPolicyService.PasswordStatus passwordStatus, Integer otpTtlMinutes) {
        public static LoginInitiationResult otpSent(int otpTtlMinutes) {
            return new LoginInitiationResult(Status.SUCCESS, null, otpTtlMinutes);
        }

        public static LoginInitiationResult invalidCredentials() {
            return new LoginInitiationResult(Status.INVALID, null, null);
        }

        public static LoginInitiationResult invalidCredentialsWarning() {
            return new LoginInitiationResult(Status.INVALID_WARNING, null, null);
        }

        public static LoginInitiationResult accountDisabled() {
            return new LoginInitiationResult(Status.ACCOUNT_DISABLED, null, null);
        }

        public static LoginInitiationResult accountLocked() {
            return new LoginInitiationResult(Status.ACCOUNT_LOCKED, null, null);
        }

        public static LoginInitiationResult blocked(AdminPasswordPolicyService.PasswordStatus passwordStatus) {
            if (passwordStatus.passwordExpired()) {
                return new LoginInitiationResult(Status.PASSWORD_EXPIRED, passwordStatus, null);
            }
            return new LoginInitiationResult(Status.PASSWORD_CHANGE_REQUIRED, passwordStatus, null);
        }
    }

    public enum Status {
        SUCCESS,
        PASSWORD_CHANGE_REQUIRED,
        PASSWORD_EXPIRED,
        ACCOUNT_DISABLED,
        ACCOUNT_LOCKED,
        INVALID_WARNING,
        INVALID,
        EXPIRED,
        TOO_MANY_ATTEMPTS
    }

    private LoginInitiationResult registerFailedAttempt(AdminUser user) {
        int maxAttempts = Math.max(1, securityProperties.getAdmin().getMaxFailedLoginAttempts());
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        if (attempts >= maxAttempts) {
            user.setAccountLocked(true);
            user.setAccountLockedAt(Instant.now());
            adminUserRepository.save(user);
            adminUserEmailService.sendAccountLockedEmail(user, maxAttempts);
            return LoginInitiationResult.accountLocked();
        }

        adminUserRepository.save(user);
        if (attempts == maxAttempts - 1) {
            return LoginInitiationResult.invalidCredentialsWarning();
        }
        return LoginInitiationResult.invalidCredentials();
    }

    private void resetFailedAttemptsIfNeeded(AdminUser user) {
        if (user.getFailedLoginAttempts() == 0 && !user.isAccountLocked() && user.getAccountLockedAt() == null) {
            return;
        }
        user.setFailedLoginAttempts(0);
        user.setAccountLocked(false);
        user.setAccountLockedAt(null);
        adminUserRepository.save(user);
    }
}
