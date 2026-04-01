package com.sms.gateway.smpp;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.sms.gateway.config.SmppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SMPP session pool / connection manager for Cloudhopper.
 */
@Component("mtnSmppSessionManager")
@Primary
public class SmppSessionManager {
    private static final Logger log = LoggerFactory.getLogger(SmppSessionManager.class);
    // Bind retries must never hammer the provider in a tight loop after a network or credential failure.
    // We start with a short delay and then grow it with exponential backoff plus jitter.
    private static final long MIN_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = TimeUnit.MINUTES.toMillis(5);

    // Spring-bound provider configuration for one SMPP target (MTN or Airtel).
    private final SmppProperties props;
    // Clock is injected through the test constructor so retry and idle timing can be verified deterministically.
    private final Clock clock;
    // A dedicated scheduler keeps session health management separate from SMS dispatch worker threads.
    private final ScheduledExecutorService healthCheckExecutor;
    // Metrics are tagged with the SMPP target so operations can distinguish MTN from Airtel in Prometheus.
    private final String metricTarget;
    private final Counter bindAttemptsCounter;
    private final Counter bindSuccessCounter;
    private final Counter bindFailureCounter;
    private final Counter rebindCounter;
    private final Counter healthCheckCounter;
    private final Counter unexpectedCloseCounter;
    // Round-robin spreads outbound submits across the configured SMPP sessions for a provider.
    private final AtomicInteger rr = new AtomicInteger(0);
    private final List<SessionHolder> sessions = new ArrayList<>();

    @Autowired
    public SmppSessionManager(SmppProperties props, MeterRegistry meterRegistry) {
        // Runtime constructor used by Spring. The extra constructor below exists only so tests can
        // inject a fake clock and scheduler without changing production behavior.
        this(props, meterRegistry, Clock.systemUTC(), newHealthCheckExecutor());
    }

    SmppSessionManager(
            SmppProperties props,
            MeterRegistry meterRegistry,
            Clock clock,
            ScheduledExecutorService healthCheckExecutor
    ) {
        this.props = props;
        this.clock = clock;
        this.healthCheckExecutor = healthCheckExecutor;
        this.metricTarget = buildMetricTarget(props);
        this.bindAttemptsCounter = Counter.builder("sms.smpp.bind.attempts")
                .tag("target", metricTarget)
                .register(meterRegistry);
        this.bindSuccessCounter = Counter.builder("sms.smpp.bind.success")
                .tag("target", metricTarget)
                .register(meterRegistry);
        this.bindFailureCounter = Counter.builder("sms.smpp.bind.failure")
                .tag("target", metricTarget)
                .register(meterRegistry);
        this.rebindCounter = Counter.builder("sms.smpp.rebind.requests")
                .tag("target", metricTarget)
                .register(meterRegistry);
        this.healthCheckCounter = Counter.builder("sms.smpp.health.checks")
                .tag("target", metricTarget)
                .register(meterRegistry);
        this.unexpectedCloseCounter = Counter.builder("sms.smpp.channel.unexpected_close")
                .tag("target", metricTarget)
                .register(meterRegistry);
    }

    @PostConstruct
    public void start() {
        int count = Math.max(1, props.getSessions());
        for (int i = 0; i < count; i++) {
            SessionHolder holder = new SessionHolder("smpp-" + (i + 1));
            sessions.add(holder);
            // Bind once at startup so the application begins with warm SMPP sessions instead of
            // paying connect+bind cost on the first outbound message.
            holder.ensureBound();
        }
        startHealthChecks();
        log.info("SMPP manager started target={} sessions={}", metricTarget, sessions.size());
    }

    @PreDestroy
    public void stop() {
        healthCheckExecutor.shutdownNow();
        for (SessionHolder holder : sessions) {
            try {
                holder.close();
            } catch (Exception ignore) {
            }
        }
        sessions.clear();
        log.info("SMPP manager stopped target={}", metricTarget);
    }

    public SubmitSmResp send(SubmitSm sm, long timeoutMs) throws Exception {
        Objects.requireNonNull(sm, "SubmitSm must not be null");
        return selectSession().send(sm, timeoutMs);
    }

    public byte[] encodeUcs2(String text) {
        String safe = (text == null) ? "" : text;
        return CharsetUtil.encode(safe, CharsetUtil.CHARSET_UCS_2);
    }

    public byte[] buildConcatenationUdh8bit(byte total, byte seq, byte ref) {
        return new byte[]{0x05, 0x00, 0x03, ref, total, seq};
    }

    public SmppManagerHealthSnapshot healthSnapshot() {
        // Actuator health and operational diagnostics use this snapshot rather than reading internal
        // fields directly. That keeps the health endpoint consistent and safe to expose.
        List<SmppSessionHealthSnapshot> sessionSnapshots = new ArrayList<>();
        int boundSessions = 0;

        for (SessionHolder holder : sessions) {
            SmppSessionHealthSnapshot snapshot = holder.snapshot();
            sessionSnapshots.add(snapshot);
            if (snapshot.bound()) {
                boundSessions++;
            }
        }

        boolean started = !sessions.isEmpty();
        boolean up = started && boundSessions == sessions.size();
        return new SmppManagerHealthSnapshot(metricTarget, started, sessions.size(), boundSessions, up, sessionSnapshots);
    }

    void runHealthChecksNow() {
        runHealthChecks();
    }

    void markSessionForRebind(int sessionIndex) {
        if (sessionIndex < 0 || sessionIndex >= sessions.size()) {
            throw new IllegalArgumentException("Invalid session index: " + sessionIndex);
        }
        sessions.get(sessionIndex).markForRebind();
    }

    protected DefaultSmppClient createClient(ExecutorService clientExecutor) {
        return new DefaultSmppClient(clientExecutor, 1);
    }

    protected SmppSession bind(DefaultSmppClient client, SmppSessionConfiguration cfg, DefaultSmppSessionHandler handler) throws Exception {
        return client.bind(cfg, handler);
    }

    protected long applyJitter(long delayMs) {
        if (delayMs <= MIN_BACKOFF_MS) {
            return delayMs;
        }
        // Jitter avoids synchronized retry storms when multiple sessions or instances fail together.
        double factor = ThreadLocalRandom.current().nextDouble(0.8d, 1.21d);
        return Math.max(MIN_BACKOFF_MS, Math.round(delayMs * factor));
    }

    private static ScheduledExecutorService newHealthCheckExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("smpp-health-check");
            t.setDaemon(true);
            return t;
        });
    }

    private static String buildMetricTarget(SmppProperties props) {
        String systemId = (props.getSystemId() == null || props.getSystemId().isBlank()) ? "unknown" : props.getSystemId().trim();
        String host = (props.getHost() == null || props.getHost().isBlank()) ? "unknown" : props.getHost().trim();
        return systemId + "@" + host + ":" + props.getPort();
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private void startHealthChecks() {
        long intervalMs = Math.max(MIN_BACKOFF_MS, props.getHealthCheckIntervalMs());
        // The health task drives three behaviors:
        // 1) bind missing/unbound sessions,
        // 2) recover sessions whose channel was closed unexpectedly,
        // 3) perform the MTN-requested fresh rebind after an idle interval.
        healthCheckExecutor.scheduleWithFixedDelay(
                this::runHealthChecks,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        log.info("SMPP health checks scheduled target={} intervalMs={} forceRebindIntervalMs={}",
                metricTarget, intervalMs, props.getForceRebindIntervalMs());
    }

    private void runHealthChecks() {
        healthCheckCounter.increment();
        for (SessionHolder holder : sessions) {
            try {
                // Each holder decides whether it needs an immediate recovery bind, an idle-time rebind,
                // or no action at all. That decision stays local to the session state.
                holder.ensureHealthy();
            } catch (Exception e) {
                log.warn("SMPP health check failed target={} name={} reason={}", metricTarget, holder.name, e.getMessage());
            }
        }
    }

    private SessionHolder selectSession() {
        if (sessions.isEmpty()) {
            throw new IllegalStateException("SMPP manager not started (no sessions)");
        }
        int idx = Math.floorMod(rr.getAndIncrement(), sessions.size());
        return sessions.get(idx);
    }

    private SmppBindType parseBindType(String s) {
        String v = (s == null) ? "" : s.trim().toUpperCase();
        return switch (v) {
            case "TRANSMITTER" -> SmppBindType.TRANSMITTER;
            case "RECEIVER" -> SmppBindType.RECEIVER;
            default -> SmppBindType.TRANSCEIVER;
        };
    }

    private byte interfaceVersion(int ignored) {
        return SmppConstants.VERSION_3_4;
    }

    private static void tryInvokeSetter(Object target, String methodName, Class<?> argType, Object argValue) {
        try {
            Method m = target.getClass().getMethod(methodName, argType);
            m.invoke(target, argValue);
        } catch (Exception ignore) {
        }
    }

    private static PduResponse awaitWindowResponse(WindowFuture<?, ?, ?> wf, long timeoutMs) throws Exception {
        if (wf instanceof Future<?> f) {
            Object r = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return (PduResponse) r;
        }

        boolean completed = false;
        try {
            Method await = wf.getClass().getMethod("await", long.class);
            Object ok = await.invoke(wf, timeoutMs);
            completed = !(ok instanceof Boolean) || (Boolean) ok;
        } catch (NoSuchMethodException ignore) {
            try {
                Method await = wf.getClass().getMethod("await", long.class, TimeUnit.class);
                Object ok = await.invoke(wf, timeoutMs, TimeUnit.MILLISECONDS);
                completed = !(ok instanceof Boolean) || (Boolean) ok;
            } catch (NoSuchMethodException ignore2) {
                Method await = wf.getClass().getMethod("await");
                await.invoke(wf);
                completed = true;
            }
        }

        if (!completed) {
            throw new TimeoutException("Timed out waiting for SMPP response");
        }

        Object respObj = null;
        for (String getter : new String[]{"getResponse", "getPduResponse", "getResult"}) {
            try {
                Method m = wf.getClass().getMethod(getter);
                respObj = m.invoke(wf);
                if (respObj != null) {
                    break;
                }
            } catch (NoSuchMethodException ignore) {
            }
        }
        if (respObj instanceof PduResponse pr) {
            return pr;
        }

        Object causeObj = null;
        for (String getter : new String[]{"getCause", "getException", "getFailureCause"}) {
            try {
                Method m = wf.getClass().getMethod(getter);
                causeObj = m.invoke(wf);
                if (causeObj != null) {
                    break;
                }
            } catch (NoSuchMethodException ignore) {
            }
        }
        if (causeObj instanceof Throwable t) {
            throw new ExecutionException(t);
        }

        throw new IllegalStateException("No SMPP response available (and no cause exposed by WindowFuture)");
    }

    public record SmppManagerHealthSnapshot(
            String target,
            boolean started,
            int totalSessions,
            int boundSessions,
            boolean up,
            List<SmppSessionHealthSnapshot> sessions
    ) {
        public Map<String, Object> toHealthDetails() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("target", target);
            details.put("started", started);
            details.put("totalSessions", totalSessions);
            details.put("boundSessions", boundSessions);
            details.put("up", up);
            details.put("sessions", sessions.stream().map(SmppSessionHealthSnapshot::toMap).toList());
            return details;
        }
    }

    public record SmppSessionHealthSnapshot(
            String name,
            String state,
            boolean bound,
            Instant lastBoundAt,
            Instant lastActivityAt,
            Instant nextBindAttemptAt,
            int consecutiveBindFailures,
            int activeSendCount,
            String lastBindFailureReason
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("name", name);
            details.put("state", state);
            details.put("bound", bound);
            details.put("lastBoundAt", lastBoundAt);
            details.put("lastActivityAt", lastActivityAt);
            details.put("nextBindAttemptAt", nextBindAttemptAt);
            details.put("consecutiveBindFailures", consecutiveBindFailures);
            details.put("activeSendCount", activeSendCount);
            details.put("lastBindFailureReason", lastBindFailureReason);
            return details;
        }
    }

    private final class SessionHolder {
        private final String name;
        private final Object lock = new Object();
        // Cloudhopper uses its own executor for IO/callback work for this specific session holder.
        private final ExecutorService clientExecutor;

        private DefaultSmppClient client;
        private volatile SmppSession session;
        // lastBoundAt tracks when the current bind became active.
        private volatile Instant lastBoundAt;
        // lastActivityAt is updated on outbound sends and inbound PDUs so forced rebind is based on idleness,
        // not simply elapsed wall-clock time since startup.
        private volatile Instant lastActivityAt;
        // After a bind failure we wait until nextBindAttemptAt before trying again.
        private volatile Instant nextBindAttemptAt;
        private volatile String lastBindFailureReason;
        // This flag is raised when Cloudhopper reports an unexpected channel close.
        private volatile boolean rebindRequested;
        // Consecutive failures feed the exponential backoff calculation.
        private volatile int consecutiveBindFailures;
        // Active sends are tracked so an idle-time rebind does not interrupt live traffic.
        private final AtomicInteger activeSendCount = new AtomicInteger(0);

        private SessionHolder(String name) {
            this.name = name;
            this.clientExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setName(this.name + "-client");
                t.setDaemon(true);
                return t;
            });
        }

        void ensureBound() {
            synchronized (lock) {
                if (session != null && session.isBound()) {
                    return;
                }

                Instant current = now();
                if (nextBindAttemptAt != null && current.isBefore(nextBindAttemptAt)) {
                    // Backoff is enforced here so a bad network or bad credential set does not cause
                    // every health check or every send attempt to hammer the SMSC.
                    return;
                }

                try {
                    bindAttemptsCounter.increment();
                    if (client == null) {
                        client = createClient(clientExecutor);
                    }

                    SmppSessionConfiguration cfg = new SmppSessionConfiguration();
                    cfg.setName(name);
                    cfg.setType(parseBindType(props.getBindType()));
                    cfg.setHost(props.getHost());
                    cfg.setPort(props.getPort());
                    cfg.setSystemId(props.getSystemId());
                    cfg.setPassword(props.getPassword());
                    cfg.setSystemType(props.getSystemType() == null ? "" : props.getSystemType());
                    cfg.setInterfaceVersion(interfaceVersion(props.getInterfaceVersion()));
                    cfg.setConnectTimeout(props.getConnectTimeoutMs());
                    cfg.setBindTimeout(props.getBindTimeoutMs());
                    cfg.setRequestExpiryTimeout(props.getRequestExpiryTimeoutMs());
                    cfg.setWindowSize(props.getWindowSize());
                    cfg.setWindowWaitTimeout(props.getRequestExpiryTimeoutMs());

                    int enquireMs = props.getEnquireLinkIntervalMs();
                    tryInvokeSetter(cfg, "setEnquireLinkInterval", int.class, enquireMs);
                    tryInvokeSetter(cfg, "setEnquireLinkInterval", long.class, (long) enquireMs);
                    tryInvokeSetter(cfg, "setEnquireLinkIntervalMillis", int.class, enquireMs);
                    tryInvokeSetter(cfg, "setEnquireLinkIntervalMillis", long.class, (long) enquireMs);

                    // A successful bind resets all recovery state and marks the session as recently active.
                    this.session = bind(client, cfg, new SimpleSessionHandler(this));
                    this.lastBoundAt = current;
                    this.lastActivityAt = current;
                    this.nextBindAttemptAt = null;
                    this.lastBindFailureReason = null;
                    this.rebindRequested = false;
                    this.consecutiveBindFailures = 0;
                    bindSuccessCounter.increment();

                    log.info("SMPP bound target={} name={} bindType={} windowSize={}", metricTarget, name, cfg.getType(), cfg.getWindowSize());
                } catch (Exception e) {
                    bindFailureCounter.increment();
                    consecutiveBindFailures++;
                    lastBindFailureReason = e.getMessage();
                    // Backoff grows with each consecutive failure so the gateway backs away from the provider
                    // instead of retrying continuously when the remote side is down or credentials are invalid.
                    nextBindAttemptAt = current.plusMillis(computeBackoffDelayMs(consecutiveBindFailures));
                    log.warn("SMPP bind failed target={} name={} failures={} nextAttemptAt={} reason={}",
                            metricTarget, name, consecutiveBindFailures, nextBindAttemptAt, e.getMessage());
                }
            }
        }

        SubmitSmResp send(SubmitSm sm, long timeoutMs) throws Exception {
            SmppSession s = session;
            if (s == null || !s.isBound()) {
                // Sending is allowed to trigger recovery when the session is missing, but the bind path
                // still respects backoff so request traffic does not become a retry loop.
                ensureBound();
                s = session;
                if (s == null || !s.isBound()) {
                    throw new IllegalStateException(buildUnavailableMessage());
                }
            }

            activeSendCount.incrementAndGet();
            markActivity();
            try {
                // Any successful send or inbound response updates activity time, which prevents the
                // MTN-specific forced rebind from interrupting a healthy active link.
                WindowFuture<?, ?, ?> fut = s.sendRequestPdu(sm, timeoutMs, false);
                PduResponse resp = awaitWindowResponse(fut, timeoutMs);
                markActivity();
                if (resp instanceof SubmitSmResp ssr) {
                    return ssr;
                }
                throw new IllegalStateException("Unexpected SMPP response type: " + (resp == null ? null : resp.getClass()));
            } finally {
                activeSendCount.decrementAndGet();
            }
        }

        void close() {
            synchronized (lock) {
                destroySessionOnly();
                try {
                    if (client != null) {
                        client.destroy();
                    }
                } catch (Exception ignore) {
                }
                client = null;
                lastBoundAt = null;
                lastActivityAt = null;
                nextBindAttemptAt = null;
                lastBindFailureReason = null;
                rebindRequested = false;
                consecutiveBindFailures = 0;
                clientExecutor.shutdownNow();
            }
        }

        void ensureHealthy() {
            synchronized (lock) {
                RebindDecision decision = evaluateRebindNeed();
                if (!decision.required()) {
                    return;
                }

                if (decision.waitingForBackoff()) {
                    return;
                }

                if (activeSendCount.get() > 0 && decision.idleRebind()) {
                    // MTN asked for a fresh rebind after idle time. We intentionally skip that rebind while
                    // a message is actively sending so we do not tear down a healthy live session mid-flight.
                    return;
                }

                if (session != null) {
                    log.info("SMPP rebind requested target={} name={} reason={}", metricTarget, name, decision.reason());
                }

                rebindCounter.increment();
                destroySessionOnly();
                ensureBound();
            }
        }

        SmppSessionHealthSnapshot snapshot() {
            synchronized (lock) {
                return new SmppSessionHealthSnapshot(
                        name,
                        currentState(),
                        session != null && session.isBound(),
                        lastBoundAt,
                        lastActivityAt,
                        nextBindAttemptAt,
                        consecutiveBindFailures,
                        activeSendCount.get(),
                        lastBindFailureReason
                );
            }
        }

        void markForRebind() {
            synchronized (lock) {
                rebindRequested = true;
            }
            unexpectedCloseCounter.increment();
        }

        private void markActivity() {
            lastActivityAt = now();
        }

        private String buildUnavailableMessage() {
            Instant nextAttempt = nextBindAttemptAt;
            if (nextAttempt == null) {
                return "SMPP session not bound (" + name + ")";
            }
            long retryInMs = Math.max(0L, Duration.between(now(), nextAttempt).toMillis());
            return "SMPP session not bound (" + name + "), next bind attempt in " + retryInMs + "ms";
        }

        private String currentState() {
            if (session != null && session.isBound()) {
                if (rebindRequested) {
                    return "REBIND_REQUESTED";
                }
                if (isIdleRebindDue()) {
                    return "IDLE_REBIND_DUE";
                }
                return "BOUND";
            }
            if (nextBindAttemptAt != null && now().isBefore(nextBindAttemptAt)) {
                return "WAITING_TO_RETRY";
            }
            return "UNBOUND";
        }

        private RebindDecision evaluateRebindNeed() {
            Instant current = now();
            if (session == null || !session.isBound()) {
                boolean waitingForBackoff = nextBindAttemptAt != null && current.isBefore(nextBindAttemptAt);
                return new RebindDecision(true, waitingForBackoff, false, waitingForBackoff ? "backoff" : "session-unbound");
            }
            if (rebindRequested) {
                // Unexpected channel close is treated as a hard recovery signal and does not depend on idleness.
                return new RebindDecision(true, false, false, "channel-closed");
            }
            if (isIdleRebindDue()) {
                // This is the MTN-driven behavior: rebind only after the session has been idle long enough.
                return new RebindDecision(true, false, true, "idle-rebind-interval");
            }
            return new RebindDecision(false, false, false, "healthy");
        }

        private boolean isIdleRebindDue() {
            long forceRebindIntervalMs = props.getForceRebindIntervalMs();
            if (forceRebindIntervalMs <= 0 || lastActivityAt == null) {
                return false;
            }
            return Duration.between(lastActivityAt, now()).toMillis() >= forceRebindIntervalMs;
        }

        private long computeBackoffDelayMs(int failureCount) {
            long baseDelay = Math.max(MIN_BACKOFF_MS, props.getReconnectDelayMs());
            int exponent = Math.max(0, Math.min(failureCount - 1, 6));
            long delay = baseDelay;
            for (int i = 0; i < exponent; i++) {
                delay = Math.min(delay * 2L, MAX_BACKOFF_MS);
            }
            // Jittered exponential backoff reduces retry bursts while still allowing recovery fairly quickly
            // after the first few failures.
            return Math.min(MAX_BACKOFF_MS, applyJitter(delay));
        }

        private void destroySessionOnly() {
            try {
                if (session != null) {
                    session.destroy();
                }
            } catch (Exception ignore) {
            }
            session = null;
        }
    }

    private record RebindDecision(boolean required, boolean waitingForBackoff, boolean idleRebind, String reason) {
    }

    private static final class SimpleSessionHandler extends DefaultSmppSessionHandler {
        private final SessionHolder holder;
        private final String name;

        private SimpleSessionHandler(SessionHolder holder) {
            this.holder = holder;
            this.name = holder.name;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public PduResponse firePduRequestReceived(PduRequest request) {
            holder.markActivity();
            log.debug("SMPP inbound PDU session={} cmdId={} seq={}", name, request.getCommandId(), request.getSequenceNumber());
            return request.createResponse();
        }

        @Override
        public void fireChannelUnexpectedlyClosed() {
            // Cloudhopper calls this when the TCP/SMPP channel disappears underneath us.
            // We do not bind immediately here; instead we mark the session and let the health loop
            // recover it in a controlled, observable way.
            holder.markForRebind();
            log.warn("SMPP channel unexpectedly closed session={}", name);
        }
    }
}