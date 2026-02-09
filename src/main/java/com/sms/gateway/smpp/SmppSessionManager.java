package com.sms.gateway.smpp;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.sms.gateway.config.SmppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SMPP session pool / connection manager for Cloudhopper.
 * <p>
 * What this class does:
 * - Creates N independent SMPP sessions to the SMSC (Airtel) based on SmppProperties.
 * - Keeps sessions alive across requests (production behavior).
 * - Selects a session using round-robin.
 * - Sends submit_sm and awaits submit_sm_resp (with compatibility across Cloudhopper variants).
 * - Provides a couple of SMPP payload helpers (UCS-2 encoding, concatenation UDH).
 * <p>
 * Why this exists:
 * - SMPP connections are stateful and expensive to create (TCP connect + bind).
 * - Production gateways should re-use sessions and control in-flight window size.
 * - Cloudhopper versions differ: some return WindowFuture that is also a Future, others don’t.
 * <p>
 * Key operational notes:
 * - A SubmitSmResp with commandStatus != 0 means the SMSC REJECTED the message.
 * You must treat that as failure (e.g., 0x0000000A “Source address invalid”).
 * - This class does not implement retries/backoff/persistence; those belong in your service layer.
 */
@Component
public class SmppSessionManager {
    private static final Logger log = LoggerFactory.getLogger(SmppSessionManager.class);

    /**
     * Spring-bound configuration: host/port/systemId/password, bindType, timeouts, window size, etc.
     */
    private final SmppProperties props;

    /**
     * Round-robin counter used by selectSession().
     * Atomic to avoid contention and be safe under concurrent sends.
     */
    private final AtomicInteger rr = new AtomicInteger(0);

    /**
     * Session holders built at startup.
     * After start(), this is read-only (no additions/removals) until stop().
     */
    private final List<SessionHolder> sessions = new ArrayList<>();

    public SmppSessionManager(SmppProperties props) {
        this.props = props;
    }

    /**
     * Spring lifecycle: create and bind sessions when the application starts.
     * <p>
     * - If bind fails, we log and keep going (service can still start; sending will try to rebind).
     * - sessions count is clamped to >= 1 to avoid modulo by zero in selectSession().
     */
    @PostConstruct
    public void start() {
        int count = Math.max(1, props.getSessions());
        for (int i = 0; i < count; i++) {
            SessionHolder holder = new SessionHolder("smpp-" + (i + 1));
            sessions.add(holder);
            holder.ensureBound();
        }
        log.info("SMPP manager started sessions={}", sessions.size());
    }

    /**
     * Spring lifecycle: shutdown hook.
     * <p>
     * This is the ONLY place we destroy sessions/clients/executors in production.
     * Sessions should NOT be torn down per request.
     */
    @PreDestroy
    public void stop() {
        for (SessionHolder s : sessions) {
            try {
                s.close();
            } catch (Exception ignore) {
                // best-effort shutdown
            }
        }
        sessions.clear();
        log.info("SMPP manager stopped");
    }

    /**
     * Sends a SubmitSm through a selected session and waits for SubmitSmResp.
     * <p>
     * Production behavior:
     * - No try-with-resources around the holder (we do NOT close it per send).
     * - Sessions are persistent and reused for throughput and stability.
     *
     * @param sm        the SubmitSm to send (must be populated by caller: src/dst/body/UDH/data_coding/etc.)
     * @param timeoutMs max time to wait for submit_sm_resp
     * @return SubmitSmResp (caller must check getCommandStatus() == 0 for success)
     */
    public SubmitSmResp send(SubmitSm sm, long timeoutMs) throws Exception {
        Objects.requireNonNull(sm, "SubmitSm must not be null");
        SessionHolder holder = selectSession();
        return holder.send(sm, timeoutMs);
    }

    /**
     * Helper for UCS-2 encoding (UTF-16BE).
     * Typically used for Unicode SMS.
     * <p>
     * IMPORTANT: Encoding bytes is not enough; your SubmitSm must also set data_coding to UCS-2 (0x08).
     */
    public byte[] encodeUcs2(String text) {
        String safe = (text == null) ? "" : text;
        return CharsetUtil.encode(safe, CharsetUtil.CHARSET_UCS_2);
    }

    /**
     * Builds 8-bit concatenation UDH for multipart SMS.
     * <p>
     * Wire format:
     * 05 00 03 <ref> <totalParts> <partNumber>
     * <p>
     * - ref: same across all segments of a message
     * - total: total number of segments
     * - seq: 1-based segment index (1..total)
     * <p>
     * NOTE: Parameter order is (total, seq, ref) but wire order is (ref, total, seq).
     * This is correct but easy to misread—keep it in mind when calling it.
     */
    public byte[] buildConcatenationUdh8bit(byte total, byte seq, byte ref) {
        return new byte[]{0x05, 0x00, 0x03, ref, total, seq};
    }

    /**
     * Round-robin selection.
     * Uses floorMod to avoid negative indices when the counter overflows.
     */
    private SessionHolder selectSession() {
        if (sessions.isEmpty()) {
            throw new IllegalStateException("SMPP manager not started (no sessions)");
        }
        int idx = Math.floorMod(rr.getAndIncrement(), sessions.size());
        return sessions.get(idx);
    }

    /**
     * Maps config string to Cloudhopper SmppBindType.
     * Default to TRANSCEIVER since it supports submit + inbound PDUs (DLR, enquire_link, etc).
     */
    private SmppBindType parseBindType(String s) {
        String v = (s == null) ? "" : s.trim().toUpperCase();
        return switch (v) {
            case "TRANSMITTER" -> SmppBindType.TRANSMITTER;
            case "RECEIVER" -> SmppBindType.RECEIVER;
            default -> SmppBindType.TRANSCEIVER;
        };
    }

    /**
     * Determines SMPP interface version. Cloudhopper expects a byte constant.
     * <p>
     * Current implementation always returns SMPP 3.4 (most common).
     * If you need to support other versions, map props.getInterfaceVersion() accordingly.
     */
    private byte interfaceVersion(int v) {
        return SmppConstants.VERSION_3_4;
    }

    /**
     * Reflection-based setter invocation to stay compatible with different Cloudhopper builds.
     * <p>
     * Problem:
     * - Method names / signatures differ across versions (e.g. setEnquireLinkInterval(int) vs *Millis(long)).
     * <p>
     * Tradeoff:
     * - This compiles across versions, but you lose compile-time verification.
     */
    private static void tryInvokeSetter(Object target, String methodName, Class<?> argType, Object argValue) {
        try {
            Method m = target.getClass().getMethod(methodName, argType);
            m.invoke(target, argValue);
        } catch (Exception ignore) {
        }
    }

    /**
     * Waits for a WindowFuture response in a way that is tolerant of Cloudhopper version differences.
     * <p>
     * Strategy:
     * 1) If WindowFuture is also a java.util.concurrent.Future, call get(timeout).
     * 2) Otherwise, call an "await" variant (await(timeoutMillis) or await(timeout, unit) or await()).
     * 3) Then retrieve the response using a getter method that exists in that version.
     * 4) If no response, attempt to retrieve and throw the underlying cause.
     * <p>
     * Timeout behavior:
     * - If the version does not support timed await and only has await() with no timeout,
     * we may block indefinitely. Prefer a Cloudhopper build that supports timed waiting.
     */
    private static PduResponse awaitWindowResponse(WindowFuture<?, ?, ?> wf, long timeoutMs) throws Exception {
        if (wf instanceof Future<?> f) {
            Object r = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return (PduResponse) r;
        }

        boolean completed = false;

        // Try: await(long timeoutMillis)
        try {
            Method await = wf.getClass().getMethod("await", long.class);
            Object ok = await.invoke(wf, timeoutMs);
            completed = !(ok instanceof Boolean) || (Boolean) ok;
        } catch (NoSuchMethodException ignore) {
            // Try: await(long timeout, TimeUnit unit)
            try {
                Method await = wf.getClass().getMethod("await", long.class, TimeUnit.class);
                Object ok = await.invoke(wf, timeoutMs, TimeUnit.MILLISECONDS);
                completed = !(ok instanceof Boolean) || (Boolean) ok;
            } catch (NoSuchMethodException ignore2) {
                // Last resort: await() (no timeout)
                Method await = wf.getClass().getMethod("await");
                await.invoke(wf);
                completed = true;
            }
        }

        if (!completed) {
            throw new TimeoutException("Timed out waiting for SMPP response");
        }

        // Response getter names vary by build/version.
        Object respObj = null;
        for (String getter : new String[]{"getResponse", "getPduResponse", "getResult"}) {
            try {
                Method m = wf.getClass().getMethod(getter);
                respObj = m.invoke(wf);
                if (respObj != null) break;
            } catch (NoSuchMethodException ignore) {
            }
        }
        if (respObj instanceof PduResponse pr) return pr;

        // If no response, attempt to surface the root cause.
        Object causeObj = null;
        for (String getter : new String[]{"getCause", "getException", "getFailureCause"}) {
            try {
                Method m = wf.getClass().getMethod(getter);
                causeObj = m.invoke(wf);
                if (causeObj != null) break;
            } catch (NoSuchMethodException ignore) {
            }
        }
        if (causeObj instanceof Throwable t) {
            throw new ExecutionException(t);
        }

        throw new IllegalStateException("No SMPP response available (and no cause exposed by WindowFuture)");
    }

    /**
     * One SMPP connection wrapper.
     * <p>
     * Contains:
     * - per-holder executor (threads named for easier diagnosis)
     * - DefaultSmppClient (used to bind)
     * - SmppSession (actual bound connection)
     * <p>
     * Thread safety:
     * - ensureBound() and close() synchronized on lock to avoid double binds/destroys.
     * - session is volatile so send() sees the latest bound session after ensureBound().
     */
    private final class SessionHolder {
        private final String name;
        private final Object lock = new Object();

        /**
         * Executor used by Cloudhopper client/session for background work.
         * Daemon threads prevent hanging JVM shutdown if something leaks.
         */
        private final ExecutorService clientExecutor;

        /**
         * Cloudhopper client; created lazily on first bind attempt.
         */
        private DefaultSmppClient client;

        /**
         * Active bound session (or null if never bound / bind failed).
         */
        private volatile SmppSession session;

        SessionHolder(String name) {
            this.name = name;

            // Create executor AFTER name assignment, so thread naming is stable.
            this.clientExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setName(this.name + "-client");
                t.setDaemon(true);
                return t;
            });
        }

        /**
         * Ensures an SMPP bind exists.
         * <p>
         * What happens on failures:
         * - We log and keep the manager alive.
         * - The next send() will try again (no backoff here; implement backoff in production if needed).
         */
        void ensureBound() {
            synchronized (lock) {
                if (session != null && session.isBound()) return;

                try {
                    if (client == null) client = new DefaultSmppClient(clientExecutor, 1);

                    SmppSessionConfiguration cfg = new SmppSessionConfiguration();
                    cfg.setName(name);
                    cfg.setType(parseBindType(props.getBindType()));
                    cfg.setHost(props.getHost());
                    cfg.setPort(props.getPort());
                    cfg.setSystemId(props.getSystemId());
                    cfg.setPassword(props.getPassword());
                    cfg.setSystemType(props.getSystemType() == null ? "" : props.getSystemType());
                    cfg.setInterfaceVersion(interfaceVersion(props.getInterfaceVersion()));

                    // Connection and SMPP timing:
                    cfg.setConnectTimeout(props.getConnectTimeoutMs());
                    cfg.setBindTimeout(props.getBindTimeoutMs());

                    // Windowing controls in-flight requests and protects session:
                    // - windowSize = max concurrent in-flight PDUs (submit_sm etc.)
                    // - requestExpiryTimeout = max lifetime before Cloudhopper expires it
                    cfg.setRequestExpiryTimeout(props.getRequestExpiryTimeoutMs());
                    cfg.setWindowSize(props.getWindowSize());
                    cfg.setWindowWaitTimeout(props.getRequestExpiryTimeoutMs());

                    // Keep alive (enquire_link) interval; reflective setters for version tolerance.
                    int enquireMs = props.getEnquireLinkIntervalMs();
                    tryInvokeSetter(cfg, "setEnquireLinkInterval", int.class, enquireMs);
                    tryInvokeSetter(cfg, "setEnquireLinkInterval", long.class, (long) enquireMs);
                    tryInvokeSetter(cfg, "setEnquireLinkIntervalMillis", int.class, enquireMs);
                    tryInvokeSetter(cfg, "setEnquireLinkIntervalMillis", long.class, (long) enquireMs);

                    // Bind and attach a handler for inbound PDUs (DLRs, etc.)
                    this.session = client.bind(cfg, new SimpleSessionHandler(name));

                    log.info("SMPP bound name={} bindType={} windowSize={}", name, cfg.getType(), cfg.getWindowSize());

                } catch (Exception e) {
                    log.warn("SMPP bind failed name={} reason={}", name, e.getMessage());
                }
            }
        }

        /**
         * Sends submit_sm on the currently bound session and waits for submit_sm_resp.
         * <p>
         * Behavior:
         * - Lazy rebind if session is not present or not bound.
         * - Uses session windowing API (sendRequestPdu).
         * - Converts WindowFuture into PduResponse in a version-tolerant way.
         */
        SubmitSmResp send(SubmitSm sm, long timeoutMs) throws Exception {
            SmppSession s = session;
            if (s == null || !s.isBound()) {
                ensureBound();
                s = session;
                if (s == null || !s.isBound()) {
                    throw new IllegalStateException("SMPP session not bound (" + name + ")");
                }
            }

            // In some builds, this returns WindowFuture instead of a direct response.
            WindowFuture<Integer, PduRequest, PduResponse> fut = s.sendRequestPdu(sm, timeoutMs, false);

            PduResponse resp = awaitWindowResponse(fut, timeoutMs);

            if (resp instanceof SubmitSmResp ssr) return ssr;
            throw new IllegalStateException("Unexpected SMPP response type: " + (resp == null ? null : resp.getClass()));
        }

        /**
         * Explicit shutdown. Intended to be called ONLY from SmppSessionManager.stop().
         * <p>
         * Destroys:
         * - session: closes bind + underlying channel
         * - client: cleans up resources
         * - executor: stops threads
         */
        void close() {
            synchronized (lock) {
                try {
                    if (session != null) session.destroy();
                } catch (Exception ignore) {
                }
                session = null;

                try {
                    if (client != null) client.destroy();
                } catch (Exception ignore) {
                }
                client = null;

                clientExecutor.shutdownNow();
            }
        }
    }

    /**
     * Receives inbound PDUs from the SMSC.
     * <p>
     * For many SMSCs:
     * - DeliverSm is used for delivery receipts (DLR).
     * - enquire_link may be sent by SMSC to check connectivity.
     * <p>
     * Current behavior:
     * - Log inbound requests at debug
     * - Always ACK by returning request.createResponse()
     * <p>
     * Production TODO:
     * - Parse DeliverSm for DLR and update message status in your database.
     */
    private static final class SimpleSessionHandler extends com.cloudhopper.smpp.impl.DefaultSmppSessionHandler {
        private final String name;

        private SimpleSessionHandler(String name) {
            this.name = name;
        }

        @Override
        public PduResponse firePduRequestReceived(PduRequest request) {
            log.debug("SMPP inbound PDU session={} cmdId={} seq={}", name, request.getCommandId(), request.getSequenceNumber());
            return request.createResponse();
        }

        @Override
        public void fireChannelUnexpectedlyClosed() {
            // This is a signal that your bind is gone; your next send() will trigger ensureBound().
            // If you want faster recovery, you can trigger an async rebind attempt here.
            log.warn("SMPP channel unexpectedly closed session={}", name);
        }
    }
}