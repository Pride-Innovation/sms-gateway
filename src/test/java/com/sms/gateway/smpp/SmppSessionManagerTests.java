package com.sms.gateway.smpp;

import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.sms.gateway.config.SmppProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmppSessionManagerTests {
    private TestSmppSessionManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void forcedIdleRebindRunsAfterConfiguredInterval() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-01T00:00:00Z"));
        manager = newManager(clock, 30_000, 1_000);
        manager.planBindSuccess();
        manager.planBindSuccess();

        manager.start();
        assertEquals(1, manager.bindCalls());

        clock.advance(Duration.ofSeconds(31));
        manager.runHealthChecksNow();

        assertEquals(2, manager.bindCalls());
        assertTrue(manager.healthSnapshot().up());
    }

    @Test
    void unexpectedCloseTriggersRebindOnHealthCheck() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-01T00:00:00Z"));
        manager = newManager(clock, 0, 1_000);
        manager.planBindSuccess();
        manager.planBindSuccess();

        manager.start();
        assertEquals(1, manager.bindCalls());
        assertNotNull(manager.latestHandler());

        manager.latestHandler().fireChannelUnexpectedlyClosed();
        manager.runHealthChecksNow();

        assertEquals(2, manager.bindCalls());
        assertEquals(1.0d, manager.registry().find("sms.smpp.channel.unexpected_close").counter().count());
    }

    @Test
    void bindFailuresRespectBackoffBeforeRetrying() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-01T00:00:00Z"));
        manager = newManager(clock, 0, 1_000);
        manager.planBindFailure("first failure");
        manager.planBindFailure("second failure");
        manager.planBindSuccess();

        manager.start();
        assertEquals(1, manager.bindCalls());
        assertFalse(manager.healthSnapshot().up());
        assertEquals("WAITING_TO_RETRY", manager.healthSnapshot().sessions().get(0).state());

        manager.runHealthChecksNow();
        assertEquals(1, manager.bindCalls());

        clock.advance(Duration.ofSeconds(1));
        manager.runHealthChecksNow();
        assertEquals(2, manager.bindCalls());
        assertEquals("WAITING_TO_RETRY", manager.healthSnapshot().sessions().get(0).state());

        clock.advance(Duration.ofSeconds(1));
        manager.runHealthChecksNow();
        assertEquals(2, manager.bindCalls());

        clock.advance(Duration.ofSeconds(1));
        manager.runHealthChecksNow();
        assertEquals(3, manager.bindCalls());
        assertTrue(manager.healthSnapshot().up());
    }

    private TestSmppSessionManager newManager(MutableClock clock, int forceRebindIntervalMs, int reconnectDelayMs) {
        SmppProperties props = new SmppProperties();
        props.setHost("localhost");
        props.setPort(2775);
        props.setSystemId("mtn-test");
        props.setPassword("secret");
        props.setBindType("TRANSCEIVER");
        props.setInterfaceVersion(34);
        props.setConnectTimeoutMs(1_000);
        props.setRequestExpiryTimeoutMs(1_000);
        props.setBindTimeoutMs(1_000);
        props.setWindowSize(1);
        props.setEnquireLinkIntervalMs(30_000);
        props.setReconnectDelayMs(reconnectDelayMs);
        props.setHealthCheckIntervalMs(60_000);
        props.setForceRebindIntervalMs(forceRebindIntervalMs);
        props.setSessions(1);
        return new TestSmppSessionManager(props, clock);
    }

    private static final class TestSmppSessionManager extends SmppSessionManager {
        private final SimpleMeterRegistry registry;
        private final Queue<Object> bindPlan = new ArrayDeque<>();
        private final List<DefaultSmppSessionHandler> handlers = new ArrayList<>();
        private final AtomicInteger bindCalls = new AtomicInteger(0);

        private TestSmppSessionManager(SmppProperties props, Clock clock) {
            this(props, clock, new SimpleMeterRegistry());
        }

        private TestSmppSessionManager(SmppProperties props, Clock clock, SimpleMeterRegistry registry) {
            super(props, registry, clock, newScheduler());
            this.registry = registry;
        }

        private static ScheduledExecutorService newScheduler() {
            return Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("smpp-test-health-check");
                return t;
            });
        }

        void planBindSuccess() {
            SmppSession session = mock(SmppSession.class);
            when(session.isBound()).thenReturn(true);
            bindPlan.add(session);
        }

        void planBindFailure(String reason) {
            bindPlan.add(new RuntimeException(reason));
        }

        int bindCalls() {
            return bindCalls.get();
        }

        DefaultSmppSessionHandler latestHandler() {
            return handlers.isEmpty() ? null : handlers.get(handlers.size() - 1);
        }

        SimpleMeterRegistry registry() {
            return registry;
        }

        @Override
        protected DefaultSmppClient createClient(java.util.concurrent.ExecutorService clientExecutor) {
            return mock(DefaultSmppClient.class);
        }

        @Override
        protected SmppSession bind(DefaultSmppClient client, SmppSessionConfiguration cfg, DefaultSmppSessionHandler handler) throws Exception {
            bindCalls.incrementAndGet();
            handlers.add(handler);
            Object outcome = bindPlan.remove();
            if (outcome instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return (SmppSession) outcome;
        }

        @Override
        protected long applyJitter(long delayMs) {
            return delayMs;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}