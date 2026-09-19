package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.WatchlistItem;
import com.finsights.portfolio.dto.WatchlistCreateRequest;
import com.finsights.portfolio.dto.WatchlistPriceRequest;
import com.finsights.portfolio.dto.WatchlistResponse;
import com.finsights.portfolio.dto.WatchlistUpdateRequest;
import com.finsights.portfolio.repository.WatchlistRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
    private final WatchlistRepository items;
    private final PriceSnapshotService snapshots;
    private final CurrentUserService currentUser;
    private final FxRateService fx;

    public WatchlistService(WatchlistRepository items, PriceSnapshotService snapshots, CurrentUserService currentUser, FxRateService fx) {
        this.items = items;
        this.snapshots = snapshots;
        this.currentUser = currentUser;
        this.fx = fx;
    }

    /** Native currency, unconverted — the basis for movement/threshold evaluation in Top movers. */
    public List<WatchlistResponse> list() {
        return items.findByUser_IdOrderByCreatedAtAsc(currentUser.currentUser().getId()).stream().map(this::toResponse).toList();
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
