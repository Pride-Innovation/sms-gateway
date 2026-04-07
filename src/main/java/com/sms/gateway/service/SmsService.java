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
import com.sms.gateway.message.MessageType;
import com.sms.gateway.smpp.SmppSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sms.gateway.message.OutboundMessage;
import com.sms.gateway.message.OutboundMessageRepository;
import com.sms.gateway.security.ApiClientAuthenticationToken;
import com.sms.gateway.security.ApiClientPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Core application service:
 * - Validates and normalizes requests
 * - Applies idempotency
 * - Persists work durably in MySQL
 * - Carrier-specific workers drain the queue and update the store
 * <p>
 * Production notes:
 * - For multi-instance scalability: SmsStore and queue records must be shared (DB/broker).
 * - RateLimiter is per-instance; if you run multiple pods, aggregate TPS can exceed your contract.
 */
@Service
public class SmsService {
    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_RETRY = "RETRY";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_EXPIRED = "EXPIRED";

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
    private final OutboundMessageRepository outboundMessageRepository;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService mtnWorker = Executors.newSingleThreadExecutor(namedThreadFactory("sms-queue-mtn"));
    private final ExecutorService airtelWorker = Executors.newSingleThreadExecutor(namedThreadFactory("sms-queue-airtel"));
    private final ScheduledExecutorService maintenanceWorker = Executors.newSingleThreadScheduledExecutor(namedThreadFactory("sms-queue-maint"));
    private final AtomicBoolean running = new AtomicBoolean(true);

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
            SmsStore store,
            PlatformTransactionManager transactionManager
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

        this.store = store;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    public void startWorkers() {
        log.info("Starting durable SMS queue workers mtnTps={} airtelTps={} otpDefaultTtlSeconds={} maxRetryAttempts={}",
                mtnProps.getTps(), airtelProps.getTps(), gwProps.getOtpDefaultTtlSeconds(), gwProps.getMaxRetryAttempts());

        mtnWorker.submit(() -> runCarrierWorker(Carrier.MTN, "sms-queue-mtn"));
        airtelWorker.submit(() -> runCarrierWorker(Carrier.AIRTEL, "sms-queue-airtel"));
        maintenanceWorker.scheduleWithFixedDelay(
                this::recoverStaleMessages,
                gwProps.getRecoveryIntervalSeconds(),
                gwProps.getRecoveryIntervalSeconds(),
                TimeUnit.SECONDS
        );
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        mtnWorker.shutdownNow();
        airtelWorker.shutdownNow();
        maintenanceWorker.shutdownNow();
    }

    @Transactional
    public String enqueue(
            String toMsisdn,
            String text,
            String senderId,
            String idempotencyKey,
            MessageType messageType,
            Integer ttlSeconds
    ) {
        String normalized = normalizeMsisdn(toMsisdn);
        Carrier carrier = carrierRouter.resolveOrThrow(normalized);
        MessageType effectiveType = (messageType == null) ? MessageType.NOTIFICATION : messageType;

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

        Instant now = Instant.now();
        Instant expiresAt = resolveExpiry(effectiveType, ttlSeconds, now);
        String requestId = UUID.randomUUID().toString();
        SmsStatus status = new SmsStatus(requestId, normalized, effectiveSender, now, STATUS_QUEUED, null);
        store.put(status, stableKey);

        OutboundMessage rec = new OutboundMessage();
        rec.setRequestId(requestId);
        rec.setPhone(normalized);
        rec.setCarrier(carrier);
        rec.setMessageType(effectiveType);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ApiClientAuthenticationToken) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof ApiClientPrincipal apiClientPrincipal) {
                rec.setApiClientId(apiClientPrincipal.id());
                rec.setApiClientName(apiClientPrincipal.username());
            }
        }
        rec.setMessage(trimmedText);
        rec.setSenderId(effectiveSender);
        rec.setStatus(STATUS_QUEUED);
        rec.setPriority(resolvePriority(effectiveType));
        rec.setAttemptCount(0);
        rec.setSegmentCount(null);
        rec.setExpiresAt(expiresAt);
        rec.setNextAttemptAt(now);
        rec.setLockedAt(null);
        rec.setLockedBy(null);
        rec.setLastError(null);
        rec.setDate(null);
        outboundMessageRepository.save(rec);

        log.info("Queued SMS requestId={} carrier={} type={} priority={} expiresAt={} requester={}",
                requestId, carrier, effectiveType, rec.getPriority(), expiresAt, rec.getApiClientName());

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

    private void runCarrierWorker(Carrier carrier, String workerName) {
        log.info("Starting carrier worker worker={} carrier={}", workerName, carrier);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<OutboundMessage> claimed = claimNextMessage(carrier, workerName);
                if (claimed.isEmpty()) {
                    sleepQuietly(gwProps.getWorkerIdleSleepMs());
                    continue;
                }
                processClaimedMessage(claimed.get());
            } catch (Throwable t) {
                log.error("Carrier worker crashed worker={} carrier={} reason={}", workerName, carrier, t.getMessage(), t);
                sleepQuietly(gwProps.getWorkerIdleSleepMs());
            }
        }
        log.info("Stopping carrier worker worker={} carrier={}", workerName, carrier);
    }

    private Optional<OutboundMessage> claimNextMessage(Carrier carrier, String workerName) {
        return Optional.ofNullable(transactionTemplate.execute(status -> {
            Optional<OutboundMessage> candidate = outboundMessageRepository.findNextForDispatch(carrier.name());
            if (candidate.isEmpty()) {
                return null;
            }

            OutboundMessage message = candidate.get();
            Instant now = Instant.now();
            message.setStatus(STATUS_PROCESSING);
            message.setLockedAt(now);
            message.setLockedBy(workerName);
            message.setNextAttemptAt(null);
            message.setLastError(null);
            outboundMessageRepository.save(message);
            store.updateState(message.getRequestId(), "SENDING", null);

            log.info("Claimed queued SMS requestId={} carrier={} type={} attempt={} worker={}",
                    message.getRequestId(), message.getCarrier(), message.getMessageType(), message.getAttemptCount(), workerName);
            return message;
        }));
    }

    private void processClaimedMessage(OutboundMessage message) {
        Instant now = Instant.now();
        if (isExpired(message, now)) {
            log.info("Skipping expired OTP requestId={} carrier={} expiresAt={}",
                    message.getRequestId(), message.getCarrier(), message.getExpiresAt());
            markExpired(message, "OTP expired before dispatch", 0);
            return;
        }

        try {
            Carrier carrier = message.getCarrier();
            SmppSessionManager smpp = resolveSmpp(carrier);
            SmppProperties selectedProps = selectSmppProperties(carrier);
            AddressingProperties addrProps = selectAddressingProperties(carrier);
            boolean registeredDelivery = selectedProps.isRegisteredDelivery();
            RateLimiter limiter = selectLimiter(carrier);

            log.info("Dispatching SMS requestId={} carrier={} type={} host={} port={} bindType={} windowSize={} senderId={}",
                    message.getRequestId(), carrier, message.getMessageType(), selectedProps.getHost(), selectedProps.getPort(),
                    selectedProps.getBindType(), selectedProps.getWindowSize(), message.getSenderId());

            Address source = buildSourceAddress(message.getSenderId(), addrProps);
            Address dest = new Address(
                    (byte) addrProps.getDestTonInternational(),
                    (byte) addrProps.getDestNpiE164(),
                    message.getPhone()
            );

            byte[] body = smpp.encodeUcs2(message.getMessage());
            List<byte[]> segments = Ucs2Segmentation.split(body);
            int totalSegments = Math.max(1, segments.size());

            if (isExpired(message, Instant.now())) {
                markExpired(message, "OTP expired before rate-limit permit", totalSegments);
                return;
            }

            limiter.acquire(totalSegments);

            if (isExpired(message, Instant.now())) {
                markExpired(message, "OTP expired while waiting for dispatch slot", totalSegments);
                return;
            }

            byte ref = (byte) (System.nanoTime() & 0xFF);
            for (int i = 0; i < totalSegments; i++) {
                SubmitSm sm = new SubmitSm();
                sm.setSourceAddress(source);
                sm.setDestAddress(dest);
                sm.setDataCoding(SmppConstants.DATA_CODING_UCS2);
                sm.setRegisteredDelivery(registeredDelivery
                        ? SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED
                        : SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_NOT_REQUESTED
                );

                byte[] payload = segments.get(i);
                if (totalSegments > 1) {
                    byte[] udh = smpp.buildConcatenationUdh8bit((byte) totalSegments, (byte) (i + 1), ref);
                    byte[] combined = new byte[udh.length + payload.length];
                    System.arraycopy(udh, 0, combined, 0, udh.length);
                    System.arraycopy(payload, 0, combined, udh.length, payload.length);
                    sm.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
                    sm.setShortMessage(combined);
                } else {
                    sm.setShortMessage(payload);
                }

                SubmitSmResp resp = smpp.send(sm, selectedProps.getRequestExpiryTimeoutMs());
                int commandStatus = resp.getCommandStatus();
                String messageId = resp.getMessageId();

                if (commandStatus != 0) {
                    String reason = "submit_sm rejected: commandStatus=0x" + Integer.toHexString(commandStatus);
                    log.warn("SMS rejected requestId={} carrier={} part={}/{} status=0x{} messageId={}",
                            message.getRequestId(), carrier, i + 1, totalSegments, Integer.toHexString(commandStatus), messageId);
                    markFailed(message, reason, totalSegments);
                    return;
                }

                store.addMessageId(message.getRequestId(), messageId);
                log.info("SMS accepted requestId={} carrier={} part={}/{} messageId={}",
                        message.getRequestId(), carrier, i + 1, totalSegments, messageId);
            }

            markSent(message, totalSegments);
        } catch (Exception e) {
            if (isExpired(message, Instant.now())) {
                markExpired(message, "OTP expired during retry handling", message.getSegmentCount() == null ? 0 : message.getSegmentCount());
                return;
            }

            if (canRetry(message)) {
                scheduleRetry(message, e.getMessage(), message.getSegmentCount());
                return;
            }

            markFailed(message, e.getMessage(), message.getSegmentCount() == null ? 0 : message.getSegmentCount());
        }
    }

    private void recoverStaleMessages() {
        if (!running.get()) {
            return;
        }

        try {
            Instant cutoff = Instant.now().minusSeconds(gwProps.getStaleProcessingTimeoutSeconds());
            List<OutboundMessage> stale = transactionTemplate.execute(status ->
                    outboundMessageRepository.findByStatusAndLockedAtBefore(STATUS_PROCESSING, cutoff));
            if (stale == null || stale.isEmpty()) {
                return;
            }

            for (OutboundMessage message : stale) {
                scheduleRetry(message, "Recovered stale processing lock", message.getSegmentCount());
            }
            log.warn("Recovered stale queued messages count={}", stale.size());
        } catch (Exception e) {
            log.warn("Unable to recover stale messages reason={}", e.getMessage());
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

    private SmppSessionManager resolveSmpp(Carrier carrier) {
        return (carrier == Carrier.MTN) ? mtnSmpp : requireAirtelSmpp();
    }

    private SmppProperties selectSmppProperties(Carrier carrier) {
        return (carrier == Carrier.MTN) ? mtnProps : toSmppProperties(airtelProps);
    }

    private AddressingProperties selectAddressingProperties(Carrier carrier) {
        return (carrier == Carrier.MTN) ? mtnAddrProps : toAddressingProperties(airtelAddrProps);
    }

    private RateLimiter selectLimiter(Carrier carrier) {
        return (carrier == Carrier.MTN) ? mtnLimiter : airtelLimiter;
    }

    private SmppProperties toSmppProperties(AirtelSmppProperties props) {
        SmppProperties mapped = new SmppProperties();
        mapped.setHost(props.getHost());
        mapped.setPort(props.getPort());
        mapped.setSystemId(props.getSystemId());
        mapped.setPassword(props.getPassword());
        mapped.setSystemType(props.getSystemType());
        mapped.setBindType(props.getBindType());
        mapped.setInterfaceVersion(props.getInterfaceVersion());
        mapped.setConnectTimeoutMs(props.getConnectTimeoutMs());
        mapped.setRequestExpiryTimeoutMs(props.getRequestExpiryTimeoutMs());
        mapped.setBindTimeoutMs(props.getBindTimeoutMs());
        mapped.setWindowSize(props.getWindowSize());
        mapped.setEnquireLinkIntervalMs(props.getEnquireLinkIntervalMs());
        mapped.setReconnectDelayMs(props.getReconnectDelayMs());
        mapped.setRegisteredDelivery(props.isRegisteredDelivery());
        mapped.setDefaultSenderId(props.getDefaultSenderId());
        mapped.setTps(props.getTps());
        mapped.setSessions(props.getSessions());
        return mapped;
    }

    private Instant resolveExpiry(MessageType messageType, Integer ttlSeconds, Instant now) {
        if (messageType != MessageType.OTP) {
            return null;
        }
        int effectiveTtl = (ttlSeconds == null) ? gwProps.getOtpDefaultTtlSeconds() : ttlSeconds;
        return now.plusSeconds(Math.max(1, effectiveTtl));
    }

    private int resolvePriority(MessageType messageType) {
        return (messageType == MessageType.OTP) ? gwProps.getOtpPriority() : gwProps.getNotificationPriority();
    }

    private boolean isExpired(OutboundMessage message, Instant now) {
        return message.getMessageType() == MessageType.OTP
                && message.getExpiresAt() != null
                && !message.getExpiresAt().isAfter(now);
    }

    private boolean canRetry(OutboundMessage message) {
        int currentAttempt = (message.getAttemptCount() == null) ? 0 : message.getAttemptCount();
        return currentAttempt < gwProps.getMaxRetryAttempts();
    }

    private void markSent(OutboundMessage message, int segmentCount) {
        Instant now = Instant.now();
        transactionTemplate.executeWithoutResult(status -> {
            outboundMessageRepository.findById(message.getId()).ifPresent(entity -> {
                entity.setStatus(STATUS_SENT);
                entity.setSegmentCount(segmentCount);
                entity.setDate(now);
                entity.setLockedAt(null);
                entity.setLockedBy(null);
                entity.setLastError(null);
                outboundMessageRepository.save(entity);
            });
            store.updateState(message.getRequestId(), STATUS_SENT, null);
        });
    }

    private void markExpired(OutboundMessage message, String reason, int segmentCount) {
        Instant now = Instant.now();
        transactionTemplate.executeWithoutResult(status -> {
            outboundMessageRepository.findById(message.getId()).ifPresent(entity -> {
                entity.setStatus(STATUS_EXPIRED);
                entity.setSegmentCount(segmentCount == 0 ? entity.getSegmentCount() : segmentCount);
                entity.setDate(now);
                entity.setLockedAt(null);
                entity.setLockedBy(null);
                entity.setLastError(reason);
                outboundMessageRepository.save(entity);
            });
            store.updateState(message.getRequestId(), STATUS_EXPIRED, reason);
        });
    }

    private void markFailed(OutboundMessage message, String reason, Integer segmentCount) {
        Instant now = Instant.now();
        transactionTemplate.executeWithoutResult(status -> {
            outboundMessageRepository.findById(message.getId()).ifPresent(entity -> {
                entity.setStatus(STATUS_FAILED);
                entity.setSegmentCount(segmentCount == null || segmentCount == 0 ? entity.getSegmentCount() : segmentCount);
                entity.setDate(now);
                entity.setLockedAt(null);
                entity.setLockedBy(null);
                entity.setLastError(reason);
                outboundMessageRepository.save(entity);
            });
            store.updateState(message.getRequestId(), STATUS_FAILED, reason);
        });
        log.warn("SMS permanently failed requestId={} carrier={} reason={}", message.getRequestId(), message.getCarrier(), reason);
    }

    private void scheduleRetry(OutboundMessage message, String reason, Integer segmentCount) {
        Instant now = Instant.now();
        int nextAttempt = ((message.getAttemptCount() == null) ? 0 : message.getAttemptCount()) + 1;
        long delaySeconds = computeRetryDelaySeconds(nextAttempt);
        Instant nextAttemptAt = now.plusSeconds(delaySeconds);

        transactionTemplate.executeWithoutResult(status -> {
            outboundMessageRepository.findById(message.getId()).ifPresent(entity -> {
                entity.setStatus(STATUS_RETRY);
                entity.setAttemptCount(nextAttempt);
                entity.setSegmentCount(segmentCount == null || segmentCount == 0 ? entity.getSegmentCount() : segmentCount);
                entity.setNextAttemptAt(nextAttemptAt);
                entity.setLockedAt(null);
                entity.setLockedBy(null);
                entity.setLastError(reason);
                outboundMessageRepository.save(entity);
            });
            store.updateState(message.getRequestId(), STATUS_RETRY, reason);
        });

        log.warn("Scheduled SMS retry requestId={} carrier={} attempt={} nextAttemptAt={} reason={}",
                message.getRequestId(), message.getCarrier(), nextAttempt, nextAttemptAt, reason);
    }

    private long computeRetryDelaySeconds(int attempt) {
        long baseDelay = Math.max(1L, gwProps.getRetryBaseDelaySeconds());
        long maxDelay = Math.max(baseDelay, gwProps.getRetryMaxDelaySeconds());
        long exponential = baseDelay * (1L << Math.max(0, attempt - 1));
        return Math.min(exponential, maxDelay);
    }

    private void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(Math.max(1L, delayMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
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