package com.sms.gateway.users;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {
    Optional<ApiClient> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    Page<ApiClient> findByUsernameContainingIgnoreCase(String username, Pageable pageable);
}
