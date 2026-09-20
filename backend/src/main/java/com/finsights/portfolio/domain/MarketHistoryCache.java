package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A ticker's daily closing-price series (Yahoo's 1-year, 1-day-interval chart) — fetched once and
 * shared across every user, never per-account. Tickers overlap heavily across accounts, and the
 * 1M/3M/6M/1Y windows in ViewMoverItem's price graph are all just different-length slices of this
 * same series, so one row per symbol serves all of them instead of a Yahoo call per user per range.
 */
@Entity
@Table(name = "market_history_cache")
public class MarketHistoryCache {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(nullable = false, unique = true, length = 24)
    private String symbol;
    @Column(length = 8)
    private String currency;
    @Column(nullable = false)
    private Instant fetchedAt;
    /** JSON-serialized List<MarketHistoryResponse.Point> — opaque here, same convention as DashboardLayout.config. */
    @Lob @Column(nullable = false)
    private String pointsJson;

    public String getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
    public String getPointsJson() { return pointsJson; }
    public void setPointsJson(String pointsJson) { this.pointsJson = pointsJson; }
}
