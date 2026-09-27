package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.WatchlistItem;
import com.finsights.portfolio.dto.MarketHistoryResponse;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.dto.WatchlistCreateRequest;
import com.finsights.portfolio.dto.WatchlistPriceRequest;
import com.finsights.portfolio.dto.WatchlistResponse;
import com.finsights.portfolio.dto.WatchlistUpdateRequest;
import com.finsights.portfolio.repository.WatchlistRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Symbols the user is watching for Hot picks without owning them as a holding. Each item's price
 * trail lives in {@link com.finsights.portfolio.domain.PriceSnapshot} rows via {@link PriceSnapshotService}.
 * A user may not track the same ticker symbol twice.
 */
@Service
public class WatchlistService {
    /** How far back a historical backfill needs to already reach before it's considered done. */
    private static final Duration DEEP_HISTORY_WINDOW = Duration.ofDays(350);

    private final WatchlistRepository items;
    private final PriceSnapshotService snapshots;
    private final CurrentUserService currentUser;
    private final FxRateService fx;
    private final MarketDataService marketData;
    private final LivePriceService livePrices;

    public WatchlistService(WatchlistRepository items, PriceSnapshotService snapshots, CurrentUserService currentUser,
                             FxRateService fx, MarketDataService marketData, LivePriceService livePrices) {
        this.items = items;
        this.snapshots = snapshots;
        this.currentUser = currentUser;
        this.fx = fx;
        this.marketData = marketData;
        this.livePrices = livePrices;
    }

    /** Native currency, unconverted — the basis for movement/threshold evaluation in Top movers. */
    @Transactional
    public List<WatchlistResponse> list() {
        List<WatchlistItem> owned = items.findByUser_IdOrderByCreatedAtAsc(currentUser.currentUser().getId());
        refreshLivePrices(owned);
        return owned.stream().map(this::toResponse).toList();
    }

    /**
     * Re-prices every ticker-bearing item from the shared {@link LivePriceService} cache — a
     * local DB lookup, not a Yahoo call, so this runs on every watchlist load without adding
     * external-network latency to it. A new snapshot is only recorded when the cached price is
     * actually newer than the item's last one — not on every single load — which naturally
     * paces snapshots to the cache's own ~15-minute refresh cadence.
     */
    private void refreshLivePrices(List<WatchlistItem> owned) {
        List<WatchlistItem> tickerBearing = owned.stream()
                .filter(w -> w.getTickerSymbol() != null && !w.getTickerSymbol().isBlank())
                .toList();
        if (tickerBearing.isEmpty()) return;

        Map<String, MarketQuoteResponse> quotes = livePrices.getPrices(tickerBearing.stream()
                .map(w -> w.getTickerSymbol().trim().toUpperCase())
                .collect(Collectors.toSet()));
        for (WatchlistItem item : tickerBearing) {
            MarketQuoteResponse quote = quotes.get(item.getTickerSymbol().trim().toUpperCase());
            if (quote == null || quote.price() == null || quote.price().signum() <= 0) continue;
            Instant asOf = quote.asOf() != null ? quote.asOf() : Instant.now();
            Instant last = snapshots.latestRecordedAt(SnapshotSubject.WATCHLIST, item.getId());
            if (last != null && !last.isBefore(asOf)) continue;
            String currency = item.getCurrency() != null ? item.getCurrency() : quote.currency();
            if (item.getCurrency() == null) {
                item.setCurrency(currency);
                items.save(item);
            }
            BigDecimal value = convertToItemCurrency(quote.price(), quote.currency(), currency);
            snapshots.record(SnapshotSubject.WATCHLIST, item.getId(), item.getUser(), value);
            backfillHistoryIfNeeded(item, currency);
        }
    }

    /**
     * One-time backfill of real historical closes (Yahoo's 1-year chart) so monthly, quarterly,
     * half-yearly, and yearly thresholds have a genuine baseline right away instead of only after
     * months of live use — never fabricated, just fetched earlier. Skipped once a snapshot already
     * exists that old; a fetch failure is swallowed and simply retried on the next refresh.
     */
    private void backfillHistoryIfNeeded(WatchlistItem item, String currency) {
        Instant cutoff = Instant.now().minus(DEEP_HISTORY_WINDOW);
        if (snapshots.hasSnapshotAtOrBefore(SnapshotSubject.WATCHLIST, item.getId(), cutoff)) return;
        marketData.history(item.getTickerSymbol(), "1Y").ifPresent(history -> {
            for (MarketHistoryResponse.Point point : history.points()) {
                BigDecimal value = convertToItemCurrency(point.price(), history.currency(), currency);
                snapshots.recordAt(SnapshotSubject.WATCHLIST, item.getId(), item.getUser(), value, point.timestamp());
            }
        });
    }

    private BigDecimal convertToItemCurrency(BigDecimal amount, String from, String to) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) return amount;
        try {
            return fx.convert(amount, from, to);
        } catch (RuntimeException e) {
            return amount; // unsupported currency pair — treat the quote as already in the item's currency
        }
    }

    /** As {@link #list()}, but each item's current value is converted to {@code displayCurrency}
     * (a blank/null currency is a no-op — same as {@link #list()}). */
    public List<WatchlistResponse> list(String displayCurrency) {
        if (displayCurrency == null || displayCurrency.isBlank()) return list();
        return list().stream().map(w -> convert(w, displayCurrency)).toList();
    }

    public WatchlistResponse create(WatchlistCreateRequest request) {
        UserAccount user = currentUser.currentUser();
        String ticker = clean(request.tickerSymbol());
        rejectDuplicate(user.getId(), ticker, null);
        WatchlistItem item = new WatchlistItem();
        item.setUser(user);
        item.setName(request.name().trim());
        item.setTickerSymbol(ticker);
        item.setCurrency(clean(request.currency()));
        item.setNotes(clean(request.notes()));
        WatchlistItem saved = items.save(item);
        snapshots.record(SnapshotSubject.WATCHLIST, saved.getId(), saved.getUser(), request.price());
        return toResponse(saved);
    }

    public WatchlistResponse update(String id, WatchlistUpdateRequest request) {
        WatchlistItem item = findOwned(id);
        String ticker = clean(request.tickerSymbol());
        rejectDuplicate(item.getUser().getId(), ticker, id);
        item.setName(request.name().trim());
        item.setTickerSymbol(ticker);
        item.setCurrency(clean(request.currency()));
        item.setNotes(clean(request.notes()));
        return toResponse(items.save(item));
    }

    public WatchlistResponse recordPrice(String id, WatchlistPriceRequest request) {
        WatchlistItem item = findOwned(id);
        snapshots.record(SnapshotSubject.WATCHLIST, item.getId(), item.getUser(), request.price());
        return toResponse(item);
    }

    @Transactional
    public void delete(String id) {
        WatchlistItem item = findOwned(id);
        snapshots.deleteFor(SnapshotSubject.WATCHLIST, item.getId());
        items.delete(item);
    }

    private void rejectDuplicate(String userId, String tickerSymbol, String excludingId) {
        if (tickerSymbol == null) return;
        boolean duplicate = excludingId == null
                ? items.existsByUser_IdAndTickerSymbolIgnoreCase(userId, tickerSymbol)
                : items.existsByUser_IdAndTickerSymbolIgnoreCaseAndIdNot(userId, tickerSymbol, excludingId);
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "\"" + tickerSymbol + "\" is already on your watchlist");
        }
    }

    private WatchlistItem findOwned(String id) {
        return items.findByIdAndUser_Id(id, currentUser.currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Watchlist item not found"));
    }

    private WatchlistResponse convert(WatchlistResponse w, String displayCurrency) {
        if (w.currentValue() == null || w.currency() == null) return w;
        BigDecimal shown = fx.convert(w.currentValue(), w.currency(), displayCurrency);
        return new WatchlistResponse(w.id(), w.name(), w.tickerSymbol(), w.notes(), shown, displayCurrency.trim().toUpperCase(), w.lastUpdated(), w.createdAt());
    }

    private WatchlistResponse toResponse(WatchlistItem item) {
        BigDecimal current = snapshots.latest(SnapshotSubject.WATCHLIST, item.getId());
        Instant lastUpdated = snapshots.latestRecordedAt(SnapshotSubject.WATCHLIST, item.getId());
        return new WatchlistResponse(item.getId(), item.getName(), item.getTickerSymbol(), item.getNotes(),
                current, item.getCurrency(), lastUpdated, item.getCreatedAt());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
