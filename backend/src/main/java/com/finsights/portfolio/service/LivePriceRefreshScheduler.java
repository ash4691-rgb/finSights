package com.finsights.portfolio.service;

import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@link com.finsights.portfolio.domain.LivePrice} warm for every symbol in the tracked pool
 * (see {@link TrackedSymbolPoolService}), every 15 minutes — the one place that calls out to
 * Yahoo/mfapi for a routine refresh, so HoldingService/WatchlistService's own page-load paths
 * never have to. Fires once at startup too (Spring's default for fixedDelay with no initialDelay),
 * so the cache isn't cold for the first request after a deploy.
 */
@Component
public class LivePriceRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(LivePriceRefreshScheduler.class);

    private final TrackedSymbolPoolService pool;
    private final LivePriceService livePrices;

    public LivePriceRefreshScheduler(TrackedSymbolPoolService pool, LivePriceService livePrices) {
        this.pool = pool;
        this.livePrices = livePrices;
    }

    @Scheduled(fixedDelayString = "PT15M")
    public void refresh() {
        List<String> symbols = pool.sync();
        if (symbols.isEmpty()) return;
        try {
            livePrices.refreshAll(new HashSet<>(symbols));
        } catch (RuntimeException ex) {
            log.warn("Live price refresh failed", ex);
        }
    }
}
