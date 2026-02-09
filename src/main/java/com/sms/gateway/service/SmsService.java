package com.sms.gateway.service;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.google.common.util.concurrent.RateLimiter;
import com.sms.gateway.config.AddressingProperties;
import com.sms.gateway.config.SmsGatewayProperties;
import com.sms.gateway.config.SmppProperties;
import com.sms.gateway.smpp.SmppSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Core application service:
 * - Validates and normalizes requests
 * - Applies idempotency
 * - Enqueues work
 * - Worker threads perform SMPP submits and update the store
 * <p>
 * Production notes:
 * - For multi-instance scalability: SmsStore must be shared (Redis/DB), dispatcher should be durable (broker).
 * - RateLimiter is per-instance; if you run multiple pods, aggregate TPS can exceed your contract.
 */
@Service
public class SmsService {
    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private final SmppSessionManager smpp;
    private final SmppProperties props;
    private final AddressingProperties addrProps;
    private final SmsGatewayProperties gwProps;

    private final RateLimiter limiter;
    private final SmsStore store;
    private final SmsDispatcher dispatcher;

    public SmsService(
            SmppSessionManager smpp,
            SmppProperties props,
            AddressingProperties addrProps,
            SmsGatewayProperties gwProps
    ) {
        this.smpp = smpp;
        this.props = props;
        this.addrProps = addrProps;
        this.gwProps = gwProps;

        // TPS here is "segments per second" per application instance.
        this.limiter = RateLimiter.create(Math.max(1, props.getTps()));

        // In-memory store is fine for dev; for production replace with Redis/DB implementation.
        this.store = new InMemorySmsStore();

        // Bounded async queue; for production durability replace with broker.
        this.dispatcher = new SmsDispatcher(
                gwProps.getQueueCapacity(),
                gwProps.getWorkerThreads(),
                "sms-worker",
                this::sendInternal
        );
    }

    @PreDestroy
    public void shutdown() {
        // Ensure worker pool stops cleanly on app shutdown.
        dispatcher.close();
    }

    public String enqueue(
            String toMsisdn,
            String text,
            String senderId,
            String idempotencyKey
    ) {
        String normalized = normalizeMsisdn(toMsisdn);

        String trimmedText = (text == null) ? "" : text.trim();
        if (trimmedText.isBlank()) throw new IllegalArgumentException("text must not be blank");
        if (trimmedText.length() > gwProps.getMaxTextChars()) {
            throw new IllegalArgumentException("text too long (max " + gwProps.getMaxTextChars() + " chars)");
        }

        String effectiveSender = (senderId == null || senderId.isBlank())
                ? props.getDefaultSenderId()
                : senderId.trim();

        if (effectiveSender == null || effectiveSender.isBlank()) {
            throw new IllegalArgumentException("senderId/defaultSenderId is blank");
        }

        int maxAlphaLen = Math.max(1, gwProps.getMaxAlphanumericSenderIdLength());

        if (isAlphanumeric(effectiveSender) && effectiveSender.length() > maxAlphaLen) {
            throw new IllegalArgumentException(
                    "senderId too long for alphanumeric sender (max " + maxAlphaLen + "): " + effectiveSender
            );
        }

        String stableKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? null
                : sha256Hex(idempotencyKey.trim());

        if (stableKey != null) {
            String existing = store.findByIdempotencyKey(stableKey);
            if (existing != null) return existing;
        }

        String requestId = UUID.randomUUID().toString();
        SmsStatus status = new SmsStatus(requestId, normalized, effectiveSender, Instant.now(), "QUEUED", null);
        store.put(status, stableKey);

        log.info("Normalized MSISDN :: {}", normalized);
        log.info("Trimmed Text Message :: {}", trimmedText);
        log.info("Request ID :: {}", requestId);
        log.info("Effective Sender :: {}", effectiveSender);

        boolean accepted = dispatcher.tryEnqueue(new SmsJob(requestId, normalized, trimmedText, effectiveSender));
        if (!accepted) {
            store.updateState(requestId, "REJECTED", "Queue full");
            throw new TooManyRequestsException("SMS queue full, try later");
        }

        return requestId;
    }

    public SmsStatus getStatus(String requestId) {
        SmsStatus s = store.get(requestId);
        if (s == null) throw new IllegalArgumentException("Unknown requestId");
        return s;
    }

    /**
     * Worker thread body.
     * <p>
     * Production rule:
     * - Only mark SENT if ALL segments were accepted by SMSC (commandStatus == 0 for each part).
     */
    private void sendInternal(SmsJob job) {
        String requestId = job.requestId();

        try {
            store.updateState(requestId, "SENDING", null);

            Address source = buildSourceAddress(job.senderId());

            Address dest = new Address(
                    (byte) addrProps.getDestTonInternational(),
                    (byte) addrProps.getDestNpiE164(),
                    job.toMsisdn()
            );

            byte[] body = smpp.encodeUcs2(job.text());
            List<byte[]> segments = Ucs2Segmentation.split(body);

            // Rate-limit by number of *submits* (segments), not by API requests.
            limiter.acquire(Math.max(1, segments.size()));

            byte ref = (byte) (System.nanoTime() & 0xFF);
            int total = segments.size();

            for (int i = 0; i < total; i++) {
                SubmitSm sm = new SubmitSm();
                sm.setSourceAddress(source);
                sm.setDestAddress(dest);
                sm.setDataCoding(SmppConstants.DATA_CODING_UCS2);

                sm.setRegisteredDelivery(props.isRegisteredDelivery()
                        ? SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED
                        : SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_NOT_REQUESTED
                );

                byte[] payload = segments.get(i);

                if (total > 1) {
                    byte[] udh = smpp.buildConcatenationUdh8bit((byte) total, (byte) (i + 1), ref);
                    byte[] combined = new byte[udh.length + payload.length];
                    System.arraycopy(udh, 0, combined, 0, udh.length);
                    System.arraycopy(payload, 0, combined, udh.length, payload.length);

                    // UDHI flag tells the SMSC the short_message begins with a UDH.
                    sm.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
                    sm.setShortMessage(combined);
                } else {
                    sm.setShortMessage(payload);
                }

                SubmitSmResp resp = smpp.send(sm, props.getRequestExpiryTimeoutMs());

                int cs = resp.getCommandStatus();
                String messageId = resp.getMessageId();

                // PRODUCTION FIX: commandStatus must be 0 for success.
                if (cs != 0) {
                    String reason = "submit_sm rejected: commandStatus=0x" + Integer.toHexString(cs);
                    store.updateState(requestId, "FAILED", reason);

                    log.warn("SMS rejected requestId={} part={}/{} status=0x{} messageId={}",
                            requestId, i + 1, total, Integer.toHexString(cs), messageId);

                    return; // stop sending remaining segments
                }

                store.addMessageId(requestId, messageId);
                log.info("SMS accepted requestId={} part={}/{} messageId={}", requestId, i + 1, total, messageId);
            }

            store.updateState(requestId, "SENT", null);

        } catch (Exception e) {
            store.updateState(requestId, "FAILED", e.getMessage());
            log.warn("SMS failed requestId={} reason={}", requestId, e.getMessage());
        }
    }

    private Address buildSourceAddress(String from) {
        if (isAlphanumeric(from)) {
            return new Address(
                    (byte) addrProps.getSourceTonAlphanumeric(),
                    (byte) addrProps.getSourceNpiAlphanumeric(),
                    from
            );
        }

        // Numeric sender: enforce E.164/UG normalization.
        return new Address(
                (byte) addrProps.getSourceTonInternational(),
                (byte) addrProps.getSourceNpiE164(),
                normalizeMsisdn(from)
        );
    }

    private boolean isAlphanumeric(String s) {
        if (s == null || s.isBlank()) return false;
        for (char c : s.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) return false;
        }
        return true;
    }

    /**
     * Uganda normalization rule example:
     * - Accept "0704xxxxxx" => convert to "256704xxxxxx"
     * - Otherwise require "256" + 9 digits = 12 digits total
     */
    private String normalizeMsisdn(String msisdn) {
        String digits = (msisdn == null) ? "" : msisdn.replaceAll("[^0-9]", "");
        if (digits.startsWith("0") && digits.length() == 10) {
            digits = "256" + digits.substring(1);
        }
        if (!digits.startsWith("256") || digits.length() != 12) {
            throw new IllegalArgumentException("Invalid MSISDN (expected UG format): " + msisdn);
        }
        return digits;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash idempotencyKey", e);
        }
    }
}