package com.sms.gateway.adminuser;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AdminUserPasswordResetTokenRepository extends JpaRepository<AdminUserPasswordResetToken, Long> {
    Optional<AdminUserPasswordResetToken> findByToken(String token);
    List<AdminUserPasswordResetToken> findByAdminUserAndUsedAtIsNull(AdminUser adminUser);
    long deleteByExpiresAtBefore(Instant now);
}
