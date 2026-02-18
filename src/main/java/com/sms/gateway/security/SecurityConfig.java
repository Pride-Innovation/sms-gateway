package com.sms.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sms.gateway.users.ApiClientService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new RestAccessDeniedHandler(objectMapper))
                )
                .authorizeHttpRequests(auth -> auth
                        // Health endpoints (keep minimal)
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Auth endpoints
                        .requestMatchers("/api/auth/**").permitAll()
                        // SMS APIs require API-client auth (no JWT)
                        .requestMatchers("/api/sms/**").hasRole("API_CLIENT")
                        // Admin APIs require ADMIN role via JWT
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                // Ensure API client auth runs for /api/sms/** before authorization
                .addFilterBefore(apiClientAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // JWT auth for the rest
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
