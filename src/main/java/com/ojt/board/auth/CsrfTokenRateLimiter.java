package com.ojt.board.auth;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class CsrfTokenRateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final int maxEntries;
    private final Clock clock;
    private final LinkedHashMap<String, RequestWindow> windows = new LinkedHashMap<>();
    private boolean capacityRejectionReported;

    @Autowired
    public CsrfTokenRateLimiter(CsrfTokenRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    CsrfTokenRateLimiter(CsrfTokenRateLimitProperties properties, Clock clock) {
        this.maxRequests = properties.maxRequests();
        this.windowMillis = properties.window().toMillis();
        this.maxEntries = properties.maxEntries();
        this.clock = clock;
    }

    public synchronized Decision acquire(String remoteAddress) {
        long now = clock.millis();
        purgeExpired(now);

        String source = normalizeSource(remoteAddress);
        RequestWindow window = windows.get(source);
        if (window == null) {
            if (windows.size() >= maxEntries) {
                RequestWindow oldest = windows.values().iterator().next();
                boolean shouldLog = !capacityRejectionReported;
                capacityRejectionReported = true;
                return Decision.blocked(
                        retryAfterSeconds(oldest.startedAtMillis() + windowMillis, now), shouldLog);
            }
            windows.put(source, new RequestWindow(now, 1, false));
            return Decision.allowed();
        }
        if (window.count() >= maxRequests) {
            boolean shouldLog = !window.rejectionReported();
            if (shouldLog) {
                windows.put(source, new RequestWindow(window.startedAtMillis(), window.count(), true));
            }
            return Decision.blocked(
                    retryAfterSeconds(window.startedAtMillis() + windowMillis, now), shouldLog);
        }

        windows.put(source, new RequestWindow(window.startedAtMillis(), window.count() + 1, false));
        return Decision.allowed();
    }

    synchronized void clear() {
        windows.clear();
        capacityRejectionReported = false;
    }

    synchronized int trackedSources() {
        return windows.size();
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<String, RequestWindow>> iterator = windows.entrySet().iterator();
        boolean removed = false;
        while (iterator.hasNext()) {
            RequestWindow window = iterator.next().getValue();
            if (elapsed(now, window.startedAtMillis()) < windowMillis) {
                break;
            }
            iterator.remove();
            removed = true;
        }
        if (removed && windows.size() < maxEntries) {
            capacityRejectionReported = false;
        }
    }

    private static long elapsed(long now, long then) {
        return Math.max(0, now - then);
    }

    private static long retryAfterSeconds(long blockedUntil, long now) {
        return Math.max(1, (Math.max(0, blockedUntil - now) + 999) / 1000);
    }

    private static String normalizeSource(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    public record Decision(boolean permitted, long retryAfterSeconds, boolean shouldLog) {
        private static Decision allowed() {
            return new Decision(true, 0, false);
        }

        private static Decision blocked(long retryAfterSeconds, boolean shouldLog) {
            return new Decision(false, retryAfterSeconds, shouldLog);
        }
    }

    private record RequestWindow(long startedAtMillis, int count, boolean rejectionReported) {
    }
}
