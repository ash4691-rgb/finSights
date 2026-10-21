package com.finsights.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One user's personal relationship to a ticker symbol, layered on top of the global tracked-symbol
 * pool (see {@link TrackedSymbol}) and shared price/news caches — starring/excluding a symbol is a
 * per-user preference, never a global one, same reasoning Watchlist already uses for what to track.
 * Starring a symbol boosts its news in {@link com.finsights.portfolio.service.NewsService#forUser};
 * excluding one hides its news from this user's Radar entirely, without touching the global pool
 * (someone else may still hold/watch it — see TrackedSymbolPoolService's own doc comment).
 */
@Entity
@Table(name = "ticker_preferences", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "symbol" }))
public class TickerPreference {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private UserAccount user;
    @Column(nullable = false, length = 24)
    private String symbol;
    @Column(nullable = false)
    private boolean starred;
    @Column(nullable = false)
    private boolean excluded;
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public boolean isStarred() { return starred; }
    public void setStarred(boolean starred) { this.starred = starred; }
    public boolean isExcluded() { return excluded; }
    public void setExcluded(boolean excluded) { this.excluded = excluded; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
