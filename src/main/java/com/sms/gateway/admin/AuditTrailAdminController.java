package com.sms.gateway.admin;

import com.sms.gateway.admin.dto.AuditTrailResponse;
import com.sms.gateway.audit.AuditTrailQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/admin")
public class AuditTrailAdminController {

    private final AuditTrailQueryService auditTrailQueryService;

    public AuditTrailAdminController(AuditTrailQueryService auditTrailQueryService) {
        this.auditTrailQueryService = auditTrailQueryService;
    }

    @GetMapping("/audit-trails")
    public Page<AuditTrailResponse> list(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        Instant fromTs = parseInstant(from, "from");
        Instant toTs = parseInstant(to, "to");

        if ((fromTs == null) != (toTs == null)) {
            throw new IllegalArgumentException("Both from and to must be provided together");
        }
        if (fromTs != null && fromTs.isAfter(toTs)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }

        int safeSize = Math.min(Math.max(size, 1), 500);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize);

        return auditTrailQueryService.search(
                entityType,
                entityId,
                actorType,
                actorId,
                operation,
                requestId,
                fromTs,
                toTs,
                pageable
        );
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
}
