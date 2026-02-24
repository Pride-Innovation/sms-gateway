package com.sms.gateway.adminuser;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final AdminUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AdminUserEmailService adminUserEmailService;

    public AdminUserService(
            AdminUserRepository repository,
            PasswordEncoder passwordEncoder,
            AdminUserEmailService adminUserEmailService
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.adminUserEmailService = adminUserEmailService;
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
            existing.setPasswordHash(passwordEncoder.encode(rawPassword));
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
}
