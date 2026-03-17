package com.sms.gateway.api;

import com.sms.gateway.adminuser.AccountLockedException;
import com.sms.gateway.service.TooManyRequestsException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized error mapping for clean API responses.
 * <p>
 * Production:
 * - Keep messages safe (don’t leak credentials/internal stack traces).
 * - Return consistent JSON structures for observability and clients.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> constraint(ConstraintViolationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Validation failed", "details", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> beanValidation(MethodArgumentNotValidException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Validation failed");
        body.put("details", e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<?> tooMany(TooManyRequestsException e) {
        return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<?> accountLocked(AccountLockedException e) {
        return ResponseEntity.status(423).body(Map.of(
                "error", e.getMessage(),
                "accountLocked", true
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> serverError(Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
    }
}