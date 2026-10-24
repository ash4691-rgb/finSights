package com.finsights.portfolio.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@link NewsService}'s cache warm for every symbol in the tracked pool (see
 * {@link TrackedSymbolPoolService}), every 15 minutes — same cadence as
 * {@link LivePriceRefreshScheduler} now (price and news are two independent flows reading the
 * same pool, not one combined job — each degrades/fails on its own). NewsService's own per-symbol
 * TTL (1h) still governs how often any one symbol is actually re-fetched; this just controls how
 * often the pool is checked.
 */
@Component
public class NewsRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsRefreshScheduler.class);

    private final TrackedSymbolPoolService pool;
    private final NewsService news;
    private final NewsDismissalService dismissals;
    private final boolean enabled;

    public NewsRefreshScheduler(TrackedSymbolPoolService pool, NewsService news, NewsDismissalService dismissals,
                                @Value("${app.news.enabled}") boolean enabled) {
        this.pool = pool;
        this.news = news;
        this.dismissals = dismissals;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "PT15M")
    public void refresh() {
        if (!enabled) return;
        // An article past NewsService's own retention window can never resurface (headlinesFor()
        // already filters it out), so a dismissal guarding against it is pointless to keep.
        try {
            dismissals.prune(Instant.now().minus(NewsService.MAX_AGE));
        } catch (RuntimeException ex) {
            log.warn("Dismissed-news pruning failed", ex);
        }

        List<String> symbols = pool.sync();
        if (symbols.isEmpty()) return;
        try {
            news.refreshAll(new HashSet<>(symbols));
        } catch (RuntimeException ex) {
            log.warn("News refresh failed", ex);
        }
    }
}
