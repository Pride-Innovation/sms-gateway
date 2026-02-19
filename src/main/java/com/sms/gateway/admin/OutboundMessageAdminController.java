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
            @RequestParam(required = false) String carrier,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), 500);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "date"));

        String phoneFilter = (phone == null || phone.isBlank()) ? null : phone.trim();
        Carrier carrierFilter = parseCarrier(carrier);
        Instant fromTs = (from == null || from.isBlank()) ? null : Instant.parse(from);
        Instant toTs = (to == null || to.isBlank()) ? null : Instant.parse(to);

        return repository.search(phoneFilter, carrierFilter, fromTs, toTs, pageable)
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
                m.getCarrier(),
                m.getMessage(),
                m.getSenderId(),
                m.getStatus(),
                m.getDate()
        );
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
}
