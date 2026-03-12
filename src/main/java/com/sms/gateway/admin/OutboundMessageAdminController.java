package com.sms.gateway.admin;

import com.sms.gateway.admin.dto.OutboundMessageResponse;
import com.sms.gateway.carrier.Carrier;
import com.sms.gateway.message.OutboundMessage;
import com.sms.gateway.message.OutboundMessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/admin")
public class OutboundMessageAdminController {

    private final OutboundMessageRepository repository;

    public OutboundMessageAdminController(OutboundMessageRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/outbound-messages")
    public Page<OutboundMessageResponse> list(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false, name = "apiClient") String apiClient,
            @RequestParam(required = false) String carrier,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), 500);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "date"));

        String[] phoneFilters = buildPhoneFilters(phone);
        Long apiClientId = parseApiClientId(apiClient);
        String apiClientName = parseApiClientName(apiClient);
        Carrier carrierFilter = parseCarrier(carrier);
        String statusFilter = parseStatus(status);
        Instant startTs = parseInstant(startDate, "startDate");
        Instant endTs = parseInstant(endDate, "endDate");

        if ((startTs == null) != (endTs == null)) {
            throw new IllegalArgumentException("Both startDate and endDate must be provided together");
        }
        if (startTs != null && startTs.isAfter(endTs)) {
            throw new IllegalArgumentException("startDate must be before or equal to endDate");
        }

        return repository.search(
                        phoneFilters[0],
                        phoneFilters[1],
                        phoneFilters[2],
                        apiClientId,
                        apiClientName,
                        carrierFilter,
                        statusFilter,
                        startTs,
                        endTs,
                        pageable
                )
                .map(this::toResponse);
    }

    @GetMapping("/outbound-messages/{requestId}")
    public ResponseEntity<OutboundMessageResponse> get(@PathVariable String requestId) {
        return repository.findByRequestId(requestId)
                .map(m -> ResponseEntity.ok(toResponse(m)))
                .orElse(ResponseEntity.notFound().build());
    }

    private OutboundMessageResponse toResponse(OutboundMessage m) {
        return new OutboundMessageResponse(
                m.getRequestId(),
                m.getPhone(),
                m.getApiClientId(),
                m.getApiClientName(),
                m.getCarrier(),
                m.getMessage(),
                m.getSenderId(),
                m.getMessageType(),
                m.getStatus(),
                m.getAttemptCount(),
                m.getExpiresAt(),
                m.getNextAttemptAt(),
                m.getLastError(),
                m.getDate()
        );
    }

    private Long parseApiClientId(String apiClient) {
        if (apiClient == null || apiClient.isBlank()) {
            return null;
        }
        String value = apiClient.trim();
        if (!value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid apiClient. Use a valid numeric id or client name");
        }
    }

    private String parseApiClientName(String apiClient) {
        if (apiClient == null || apiClient.isBlank()) {
            return null;
        }
        String value = apiClient.trim();
        return value.chars().allMatch(Character::isDigit) ? null : value;
    }

    private Carrier parseCarrier(String carrier) {
        if (carrier == null || carrier.isBlank()) {
            return null;
        }
        try {
            return Carrier.valueOf(carrier.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid carrier. Use MTN or AIRTEL");
        }
    }

    private String parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        if (!"QUEUED".equals(normalized)
                && !"PROCESSING".equals(normalized)
                && !"RETRY".equals(normalized)
                && !"SENT".equals(normalized)
                && !"FAILED".equals(normalized)
                && !"EXPIRED".equals(normalized)) {
            throw new IllegalArgumentException("Invalid status. Use QUEUED, PROCESSING, RETRY, SENT, FAILED or EXPIRED");
        }
        return normalized;
    }

    private Instant parseInstant(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + " must be an ISO-8601 datetime");
        }
    }

    private String[] buildPhoneFilters(String phone) {
        if (phone == null || phone.isBlank()) {
            return new String[]{null, null, null};
        }

        String digits = phone.replaceAll("\\D", "");
        if (digits.isBlank()) {
            return new String[]{null, null, null};
        }

        String altOne = null;
        String altTwo = null;

        if (digits.startsWith("256") && digits.length() > 3) {
            altOne = "0" + digits.substring(3);
        } else if (digits.startsWith("0") && digits.length() > 1) {
            altOne = "256" + digits.substring(1);
        } else {
            altOne = "0" + digits;
            altTwo = "256" + digits;
        }

        return new String[]{digits, altOne, altTwo};
    }
}
