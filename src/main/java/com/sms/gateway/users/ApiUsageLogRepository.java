package com.sms.gateway.users;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiUsageLogRepository extends JpaRepository<ApiUsageLog, Long> {

    Page<ApiUsageLog> findByApiClientIdOrderByTimestampDesc(Long apiClientId, Pageable pageable);

    Page<ApiUsageLog> findAllByOrderByTimestampDesc(Pageable pageable);
}
