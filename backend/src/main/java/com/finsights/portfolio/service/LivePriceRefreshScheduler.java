package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.WatchlistRepository;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@link com.finsights.portfolio.domain.LivePrice} warm for every symbol anyone currently
 * holds (MARKET_PRICE) or watches, every 15 minutes — the one place that calls out to Yahoo/mfapi
 * for a routine refresh, so HoldingService/WatchlistService's own page-load paths never have to.
 * Fires once at startup too (Spring's default for fixedDelay with no initialDelay), so the cache
 * isn't cold for the first request after a deploy.
 */
@Component
public class LivePriceRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(LivePriceRefreshScheduler.class);

    private final HoldingRepository holdings;
    private final WatchlistRepository watchlist;
    private final LivePriceService livePrices;

    public LivePriceRefreshScheduler(HoldingRepository holdings, WatchlistRepository watchlist, LivePriceService livePrices) {
        this.holdings = holdings;
        this.watchlist = watchlist;
        this.livePrices = livePrices;
    }

    @Scheduled(fixedDelayString = "PT15M")
    public void refresh() {
        Set<String> symbols = new HashSet<>();
        holdings.findDistinctTickerSymbolsByValuationMethod(ValuationMethod.MARKET_PRICE).forEach(symbols::add);
        watchlist.findDistinctTickerSymbols().forEach(symbols::add);
        if (symbols.isEmpty()) return;
        try {
            livePrices.refreshAll(symbols);
        } catch (RuntimeException ex) {
            log.warn("Live price refresh failed", ex);
        }
    }
}
