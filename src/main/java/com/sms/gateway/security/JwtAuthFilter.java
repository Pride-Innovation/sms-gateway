package com.sms.gateway.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;

    public JwtAuthFilter(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip for SMS client endpoints, public auth endpoints, and health
        return path.startsWith("/api/sms")
            || isPublicAuthPath(path)
                || path.startsWith("/actuator/health");
    }

        private boolean isPublicAuthPath(String path) {
        return "/api/auth/login".equals(path)
                || "/api/auth/login/verify-otp".equals(path)
            || "/api/auth/refresh".equals(path)
            || "/api/auth/forgot-password".equals(path)
            || "/api/auth/reset-password".equals(path);
        }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            throw new BadCredentialsException("Missing bearer token");
        }

        String token = authHeader.substring(7);
        Claims claims = jwtTokenService.parse(token);

        String subject = claims.getSubject();
        @SuppressWarnings("unchecked")
        List<String> roles = claims.containsKey("roles") ? (List<String>) claims.get("roles") : List.of();

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }

        UserPrincipal principal = new UserPrincipal(subject);
        UserAuthenticationToken auth = new UserAuthenticationToken(principal, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }
}
