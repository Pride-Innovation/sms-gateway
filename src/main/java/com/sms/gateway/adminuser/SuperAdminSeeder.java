package com.sms.gateway.adminuser;

import com.sms.gateway.security.SecurityProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SuperAdminSeeder implements ApplicationRunner {

    private final AdminUserRepository adminUserRepository;
    private final SecurityProperties securityProperties;
    private final PasswordEncoder passwordEncoder;
    private final AdminPasswordPolicyService adminPasswordPolicyService;

    public SuperAdminSeeder(
            AdminUserRepository adminUserRepository,
            SecurityProperties securityProperties,
            PasswordEncoder passwordEncoder,
            AdminPasswordPolicyService adminPasswordPolicyService
    ) {
        this.adminUserRepository = adminUserRepository;
        this.securityProperties = securityProperties;
        this.passwordEncoder = passwordEncoder;
        this.adminPasswordPolicyService = adminPasswordPolicyService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = securityProperties.getAdmin().getUsername();
        String password = securityProperties.getAdmin().getPassword();

        if (username == null || username.isBlank()) {
            throw new IllegalStateException("Missing super-admin username. Set app.security.admin.username");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Missing super-admin password. Set app.security.admin.password");
        }

        String normalizedUsername = username.trim();

        AdminUser admin = adminUserRepository.findByUsernameIgnoreCase(normalizedUsername)
                .orElseGet(() -> {
                    AdminUser created = new AdminUser();
                    created.setUsername(normalizedUsername);
                    return created;
                });

        admin.setEnabled(true);

        // Only update hash if password changed (BCrypt is salted, so we must verify using matches).
        boolean matches = admin.getPasswordHash() != null && passwordEncoder.matches(password, admin.getPasswordHash());
        if (!matches) {
            admin.setPasswordHash(passwordEncoder.encode(password));
            adminPasswordPolicyService.markPasswordChanged(admin);
        } else if (admin.getPasswordChangedAt() == null) {
            adminPasswordPolicyService.markPasswordChanged(admin);
        }

        adminUserRepository.save(admin);
    }
}
