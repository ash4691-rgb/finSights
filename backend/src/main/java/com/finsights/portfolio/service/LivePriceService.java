package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.LivePrice;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.repository.LivePriceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared, DB-backed live-price cache every holding and watchlist item reads from instead of
 * calling {@link MarketDataService} (Yahoo/mfapi) directly. {@link LivePriceRefreshScheduler}
 * keeps it warm every 15 minutes for every symbol currently held or watched by anyone — so
 * reading a price here is normally just a local lookup, and two users holding the same ticker
 * share one fetch instead of each tripping their own call (and doubling the odds of Yahoo's
 * rate limit) on every single page load.
 */
@Service
public class LivePriceService {

    private static final Logger log = LoggerFactory.getLogger(LivePriceService.class);
    /** A row older than this is treated as if it were never fetched — the scheduled refresh
     *  should have long since replaced it, so something's wrong (the job hasn't run, or this
     *  symbol only just started being tracked) and a one-off fetch is worth the cost rather
     *  than serving an indefinitely stale price. */
    private static final Duration STALE_FALLBACK_THRESHOLD = Duration.ofMinutes(45);

    private final LivePriceRepository livePrices;
    private final MarketDataService marketData;

    public LivePriceService(LivePriceRepository livePrices, MarketDataService marketData) {
        this.livePrices = livePrices;
        this.marketData = marketData;
    }

    /**
     * Reads the given symbols from the shared table — no external call for a symbol the
     * scheduled job already keeps warm. A symbol with no row yet (never seen before) or whose
     * row has fallen well behind the refresh cadence gets a one-off on-demand fetch instead of
     * being left unpriced for up to a whole refresh cycle; that fetch's result is itself saved,
     * so the very next reader (this user's next page load, or a different user on the same
     * ticker) finds it already cached.
     */
    @Transactional
    public Map<String, MarketQuoteResponse> getPrices(Set<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return Map.of();
        Set<String> normalized = normalize(symbols);
        Map<String, LivePrice> cached = livePrices.findAllById(normalized).stream()
                .collect(Collectors.toMap(LivePrice::getSymbol, r -> r));

        Instant staleCutoff = Instant.now().minus(STALE_FALLBACK_THRESHOLD);
        Set<String> missingOrStale = normalized.stream()
                .filter(s -> { LivePrice row = cached.get(s); return row == null || row.getFetchedAt().isBefore(staleCutoff); })
                .collect(Collectors.toSet());
        if (!missingOrStale.isEmpty()) {
            log.debug("Live price fallback fetch for {} symbol(s) missing from the shared cache", missingOrStale.size());
            upsert(marketData.quotes(missingOrStale)).forEach(row -> cached.put(row.getSymbol(), row));
        }

        Map<String, MarketQuoteResponse> out = new HashMap<>();
        cached.forEach((symbol, row) -> out.put(symbol, toQuote(row)));
        return out;
    }

    /** Called by {@link LivePriceRefreshScheduler} for every symbol anyone currently holds or
     *  watches — unconditionally re-fetches and upserts, regardless of how fresh each row is,
     *  since this IS the refresh. */
    public void refreshAll(Set<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return;
        Set<String> normalized = normalize(symbols);
        Map<String, MarketQuoteResponse> quotes = marketData.quotes(normalized);
        int saved = upsert(quotes).size();
        log.info("Live price refresh: {} of {} tracked symbol(s) priced", saved, normalized.size());
    }

    /** Builds and persists the updated rows, returning them directly — deliberately not the
     *  result of saveAll() itself, so the caller's in-memory merge doesn't depend on what a
     *  particular JpaRepository implementation (or a test double) happens to hand back. */
    private java.util.List<LivePrice> upsert(Map<String, MarketQuoteResponse> quotes) {
        if (quotes.isEmpty()) return java.util.List.of();
        Map<String, LivePrice> existing = livePrices.findAllById(quotes.keySet()).stream()
                .collect(Collectors.toMap(LivePrice::getSymbol, r -> r));
        Instant now = Instant.now();
        java.util.List<LivePrice> rows = quotes.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().price() != null && e.getValue().price().signum() > 0)
                .map(e -> {
                    LivePrice row = existing.getOrDefault(e.getKey(), new LivePrice());
                    row.setSymbol(e.getKey());
                    row.setName(e.getValue().name());
                    row.setPrice(e.getValue().price());
                    row.setCurrency(e.getValue().currency());
                    row.setFetchedAt(e.getValue().asOf() != null ? e.getValue().asOf() : now);
                    return row;
                })
                .toList();
        livePrices.saveAll(rows);
        return rows;
    }

    private MarketQuoteResponse toQuote(LivePrice row) {
        return new MarketQuoteResponse(row.getSymbol(), row.getName(), row.getPrice(), row.getCurrency(), row.getFetchedAt());
    }

    private Set<String> normalize(Set<String> symbols) {
        Set<String> out = new HashSet<>();
        for (String s : symbols) {
            if (s != null && !s.isBlank()) out.add(s.trim().toUpperCase());
        }
        return out;
    }
}
