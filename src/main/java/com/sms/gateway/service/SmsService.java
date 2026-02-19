package com.sms.gateway.service;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.google.common.util.concurrent.RateLimiter;
import com.sms.gateway.carrier.Carrier;
import com.sms.gateway.carrier.CarrierRouter;
import com.sms.gateway.config.AddressingProperties;
import com.sms.gateway.config.AirtelAddressingProperties;
import com.sms.gateway.config.AirtelSmppProperties;
import com.sms.gateway.config.SmsGatewayProperties;
import com.sms.gateway.config.SmppProperties;
import com.sms.gateway.smpp.SmppSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.sms.gateway.message.OutboundMessage;
import com.sms.gateway.message.OutboundMessageRepository;

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

    private final SmppSessionManager mtnSmpp;
    private final SmppSessionManager airtelSmpp;

    private final SmppProperties mtnProps;
    private final AirtelSmppProperties airtelProps;

    private final AddressingProperties mtnAddrProps;
    private final AirtelAddressingProperties airtelAddrProps;

    private final SmsGatewayProperties gwProps;

    private final CarrierRouter carrierRouter;

    private final RateLimiter mtnLimiter;
    private final RateLimiter airtelLimiter;
    private final SmsStore store;
    private final SmsDispatcher dispatcher;
    private final OutboundMessageRepository outboundMessageRepository;

    public SmsService(
            @Qualifier("mtnSmppSessionManager") SmppSessionManager mtnSmpp,
            @Qualifier("airtelSmppSessionManager") ObjectProvider<SmppSessionManager> airtelSmppProvider,
            SmppProperties mtnProps,
            AirtelSmppProperties airtelProps,
            AddressingProperties mtnAddrProps,
            AirtelAddressingProperties airtelAddrProps,
            SmsGatewayProperties gwProps,
            CarrierRouter carrierRouter,
            OutboundMessageRepository outboundMessageRepository,
            SmsStore store
    ) {
        this.mtnSmpp = mtnSmpp;
        this.airtelSmpp = airtelSmppProvider.getIfAvailable();
        this.mtnProps = mtnProps;
        this.airtelProps = airtelProps;
        this.mtnAddrProps = mtnAddrProps;
        this.airtelAddrProps = airtelAddrProps;
        this.gwProps = gwProps;
        this.carrierRouter = carrierRouter;
        this.outboundMessageRepository = outboundMessageRepository;

        // TPS here is "segments per second" per application instance.
        this.mtnLimiter = RateLimiter.create(Math.max(1, mtnProps.getTps()));
        this.airtelLimiter = RateLimiter.create(Math.max(1, airtelProps.getTps()));

        // SmsStore is now injected (MySQL-backed for robustness)
        this.store = store;

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
        Carrier carrier = carrierRouter.resolveOrThrow(normalized);

        if (carrier == Carrier.AIRTEL && airtelSmpp == null) {
            throw new IllegalArgumentException(
                    "Destination resolves to AIRTEL but Airtel SMPP is not configured. " +
                            "Set sms.airtel.smpp.host/port/systemId/password (AIRTEL_SMPP_* env vars)."
            );
        }

        String trimmedText = (text == null) ? "" : text.trim();
        String effectiveSender = getString(senderId, trimmedText, carrier);

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

        // Persist initial record (date null while queued)
        OutboundMessage rec = new OutboundMessage();
        rec.setRequestId(requestId);
        rec.setPhone(normalized);
        rec.setCarrier(carrier);
        rec.setMessage(trimmedText);
        rec.setSenderId(effectiveSender);
        rec.setStatus("QUEUED");
        rec.setDate(null);
        outboundMessageRepository.save(rec);

        boolean accepted = dispatcher.tryEnqueue(new SmsJob(requestId, normalized, trimmedText, effectiveSender, carrier));
        if (!accepted) {
            store.updateState(requestId, "REJECTED", "Queue full");
            // Persist REJECTED in outbound_messages with timestamp for auditing
            outboundMessageRepository.findByRequestId(requestId).ifPresent(m -> {
                m.setStatus("REJECTED");
                m.setDate(Instant.now());
                outboundMessageRepository.save(m);
            });
            throw new TooManyRequestsException("SMS queue full, try later");
        }

        return requestId;
    }

    private String getString(String senderId, String trimmedText, Carrier carrier) {
        if (trimmedText.isBlank()) throw new IllegalArgumentException("text must not be blank");
        if (trimmedText.length() > gwProps.getMaxTextChars()) {
            throw new IllegalArgumentException("text too long (max " + gwProps.getMaxTextChars() + " chars)");
        }

        String providerDefaultSender = (carrier == Carrier.MTN)
                ? mtnProps.getDefaultSenderId()
                : airtelProps.getDefaultSenderId();

        String effectiveSender = (senderId == null || senderId.isBlank())
                ? providerDefaultSender
                : senderId.trim();

        if (effectiveSender == null || effectiveSender.isBlank()) {
            throw new IllegalArgumentException("senderId/defaultSenderId is blank");
        }
        return effectiveSender;
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

            Carrier carrier = job.carrier();
            SmppSessionManager smpp = (carrier == Carrier.MTN) ? mtnSmpp : requireAirtelSmpp();
            boolean registeredDelivery = (carrier == Carrier.MTN) ? mtnProps.isRegisteredDelivery() : airtelProps.isRegisteredDelivery();
            int requestExpiryTimeoutMs = (carrier == Carrier.MTN) ? mtnProps.getRequestExpiryTimeoutMs() : airtelProps.getRequestExpiryTimeoutMs();
            AddressingProperties addrProps = (carrier == Carrier.MTN) ? mtnAddrProps : toAddressingProperties(airtelAddrProps);
            RateLimiter limiter = (carrier == Carrier.MTN) ? mtnLimiter : airtelLimiter;

            Address source = buildSourceAddress(job.senderId(), addrProps);

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

                sm.setRegisteredDelivery(registeredDelivery
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

                SubmitSmResp resp = smpp.send(sm, requestExpiryTimeoutMs);

                int cs = resp.getCommandStatus();
                String messageId = resp.getMessageId();

                // PRODUCTION FIX: commandStatus must be 0 for success.
                if (cs != 0) {
                    String reason = "submit_sm rejected: commandStatus=0x" + Integer.toHexString(cs);
                    store.updateState(requestId, "FAILED", reason);

                    outboundMessageRepository.findByRequestId(requestId).ifPresent(m -> {
                        m.setStatus("FAILED");
                        m.setDate(Instant.now());
                        outboundMessageRepository.save(m);
                    });

                    log.warn("SMS rejected requestId={} part={}/{} status=0x{} messageId={}",
                            requestId, i + 1, total, Integer.toHexString(cs), messageId);

                    return; // stop sending remaining segments
                }

                store.addMessageId(requestId, messageId);
                log.info("SMS accepted requestId={} part={}/{} messageId={}", requestId, i + 1, total, messageId);
            }

            store.updateState(requestId, "SENT", null);

            outboundMessageRepository.findByRequestId(requestId).ifPresent(m -> {
                m.setStatus("SENT");
                m.setDate(Instant.now());
                outboundMessageRepository.save(m);
            });

        } catch (Exception e) {
            store.updateState(requestId, "FAILED", e.getMessage());
            log.warn("SMS failed requestId={} reason={}", requestId, e.getMessage());

            outboundMessageRepository.findByRequestId(requestId).ifPresent(m -> {
                m.setStatus("FAILED");
                m.setDate(Instant.now());
                outboundMessageRepository.save(m);
            });
        }
    }

    private SmppSessionManager requireAirtelSmpp() {
        if (airtelSmpp == null) {
            throw new IllegalStateException(
                    "Airtel SMPP is not configured. Set sms.airtel.smpp.host/port/systemId/password (AIRTEL_SMPP_* env vars)."
            );
        }
        return airtelSmpp;
    }

    private Address buildSourceAddress(String from, AddressingProperties addrProps) {
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

    private AddressingProperties toAddressingProperties(AirtelAddressingProperties ap) {
        AddressingProperties p = new AddressingProperties();
        p.setSourceTonAlphanumeric(ap.getSourceTonAlphanumeric());
        p.setSourceNpiAlphanumeric(ap.getSourceNpiAlphanumeric());
        p.setSourceTonInternational(ap.getSourceTonInternational());
        p.setSourceNpiE164(ap.getSourceNpiE164());
        p.setDestTonInternational(ap.getDestTonInternational());
        p.setDestNpiE164(ap.getDestNpiE164());
        return p;
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