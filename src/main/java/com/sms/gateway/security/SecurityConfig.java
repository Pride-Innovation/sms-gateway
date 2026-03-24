package com.sms.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sms.gateway.audit.AuditRequestContextFilter;
import com.sms.gateway.users.ApiClientService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            ApiClientService apiClientService,
            JwtTokenService jwtTokenService
    ) throws Exception {

        ApiClientAuthFilter apiClientAuthFilter = new ApiClientAuthFilter(apiClientService);
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtTokenService);
        HttpMethodOverrideHeaderFilter methodOverrideHeaderFilter = new HttpMethodOverrideHeaderFilter();
        AuditRequestContextFilter auditRequestContextFilter = new AuditRequestContextFilter();

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // REST API is stateless/token or API-client auth; ignore CSRF here.
//                        .ignoringRequestMatchers("/api/**", "/actuator/**")
                        .ignoringRequestMatchers("/actuator/**")
                )
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new RestAccessDeniedHandler(objectMapper))
                )
                .authorizeHttpRequests(auth -> auth
                        // Health endpoints (keep minimal)
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Public auth endpoints
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/login/verify-otp",
                                "/api/auth/change-password/required",
                                "/api/auth/refresh",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/auth/csrf"
                        ).permitAll()
                        // Authenticated user password change
                        .requestMatchers("/api/auth/change-password").hasRole("ADMIN")
                        // SMS APIs require API-client auth (no JWT)
                        .requestMatchers("/api/sms/**").hasRole("API_CLIENT")
                        // Admin APIs require ADMIN role via JWT
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                        // Capture request metadata once so DB revisions can include request context.
                        .addFilterBefore(auditRequestContextFilter, UsernamePasswordAuthenticationFilter.class)
                // Allow POST method tunneling (X-HTTP-Method-Override) for selected API paths.
                .addFilterBefore(methodOverrideHeaderFilter, UsernamePasswordAuthenticationFilter.class)
                // Ensure API client auth runs for /api/sms/** before authorization
                .addFilterBefore(apiClientAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // JWT auth for the rest
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(SecurityProperties securityProperties) {
        SecurityProperties.Cors c = securityProperties.getCors();
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(new ArrayList<>(c.getAllowedOrigins()));

        Set<String> allowedMethods = new LinkedHashSet<>(c.getAllowedMethods());
        allowedMethods.add("OPTIONS");
        cfg.setAllowedMethods(new ArrayList<>(allowedMethods));

        Set<String> allowedHeaders = new LinkedHashSet<>(c.getAllowedHeaders());
        // Keep method tunneling functional even when env provides an explicit CORS header list.
        if (allowedHeaders.stream().noneMatch("*"::equals)) {
            allowedHeaders.add("Authorization");
            allowedHeaders.add("Content-Type");
            allowedHeaders.add(HttpMethodOverrideHeaderFilter.METHOD_OVERRIDE_HEADER);
            allowedHeaders.add(HttpMethodOverrideHeaderFilter.METHOD_OVERRIDE_HEADER.toLowerCase(Locale.ROOT));
        }
        cfg.setAllowedHeaders(new ArrayList<>(allowedHeaders));
        cfg.setExposedHeaders(new ArrayList<>(c.getExposedHeaders()));
        cfg.setAllowCredentials(c.isAllowCredentials());
        cfg.setMaxAge(c.getMaxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
