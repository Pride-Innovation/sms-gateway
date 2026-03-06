package com.sms.gateway.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

public class AuditRequestContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        String ipAddress = resolveIpAddress(request);
        String userAgent = trim(request.getHeader("User-Agent"), 512);

        AuditRequestContextHolder.set(new AuditRequestContext(requestId, ipAddress, userAgent));
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            AuditRequestContextHolder.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (StringUtils.hasText(requestId)) {
            return trim(requestId, 120);
        }
        return UUID.randomUUID().toString();
    }

    private String resolveIpAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int commaIndex = forwarded.indexOf(',');
            String firstHop = commaIndex > -1 ? forwarded.substring(0, commaIndex) : forwarded;
            return trim(firstHop.trim(), 64);
        }
        return trim(request.getRemoteAddr(), 64);
    }

    private String trim(String value, int maxLen) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
