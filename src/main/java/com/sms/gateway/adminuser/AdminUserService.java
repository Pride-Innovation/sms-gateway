package com.sms.gateway.adminuser;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sms.gateway.security.SecurityProperties;
import java.time.Instant;
import java.util.UUID;

@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final AdminUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AdminUserEmailService adminUserEmailService;
    private final AdminUserPasswordResetTokenRepository passwordResetTokenRepository;
    private final SecurityProperties securityProperties;
    private final AdminPasswordPolicyService adminPasswordPolicyService;

    public AdminUserService(
            AdminUserRepository repository,
            PasswordEncoder passwordEncoder,
            AdminUserEmailService adminUserEmailService,
            AdminUserPasswordResetTokenRepository passwordResetTokenRepository,
            SecurityProperties securityProperties,
            AdminPasswordPolicyService adminPasswordPolicyService
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.adminUserEmailService = adminUserEmailService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.securityProperties = securityProperties;
        this.adminPasswordPolicyService = adminPasswordPolicyService;
    }

    @Transactional
    public AdminUser create(String username, String rawPassword, Boolean enabled,
                            String firstName, String lastName, String email, String title, String department) {
        String normalizedUsername = normalizeUsername(username);
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }

        String normalizedEmail = normalizeEmail(email);

        if (repository.findByUsernameIgnoreCase(normalizedUsername).isPresent()) {
            throw new IllegalArgumentException("Admin username already exists");
        }

        if (repository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("Admin email already exists");
        }

        AdminUser adminUser = new AdminUser();
        adminUser.setUsername(normalizedUsername);
        adminUser.setFirstName(normalizeOptional(firstName));
        adminUser.setLastName(normalizeOptional(lastName));
        adminUser.setEmail(normalizedEmail);
        adminUser.setTitle(normalizeOptional(title));
        adminUser.setDepartment(normalizeOptional(department));
        adminUser.setPasswordHash(passwordEncoder.encode(rawPassword));
        adminUser.setEnabled(enabled == null || enabled);
        adminPasswordPolicyService.markTemporaryPassword(adminUser);

        try {
            AdminUser saved = repository.save(adminUser);
            boolean sent = adminUserEmailService.sendWelcomeEmail(saved, rawPassword);
            if (!sent) {
                log.warn("Admin user created but welcome email was not sent. userId={} username={}", saved.getId(), saved.getUsername());
            }
            return saved;
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Admin username already exists");
        }
    }

    @Transactional(readOnly = true)
    public Page<AdminUser> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminUser> listByEmail(String email, Pageable pageable) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        return repository.findByEmailContainingIgnoreCase(email.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public AdminUser get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found"));
    }

    @Transactional
    public AdminUser update(Long id, String username, String rawPassword, Boolean enabled,
                            String firstName, String lastName, String email, String title, String department) {
        AdminUser existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found"));

        if (username != null && !username.isBlank()) {
            String normalizedUsername = normalizeUsername(username);
            repository.findByUsernameIgnoreCase(normalizedUsername)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> {
                        throw new IllegalArgumentException("Admin username already exists");
                    });
            existing.setUsername(normalizedUsername);
        }

        if (rawPassword != null && !rawPassword.isBlank()) {
            validateNewPasswordIsDifferent(existing, rawPassword);
            existing.setPasswordHash(passwordEncoder.encode(rawPassword));
            adminPasswordPolicyService.markTemporaryPassword(existing);
        }

        if (firstName != null) {
            existing.setFirstName(normalizeOptional(firstName));
        }

        if (lastName != null) {
            existing.setLastName(normalizeOptional(lastName));
        }

        if (email != null) {
            String normalizedEmail = normalizeEmail(email);
            repository.findByEmailIgnoreCase(normalizedEmail)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> {
                        throw new IllegalArgumentException("Admin email already exists");
                    });
            existing.setEmail(normalizedEmail);
        }

        if (title != null) {
            existing.setTitle(normalizeOptional(title));
        }

        if (department != null) {
            existing.setDepartment(normalizeOptional(department));
        }

        if (enabled != null) {
            existing.setEnabled(enabled);
        }

        return repository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("Admin user not found");
        }
        repository.deleteById(id);
    }

    @Transactional
    public AdminUser enable(Long id) {
        return setEnabledState(id, true);
    }

    @Transactional
    public AdminUser disable(Long id) {
        return setEnabledState(id, false);
    }

    @Transactional
    public void changePassword(String username, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isBlank()) {
            throw new IllegalArgumentException("oldPassword is required");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("newPassword is required");
        }

        AdminUser adminUser = repository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found"));

        if (!passwordEncoder.matches(oldPassword, adminUser.getPasswordHash())) {
            throw new IllegalArgumentException("Old password is incorrect");
        }

        validateNewPasswordIsDifferent(adminUser, newPassword);
        adminUser.setPasswordHash(passwordEncoder.encode(newPassword));
        adminPasswordPolicyService.markPasswordChanged(adminUser);
        repository.save(adminUser);
    }

    @Transactional
    public void changePasswordForBlockedLogin(String username, String oldPassword, String newPassword) {
        AdminUser adminUser = repository.findByUsernameIgnoreCase(normalizeUsername(username))
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found"));

        AdminPasswordPolicyService.PasswordStatus passwordStatus = adminPasswordPolicyService.evaluate(adminUser);
        if (!passwordStatus.blocksAuthentication()) {
            throw new IllegalArgumentException("Password change challenge is no longer valid");
        }

        changePassword(adminUser.getUsername(), oldPassword, newPassword);
    }

    @Transactional
    public void requestPasswordReset(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }

        String normalizedEmail = normalizeEmail(email);
        AdminUser adminUser = repository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (adminUser == null || !adminUser.isEnabled()) {
            return;
        }

        // Invalidate previous active tokens for the same user
        for (AdminUserPasswordResetToken token : passwordResetTokenRepository.findByAdminUserAndUsedAtIsNull(adminUser)) {
            if (!token.isExpired(Instant.now())) {
                token.setUsedAt(Instant.now());
                passwordResetTokenRepository.save(token);
            }
        }

        String tokenValue = UUID.randomUUID().toString();
        AdminUserPasswordResetToken resetToken = new AdminUserPasswordResetToken();
        resetToken.setToken(tokenValue);
        resetToken.setAdminUser(adminUser);
        resetToken.setExpiresAt(Instant.now().plusSeconds(Math.max(1, securityProperties.getPasswordReset().getTokenTtlMinutes()) * 60));
        passwordResetTokenRepository.save(resetToken);

        String frontendUrl = securityProperties.getPasswordReset().getFrontendUrl();
        String separator = (frontendUrl != null && frontendUrl.contains("?")) ? "&" : "?";
        String resetLink = (frontendUrl == null ? "" : frontendUrl) + separator + "token=" + tokenValue;
        boolean sent = adminUserEmailService.sendPasswordResetEmail(adminUser, resetLink);
        if (!sent) {
            log.warn("Password reset token generated but reset email not sent. userId={} email={}", adminUser.getId(), adminUser.getEmail());
        }
    }

    @Transactional
    public void resetPasswordWithToken(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("newPassword is required");
        }

        AdminUserPasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid token"));

        Instant now = Instant.now();
        if (resetToken.isUsed()) {
            throw new IllegalArgumentException("Token already used");
        }
        if (resetToken.isExpired(now)) {
            throw new IllegalArgumentException("Token expired");
        }

        AdminUser adminUser = resetToken.getAdminUser();
    validateNewPasswordIsDifferent(adminUser, newPassword);
        adminUser.setPasswordHash(passwordEncoder.encode(newPassword));
    adminPasswordPolicyService.markPasswordChanged(adminUser);
        repository.save(adminUser);

        resetToken.setUsedAt(now);
        passwordResetTokenRepository.save(resetToken);
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        return username.trim();
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        return email.trim().toLowerCase();
    }

    private String normalizeOptional(String value) {
        return value == null ? null : value.trim();
    }

    private void validateNewPasswordIsDifferent(AdminUser adminUser, String newPassword) {
        if (adminUser.getPasswordHash() != null && passwordEncoder.matches(newPassword, adminUser.getPasswordHash())) {
            throw new IllegalArgumentException("New password must be different from the current password");
        }
    }

    private AdminUser setEnabledState(Long id, boolean enabled) {
        AdminUser adminUser = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found"));
        adminUser.setEnabled(enabled);
        return repository.save(adminUser);
    }
}
