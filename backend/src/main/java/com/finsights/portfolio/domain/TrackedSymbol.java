package com.finsights.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The global pool of ticker/scheme symbols worth keeping priced and newsed — every symbol
 * currently held (MARKET_PRICE) or watchlisted by ANY user, independent of who added it. Single
 * source of truth both {@link com.finsights.portfolio.service.LivePriceRefreshScheduler} and
 * {@link com.finsights.portfolio.service.NewsRefreshScheduler} read from, kept in sync by
 * {@link com.finsights.portfolio.service.TrackedSymbolPoolService#sync()} at the top of each run,
 * instead of each scheduler separately unioning HoldingRepository/WatchlistRepository itself.
 */
@Entity
@Table(name = "tracked_symbols")
public class TrackedSymbol {
    @Id
    @Column(length = 24)
    private String symbol;
    @Column(nullable = false)
    private Instant firstTrackedAt = Instant.now();

    public TrackedSymbol() { }
    public TrackedSymbol(String symbol) { this.symbol = symbol; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public Instant getFirstTrackedAt() { return firstTrackedAt; }
    public void setFirstTrackedAt(Instant firstTrackedAt) { this.firstTrackedAt = firstTrackedAt; }
}
