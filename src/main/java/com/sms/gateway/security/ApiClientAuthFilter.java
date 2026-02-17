package com.sms.gateway.security;

import com.sms.gateway.users.ApiClient;
import com.sms.gateway.users.ApiClientService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Authenticates API clients calling /api/sms/** endpoints.
 * <p>
 * Supported credential formats:
 * - Custom headers: X-Api-Username / X-Api-Password
 * - HTTP Basic header (Authorization: Basic ...)
 */
public class ApiClientAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_API_USERNAME = "X-Api-Username";
    public static final String HEADER_API_PASSWORD = "X-Api-Password";

    private final ApiClientService apiClientService;

    public ApiClientAuthFilter(ApiClientService apiClientService) {
        this.apiClientService = apiClientService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/sms");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String username = request.getHeader(HEADER_API_USERNAME);
        String password = request.getHeader(HEADER_API_PASSWORD);

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (StringUtils.hasText(authHeader) && authHeader.startsWith("Basic ")) {
                String base64 = authHeader.substring("Basic ".length());
                try {
                    String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
                    int idx = decoded.indexOf(':');
                    if (idx > 0) {
                        username = decoded.substring(0, idx);
                        password = decoded.substring(idx + 1);
                    }
                } catch (IllegalArgumentException ignored) {
                    // invalid base64 -> treat as missing/invalid creds
                }
            }
        }

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BadCredentialsException("Missing API credentials");
        }

        ApiClient client = apiClientService.authenticate(username, password);
        if (client.isBlocked()) {
            throw new AccessDeniedException("API client is blocked");
        }

        ApiClientPrincipal principal = new ApiClientPrincipal(client.getId(), client.getUsername());
        ApiClientAuthenticationToken auth = new ApiClientAuthenticationToken(
                principal,
                List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))
        );

        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }
}
