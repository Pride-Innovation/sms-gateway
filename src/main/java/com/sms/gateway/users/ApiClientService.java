package com.sms.gateway.users;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiClientService {

    private final ApiClientRepository repository;
    private final PasswordEncoder passwordEncoder;

    public ApiClientService(ApiClientRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public ApiClient create(String username, String rawPassword, String description) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }

        ApiClient client = new ApiClient();
        client.setUsername(username.trim());
        client.setPasswordHash(passwordEncoder.encode(rawPassword));
        client.setDescription(description.trim());
        client.setBlocked(false);

        try {
            return repository.save(client);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("username already exists");
        }
    }

    @Transactional(readOnly = true)
    public ApiClient authenticate(String username, String rawPassword) {
        ApiClient client = repository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new BadCredentialsException("Invalid API credentials"));

        if (!passwordEncoder.matches(rawPassword, client.getPasswordHash())) {
            throw new BadCredentialsException("Invalid API credentials");
        }

        return client;
    }

    @Transactional(readOnly = true)
    public Page<ApiClient> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<ApiClient> listByUsername(String username, Pageable pageable) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        return repository.findByUsernameContainingIgnoreCase(username.trim(), pageable);
    }

    @Transactional
    public ApiClient updateDescription(Long id, String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        ApiClient client = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ApiClient not found"));
        client.setDescription(description.trim());
        return repository.save(client);
    }

    @Transactional
    public ApiClient setBlocked(Long id, boolean blocked) {
        ApiClient client = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ApiClient not found"));
        client.setBlocked(blocked);
        return repository.save(client);
    }

    @Transactional
    public ApiClient resetPassword(Long id, String newRawPassword) {
        if (newRawPassword == null || newRawPassword.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
        ApiClient client = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ApiClient not found"));
        client.setPasswordHash(passwordEncoder.encode(newRawPassword));
        return repository.save(client);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("ApiClient not found");
        }
        repository.deleteById(id);
    }
}
