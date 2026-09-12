package com.cretas.aims.audit.client;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Process-local bounded limiter for the browser telemetry endpoint. */
final class ClientErrorRateLimiter {
    static final int MAX_REPORTS_PER_MINUTE = 10;
    private static final int MAX_IDENTITIES = 10_000;
    private static final long WINDOW_MILLIS = 60_000L;

    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();

    ClientErrorRateLimiter() {
        this(Clock.systemUTC());
    }

    ClientErrorRateLimiter(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean tryAcquire(String factoryId, long userId) {
        long now = clock.millis();
        long window = now / WINDOW_MILLIS;
        String key = factoryId + ":" + userId;
        buckets.entrySet().removeIf(entry -> entry.getValue().window < window - 1);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= MAX_IDENTITIES) return false;
            bucket = new Bucket(window);
            buckets.put(key, bucket);
        } else if (bucket.window != window) {
            bucket = new Bucket(window);
            buckets.put(key, bucket);
        }
        if (bucket.count >= MAX_REPORTS_PER_MINUTE) return false;
        bucket.count++;
        return true;
    }

    private static final class Bucket {
        private final long window;
        private int count;

        private Bucket(long window) {
            this.window = window;
        }
    }
}
