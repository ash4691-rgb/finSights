package com.finsights.portfolio.service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * A per-user daily cap on Goku queries, so a runaway chat loop (or a misbehaving client) can't
 * turn into a runaway Anthropic bill. In-memory by design: Phase 1 is gated to one account (see
 * {@link GokuAccessService}), so a counter that resets on redeploy is a fine trade for not adding
 * a table this feature may never need past its trial. Revisit if/when the allowlist widens.
 */
@Service
public class GokuRateLimiter {
    @Value("${app.goku.daily-query-limit:40}") private int dailyLimit;

    private final Map<String, AtomicInteger> countsByUserAndDay = new ConcurrentHashMap<>();

    /** Increments today's count for this user and reports whether that still fits the cap. */
    public boolean tryConsume(String userId) {
        AtomicInteger count = countsByUserAndDay.computeIfAbsent(key(userId), k -> new AtomicInteger());
        return count.incrementAndGet() <= dailyLimit;
    }

    public int remainingToday(String userId) {
        AtomicInteger count = countsByUserAndDay.get(key(userId));
        int used = count == null ? 0 : count.get();
        return Math.max(0, dailyLimit - used);
    }

    private String key(String userId) {
        return userId + ":" + LocalDate.now();
    }
}
