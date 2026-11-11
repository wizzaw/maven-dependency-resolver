package dev.airgap.bundler.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * In-memory per-IP sliding-window rate limiter and concurrent ZIP stream guard.
 */
public class RateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final int maxRequestsPerMinute;
    private final Semaphore streamSemaphore;
    private final Map<String, Deque<Long>> requestTimestampsByIp = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequestsPerMinute, int maxConcurrentStreams) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.streamSemaphore = new Semaphore(maxConcurrentStreams);
    }

    public boolean tryAllowRequest(String clientIp) {
        if (maxRequestsPerMinute <= 0) {
            return true;
        }
        String key = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp;
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW_MS;

        Deque<Long> timestamps = requestTimestampsByIp.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequestsPerMinute) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    public boolean tryAcquireStreamSlot(long timeoutSeconds) throws InterruptedException {
        return streamSemaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
    }

    public void releaseStreamSlot() {
        streamSemaphore.release();
    }

    public int availableStreamSlots() {
        return streamSemaphore.availablePermits();
    }
}
