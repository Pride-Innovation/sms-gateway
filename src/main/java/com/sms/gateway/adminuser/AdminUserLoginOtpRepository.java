package com.sms.gateway.adminuser;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AdminUserLoginOtpRepository extends JpaRepository<AdminUserLoginOtp, Long> {
    List<AdminUserLoginOtp> findByAdminUserAndUsedAtIsNull(AdminUser adminUser);
    Optional<AdminUserLoginOtp> findTopByAdminUserAndUsedAtIsNullOrderByCreatedAtDesc(AdminUser adminUser);
    long deleteByExpiresAtBefore(Instant now);
}
