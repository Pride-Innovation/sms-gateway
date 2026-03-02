package com.sms.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Asynchronous SMS dispatcher backed by a bounded in-memory queue and a fixed worker pool.
 *
 * <h2>Purpose</h2>
 * Decouples API request handling from SMS submission latency (e.g., SMPP calls).
 * The API thread only enqueues a job, while worker threads process jobs in the background.
 *
 * <h2>Backpressure</h2>
 * The queue has a fixed capacity. When full, enqueue operations fail immediately (or after
 * a small timeout if configured). This allows the API layer to return HTTP 429 instead
 * of overloading the system.
 *
 * <h2>Delivery Guarantees</h2>
 * - In-memory only (NOT durable)
 * - Jobs are lost on process crash or restart
 * - No retry, persistence, or dead-letter mechanism
 * <p>
 * For guaranteed delivery, use a persistent message broker (e.g., Kafka/RabbitMQ/SQS)
 * and track job state externally.
 *
 * <h2>Threading Model</h2>
 * - Multiple producer threads (API requests) enqueue jobs
 * - Fixed number of worker threads consume jobs
 * - Uses ArrayBlockingQueue for predictable memory usage
 *
 * <h2>Shutdown Behavior</h2>
 * - close() performs an immediate stop (interrupts workers)
 * - Pending queued jobs may not be processed
 * <p>
 * This class is thread-safe.
 */
public class SmsDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SmsDispatcher.class);

    /**
     * Bounded FIFO queue holding pending SMS jobs.
     * Provides natural backpressure and thread-safe producer/consumer coordination.
     */
    private final BlockingQueue<SmsJob> queue;

    /**
     * Fixed thread pool responsible for consuming and processing jobs.
     */
    private final ExecutorService workers;

    /**
     * The business logic executed for each dequeued SmsJob.
     * Injected to keep this dispatcher infrastructure-focused.
     */
    private final Consumer<SmsJob> consumer;

    /**
     * Lifecycle flag controlling worker execution.
     * When set to false, workers exit their processing loop.
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * Creates a dispatcher with a default worker thread name prefix ("sms-worker").
     *
     * @param capacity      Maximum number of jobs allowed in the queue (minimum 1).
     * @param workerThreads Number of worker threads (minimum 1).
     * @param consumer      Job processing logic (must not be null).
     */
    public SmsDispatcher(int capacity, int workerThreads, Consumer<SmsJob> consumer) {
        this(capacity, workerThreads, "sms-worker", consumer);
    }

    /**
     * Creates a dispatcher with configurable thread naming.
     *
     * @param capacity         Maximum queue size. When full, enqueue fails.
     * @param workerThreads    Number of background worker threads.
     * @param threadNamePrefix Prefix for worker thread names (useful for debugging/monitoring).
     * @param consumer         Logic invoked for each SmsJob.
     */
    public SmsDispatcher(int capacity,
                         int workerThreads,
                         String threadNamePrefix,
                         Consumer<SmsJob> consumer) {

        // Ensure queue always has at least capacity 1
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));

        // Fail fast if processing logic is not provided
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");

        int threads = Math.max(1, workerThreads);

        // Custom thread factory to:
        // - assign readable names
        // - mark threads as daemon (do not block JVM shutdown)
        ThreadFactory tf = new ThreadFactory() {
            private final ThreadFactory base = Executors.defaultThreadFactory();
            private final java.util.concurrent.atomic.AtomicInteger idx =
                    new java.util.concurrent.atomic.AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = base.newThread(r);
                t.setName(threadNamePrefix + "-" + idx.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };

        this.workers = Executors.newFixedThreadPool(threads, tf);

        // Start worker threads immediately.
        // Each worker continuously polls the queue and processes jobs.
        for (int i = 0; i < threads; i++) {
            workers.submit(this::runLoop);
        }
    }

    /**
     * Attempts to enqueue a job without blocking.
     *
     * <p>Intended for the API "fast path".
     *
     * @param job The SMS job to enqueue.
     * @return true if successfully enqueued,
     * false if the queue is full or dispatcher is shutting down.
     */
    public boolean tryEnqueue(SmsJob job) {
        if (!running.get()) {
            return false;
        }
        return queue.offer(job); // Non-blocking
    }

    /**
     * Attempts to enqueue a job, waiting up to the specified timeout if the queue is full.
     * <p>
     * Useful under burst traffic to avoid immediate rejection.
     *
     * @param job     The SMS job.
     * @param timeout Maximum time to wait.
     * @param unit    Time unit.
     * @return true if enqueued, false if timeout elapsed or dispatcher is stopping.
     * @throws InterruptedException if the calling thread is interrupted while waiting.
     */
    public boolean tryEnqueue(SmsJob job, long timeout, TimeUnit unit)
            throws InterruptedException {

        if (!running.get()) {
            return false;
        }
        return queue.offer(job, timeout, unit);
    }

    /**
     * Main worker loop.
     * <p>
     * Each worker:
     * 1. Blocks on queue.take() until a job is available
     * 2. Executes consumer.accept(job)
     * 3. Repeats until shutdown
     * <p>
     * InterruptedException signals shutdown.
     * Any other exception is logged and processing continues.
     */
    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // Blocks until a job is available
                SmsJob job = queue.take();

                // Execute user-provided processing logic
                log.info("Logging the job before sending the message :: {}", job);
                consumer.accept(job);

            } catch (InterruptedException ie) {
                // Restore interrupt status and exit loop
                Thread.currentThread().interrupt();
                return;

            } catch (Throwable t) {
                // Catch all to prevent worker thread death from bad jobs.
                // This keeps the pool healthy even if one job fails.
                log.warn("SMS worker crashed a job: {}", t.getMessage());
            }
        }
    }

    /**
     * Immediately stops the dispatcher.
     * <p>
     * Behavior:
     * - Prevents further enqueue attempts
     * - Interrupts all worker threads
     * - Does NOT guarantee queued jobs are processed
     * <p>
     * For graceful shutdown with draining:
     * 1. set running = false
     * 2. call workers.shutdown()
     * 3. await termination
     */
    @Override
    public void close() {
        running.set(false);
        workers.shutdownNow(); // Interrupts workers immediately
    }
}