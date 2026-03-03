package com.sms.gateway.users;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Service
public class ApiClientService {

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789@#$%";
    private static final int GENERATED_PASSWORD_LENGTH = 20;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiClientRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final ApiClientEmailService apiClientEmailService;

    public ApiClientService(
            ApiClientRepository repository,
            PasswordEncoder passwordEncoder,
            ApiClientEmailService apiClientEmailService
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.apiClientEmailService = apiClientEmailService;
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
            ApiClient savedClient = repository.save(client);
            apiClientEmailService.sendAccountCreatedEmail(savedClient, rawPassword);
            return savedClient;
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
    public String regeneratePassword(Long id) {
        ApiClient client = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ApiClient not found"));
        String password = generatePassword();
        client.setPasswordHash(passwordEncoder.encode(password));
        ApiClient savedClient = repository.save(client);
        apiClientEmailService.sendPasswordRegeneratedEmail(savedClient, password);
        return password;
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("ApiClient not found");
        }
        repository.deleteById(id);
    }

    private String generatePassword() {
        StringBuilder password = new StringBuilder(GENERATED_PASSWORD_LENGTH);
        for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
            int index = SECURE_RANDOM.nextInt(PASSWORD_CHARS.length());
            password.append(PASSWORD_CHARS.charAt(index));
        }
        return password.toString();
    }
}
