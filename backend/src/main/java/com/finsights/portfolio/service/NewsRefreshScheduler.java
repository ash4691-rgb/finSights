package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.WatchlistRepository;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@link NewsService}'s cache warm for every symbol anyone currently holds (MARKET_PRICE) or
 * watches, every hour — same union-of-tickers approach as {@link LivePriceRefreshScheduler}, just
 * on a longer interval since headlines don't need 15-minute freshness.
 */
@Component
public class NewsRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsRefreshScheduler.class);

    private final HoldingRepository holdings;
    private final WatchlistRepository watchlist;
    private final NewsService news;
    private final boolean enabled;

    public NewsRefreshScheduler(HoldingRepository holdings, WatchlistRepository watchlist, NewsService news,
                                @Value("${app.news.enabled}") boolean enabled) {
        this.holdings = holdings;
        this.watchlist = watchlist;
        this.news = news;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "PT60M")
    public void refresh() {
        if (!enabled) return;
        Set<String> symbols = new HashSet<>();
        holdings.findDistinctTickerSymbolsByValuationMethod(ValuationMethod.MARKET_PRICE).forEach(symbols::add);
        watchlist.findDistinctTickerSymbols().forEach(symbols::add);
        if (symbols.isEmpty()) return;
        try {
            news.refreshAll(symbols);
        } catch (RuntimeException ex) {
            log.warn("News refresh failed", ex);
        }
    }
}
