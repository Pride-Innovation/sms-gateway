package com.sms.gateway.admin;

import com.sms.gateway.admin.dto.ApiUsageResponse;
import com.sms.gateway.users.ApiUsageLog;
import com.sms.gateway.users.ApiUsageLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class ApiUsageAdminController {

    private final ApiUsageLogRepository usageLogRepository;

    public ApiUsageAdminController(ApiUsageLogRepository usageLogRepository) {
        this.usageLogRepository = usageLogRepository;
    }

    @GetMapping("/api-usage")
    public Page<ApiUsageResponse> listUsage(
            @RequestParam(required = false) Long apiClientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), 500);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize);

        Page<ApiUsageLog> logs = (apiClientId == null)
                ? usageLogRepository.findAllByOrderByTimestampDesc(pageable)
                : usageLogRepository.findByApiClientIdOrderByTimestampDesc(apiClientId, pageable);

        return logs.map(l -> new ApiUsageResponse(
                l.getId(),
                l.getApiClient().getId(),
                l.getApiClient().getUsername(),
                l.getMethod(),
                l.getPath(),
                l.getStatusCode(),
                l.getTimestamp()
        ));
    }
}
