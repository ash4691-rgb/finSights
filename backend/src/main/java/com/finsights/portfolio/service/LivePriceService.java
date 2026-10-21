package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.LivePrice;
import com.finsights.portfolio.domain.PriceHistoryPoint;
import com.finsights.portfolio.dto.MarketHistoryResponse;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.repository.LivePriceRepository;
import com.finsights.portfolio.repository.PriceHistoryPointRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    /** Chart ranges {@link #ownHistory} can serve — all four are daily-interval already (see
     *  MarketDataService's DERIVED_FROM_DAILY_SERIES), matching this table's daily granularity.
     *  1D/1W need intraday data this table never stores, so they always fall back to Yahoo. */
    private static final Map<String, Integer> OWN_HISTORY_RANGE_DAYS = Map.of("1M", 31, "3M", 92, "6M", 183, "1Y", 365);
    /** "Up to 1 year, minimal data points": one row per symbol per day, nothing older kept. */
    private static final int HISTORY_RETENTION_DAYS = 366;

    private final LivePriceRepository livePrices;
    private final MarketDataService marketData;
    private final PriceHistoryPointRepository priceHistory;

    public LivePriceService(LivePriceRepository livePrices, MarketDataService marketData, PriceHistoryPointRepository priceHistory) {
        this.livePrices = livePrices;
        this.marketData = marketData;
        this.priceHistory = priceHistory;
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
        List<LivePrice> saved = upsert(quotes);
        recordHistory(saved);
        priceHistory.deleteOlderThan(LocalDate.now(ZoneOffset.UTC).minusDays(HISTORY_RETENTION_DAYS));
        log.info("Live price refresh: {} of {} tracked symbol(s) priced", saved.size(), normalized.size());
    }

    /** Upserts today's row per symbol — at most one data point per symbol per day, however many
     *  times refreshAll() runs today, so a year of history stays ~365 rows per symbol, not ~35,000
     *  (one per 15-minute tick). Each call is its own short save, same reasoning as NewsService's
     *  refreshAll — never held open across the batch. */
    private void recordHistory(List<LivePrice> rows) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (LivePrice row : rows) {
            PriceHistoryPoint point = priceHistory.findBySymbolAndPriceDate(row.getSymbol(), today).orElseGet(PriceHistoryPoint::new);
            point.setSymbol(row.getSymbol());
            point.setPriceDate(today);
            point.setPrice(row.getPrice());
            point.setCurrency(row.getCurrency());
            point.setUpdatedAt(Instant.now());
            priceHistory.save(point);
        }
    }

    /** Serves 1M/3M/6M/1Y straight from our own accumulated daily history once there's enough
     *  back-history to cover the requested window — empty otherwise (a brand-new symbol, or one
     *  this feature hasn't been tracking for the full window yet), so the caller falls back to
     *  MarketDataService's Yahoo-backed history() exactly like before this existed. */
    public Optional<MarketHistoryResponse> ownHistory(String symbol, String rangeKey) {
        if (symbol == null || symbol.isBlank() || !OWN_HISTORY_RANGE_DAYS.containsKey(rangeKey)) return Optional.empty();
        String key = symbol.trim().toUpperCase();
        LocalDate requiredStart = LocalDate.now(ZoneOffset.UTC).minusDays(OWN_HISTORY_RANGE_DAYS.get(rangeKey));
        List<PriceHistoryPoint> points = priceHistory.findBySymbolAndPriceDateGreaterThanEqualOrderByPriceDateAsc(key, requiredStart);
        // A couple of days' grace for weekends/holidays/a missed refresh cycle — otherwise, not
        // enough back-history yet, so let the caller fall back to the live Yahoo chart call.
        if (points.isEmpty() || points.get(0).getPriceDate().isAfter(requiredStart.plusDays(2))) return Optional.empty();
        String currency = points.get(points.size() - 1).getCurrency();
        List<MarketHistoryResponse.Point> series = points.stream()
                .map(p -> new MarketHistoryResponse.Point(p.getPriceDate().atStartOfDay(ZoneOffset.UTC).toInstant(), p.getPrice()))
                .toList();
        return Optional.of(new MarketHistoryResponse(key, currency, series));
    }

    /** Whether {@link #ownHistory} can ever serve this range — used by MarketController to decide
     *  whether a backfill is worth attempting on a miss (1D/1W never are, needing intraday data
     *  this table doesn't keep). */
    public boolean supportsOwnHistory(String rangeKey) {
        return OWN_HISTORY_RANGE_DAYS.containsKey(rangeKey);
    }

    /** Persists every point of a live-fetched daily series into our own table in one batch — called
     *  by MarketController when {@link #ownHistory} comes up short, so a single live fetch (for
     *  whatever's the fullest span available, typically "1Y" regardless of what range the user
     *  actually asked for) immediately gives this symbol close to a full year of coverage for next
     *  time, rather than waiting on the 15-minute scheduler to build it up one day at a time. Same
     *  symbol+date upsert as {@link #recordHistory}, just batched over a whole series at once. */
    public void backfillHistory(String symbol, MarketHistoryResponse response) {
        if (symbol == null || symbol.isBlank() || response.points().isEmpty()) return;
        String key = symbol.trim().toUpperCase();
        Map<LocalDate, PriceHistoryPoint> existing = priceHistory.findBySymbol(key).stream()
                .collect(Collectors.toMap(PriceHistoryPoint::getPriceDate, p -> p, (a, b) -> a));
        Instant now = Instant.now();
        List<PriceHistoryPoint> rows = response.points().stream()
                .map(point -> {
                    LocalDate date = point.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
                    PriceHistoryPoint row = existing.getOrDefault(date, new PriceHistoryPoint());
                    row.setSymbol(key);
                    row.setPriceDate(date);
                    row.setPrice(point.price());
                    row.setCurrency(response.currency());
                    row.setUpdatedAt(now);
                    return row;
                })
                .toList();
        priceHistory.saveAll(rows);
        log.debug("Backfilled {} day(s) of history for '{}'", rows.size(), key);
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
