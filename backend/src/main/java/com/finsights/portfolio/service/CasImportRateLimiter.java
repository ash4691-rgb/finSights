package com.finsights.portfolio.service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * A per-user daily cap on CAS statement uploads, so a runaway client (or someone re-uploading the
 * same file on a loop) can't turn into a runaway Anthropic bill — each upload is a real API call
 * against a user-supplied file. In-memory by design, same trade-off as GokuRateLimiter: this
 * feature doesn't have enough volume yet to justify a table that has to survive a redeploy.
 */
@Service
public class CasImportRateLimiter {
    @Value("${app.cas-import.daily-upload-limit:10}") private int dailyLimit;

    private final Map<String, AtomicInteger> countsByUserAndDay = new ConcurrentHashMap<>();

    /** Increments today's count for this user and reports whether that still fits the cap. */
    public boolean tryConsume(String userId) {
        AtomicInteger count = countsByUserAndDay.computeIfAbsent(key(userId), k -> new AtomicInteger());
        return count.incrementAndGet() <= dailyLimit;
    }

    private String key(String userId) { return userId + ":" + LocalDate.now(); }
}
