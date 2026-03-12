package com.sms.gateway.api;


import com.sms.gateway.service.SmsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sms")
@Validated
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;

    @PostMapping
    public ResponseEntity<SendSmsResponse> send(@RequestBody @Valid SendSmsRequest req) {
        String requestId = smsService.enqueue(
                req.getTo(),
                req.getText(),
                req.getSenderId(),
                req.getIdempotencyKey(),
                req.getMessageType(),
                req.getTtlSeconds()
        );
        return ResponseEntity.accepted().body(new SendSmsResponse(requestId, "QUEUED"));
    }

    // Request status is persisted in MySQL-backed SmsStore.
    @GetMapping("/{requestId}")
    public ResponseEntity<?> status(@PathVariable String requestId) {
        return ResponseEntity.ok(smsService.getStatus(requestId));
    }
}