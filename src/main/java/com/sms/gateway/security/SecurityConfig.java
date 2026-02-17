package com.sms.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sms.gateway.users.ApiClientService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
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
            ApiClientService apiClientService
    ) throws Exception {

        ApiClientAuthFilter apiClientAuthFilter = new ApiClientAuthFilter(apiClientService);

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
                        // Admin dashboard APIs
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // SMS APIs require API-client auth
                        .requestMatchers("/api/sms/**").hasRole("API_CLIENT")
                        // Allow everything else for now (can tighten later)
                        .anyRequest().permitAll()
                )
                .httpBasic(Customizer.withDefaults())
                // Ensure API client auth runs for /api/sms/** before authorization
                .addFilterBefore(apiClientAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
