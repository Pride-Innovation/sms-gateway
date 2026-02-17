package com.sms.gateway.users;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiUsageLogService {

    private final ApiUsageLogRepository usageRepository;
    private final ApiClientRepository apiClientRepository;

    public ApiUsageLogService(ApiUsageLogRepository usageRepository, ApiClientRepository apiClientRepository) {
        this.usageRepository = usageRepository;
        this.apiClientRepository = apiClientRepository;
    }

    @Transactional
    public void log(Long apiClientId, String method, String path, int statusCode) {
        ApiUsageLog log = new ApiUsageLog();
        log.setApiClient(apiClientRepository.getReferenceById(apiClientId));
        log.setMethod(method);
        log.setPath(path);
        log.setStatusCode(statusCode);
        usageRepository.save(log);
    }
}
