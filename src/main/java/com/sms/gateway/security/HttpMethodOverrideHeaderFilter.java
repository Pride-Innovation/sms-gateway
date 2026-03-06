package com.sms.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Supports method tunneling when upstream WAF only allows GET/POST.
 * Only POST requests with X-HTTP-Method-Override: PUT|DELETE are rewritten.
 */
public class HttpMethodOverrideHeaderFilter extends OncePerRequestFilter {

    public static final String METHOD_OVERRIDE_HEADER = "X-HTTP-Method-Override";
    public static final String ORIGINAL_METHOD_ATTR = "gateway.originalHttpMethod";
    public static final String EFFECTIVE_METHOD_ATTR = "gateway.effectiveHttpMethod";

    private static final Set<String> ALLOWED_OVERRIDES = Set.of("PUT", "DELETE", "POST");
    private static final List<String> ELIGIBLE_PATHS = List.of(
            "/api/prefixes/**",
            "/api/admin/api-clients/**",
            "/api/admin/admin-users/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }

        String path = request.getServletPath();
        return ELIGIBLE_PATHS.stream().noneMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String override = request.getHeader(METHOD_OVERRIDE_HEADER);
        if (override == null || override.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String normalized = override.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_OVERRIDES.contains(normalized)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    METHOD_OVERRIDE_HEADER + " must be one of PUT, DELETE or POST");
            return;
        }

        if (HttpMethod.POST.matches(normalized)) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(request) {
            @Override
            public String getMethod() {
                return normalized;
            }
        };

        wrapped.setAttribute(ORIGINAL_METHOD_ATTR, request.getMethod());
        wrapped.setAttribute(EFFECTIVE_METHOD_ATTR, normalized);
        filterChain.doFilter(wrapped, response);
    }
}
