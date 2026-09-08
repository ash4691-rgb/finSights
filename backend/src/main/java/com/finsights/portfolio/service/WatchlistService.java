package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.SnapshotSubject;
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
 */
@Service
public class WatchlistService {
    private final WatchlistRepository items;
    private final PriceSnapshotService snapshots;
    private final CurrentUserService currentUser;

    public WatchlistService(WatchlistRepository items, PriceSnapshotService snapshots, CurrentUserService currentUser) {
        this.items = items;
        this.snapshots = snapshots;
        this.currentUser = currentUser;
    }

    public List<WatchlistResponse> list() {
        return items.findByUser_IdOrderByCreatedAtAsc(currentUser.currentUser().getId()).stream().map(this::toResponse).toList();
    }

    public WatchlistResponse create(WatchlistCreateRequest request) {
        WatchlistItem item = new WatchlistItem();
        item.setUser(currentUser.currentUser());
        item.setName(request.name().trim());
        item.setTickerSymbol(clean(request.tickerSymbol()));
        item.setNotes(clean(request.notes()));
        WatchlistItem saved = items.save(item);
        snapshots.record(SnapshotSubject.WATCHLIST, saved.getId(), saved.getUser(), request.price());
        return toResponse(saved);
    }

    public WatchlistResponse update(String id, WatchlistUpdateRequest request) {
        WatchlistItem item = findOwned(id);
        item.setName(request.name().trim());
        item.setTickerSymbol(clean(request.tickerSymbol()));
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

    private WatchlistItem findOwned(String id) {
        return items.findByIdAndUser_Id(id, currentUser.currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Watchlist item not found"));
    }

    private WatchlistResponse toResponse(WatchlistItem item) {
        BigDecimal current = snapshots.latest(SnapshotSubject.WATCHLIST, item.getId());
        Instant lastUpdated = snapshots.latestRecordedAt(SnapshotSubject.WATCHLIST, item.getId());
        return new WatchlistResponse(item.getId(), item.getName(), item.getTickerSymbol(), item.getNotes(),
                current, lastUpdated, item.getCreatedAt());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
