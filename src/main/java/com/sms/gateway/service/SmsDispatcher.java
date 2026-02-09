package com.sms.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Simple bounded in-memory queue + worker threads.
 * <p>
 * Why:
 * - Decouples API request latency from SMPP submit latency.
 * - Provides backpressure via a bounded queue (when full, enqueue fails -> return 429).
 * <p>
 * Production warnings:
 * - In-memory queue is not durable; jobs are lost on restart/crash.
 * - For guaranteed delivery, use a broker (Kafka/RabbitMQ/SQS) and persist job states.
 * <p>
 * Implementation details:
 * - Uses an ArrayBlockingQueue for predictable memory usage and fair FIFO behavior.
 * - Worker threads run until close() is called.
 */
public class SmsDispatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SmsDispatcher.class);

    private final BlockingQueue<SmsJob> queue;
    private final ExecutorService workers;
    private final Consumer<SmsJob> consumer;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public SmsDispatcher(int capacity, int workerThreads, Consumer<SmsJob> consumer) {
        this(capacity, workerThreads, "sms-worker", consumer);
    }

    public SmsDispatcher(int capacity, int workerThreads, String threadNamePrefix, Consumer<SmsJob> consumer) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");

        int threads = Math.max(1, workerThreads);

        ThreadFactory tf = new ThreadFactory() {
            private final ThreadFactory base = Executors.defaultThreadFactory();
            private final java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = base.newThread(r);
                t.setName(threadNamePrefix + "-" + idx.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };

        this.workers = Executors.newFixedThreadPool(threads, tf);

        for (int i = 0; i < threads; i++) {
            workers.submit(this::runLoop);
        }
    }

    /**
     * Non-blocking enqueue (fast path for API).
     * Returns false if the queue is full.
     */
    public boolean tryEnqueue(SmsJob job) {
        if (!running.get()) return false;
        return queue.offer(job);
    }

    /**
     * Optional variant if you want a tiny wait before rejecting (helps under bursty load).
     */
    public boolean tryEnqueue(SmsJob job, long timeout, TimeUnit unit) throws InterruptedException {
        if (!running.get()) return false;
        return queue.offer(job, timeout, unit);
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                SmsJob job = queue.take();
                consumer.accept(job);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // Do not swallow silently: you want to see poison jobs/bugs.
                log.warn("SMS worker crashed a job: {}", t.getMessage());
            }
        }
    }

    /**
     * Stops workers immediately.
     * For graceful draining, you can extend this to:
     * - set running=false
     * - workers.shutdown()
     * - awaitTermination(...)
     */
    @Override
    public void close() {
        running.set(false);
        workers.shutdownNow();
    }
}