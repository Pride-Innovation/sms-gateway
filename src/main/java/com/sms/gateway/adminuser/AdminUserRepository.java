package com.sms.gateway.adminuser;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {
    Optional<AdminUser> findByUsernameIgnoreCase(String username);

    Optional<AdminUser> findByEmailIgnoreCase(String email);

    Page<AdminUser> findByEmailContainingIgnoreCase(String email, Pageable pageable);
}
