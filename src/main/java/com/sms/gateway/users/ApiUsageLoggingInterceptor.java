package com.sms.gateway.users;

import com.sms.gateway.security.ApiClientAuthenticationToken;
import com.sms.gateway.security.ApiClientPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiUsageLoggingInterceptor implements HandlerInterceptor {

    private final ApiUsageLogService usageLogService;

    public ApiUsageLoggingInterceptor(ApiUsageLogService usageLogService) {
        this.usageLogService = usageLogService;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ApiClientAuthenticationToken)) {
            return;
        }
        Object principalObj = authentication.getPrincipal();
        if (!(principalObj instanceof ApiClientPrincipal principal)) {
            return;
        }

        usageLogService.log(
                principal.id(),
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus()
        );
    }
}
