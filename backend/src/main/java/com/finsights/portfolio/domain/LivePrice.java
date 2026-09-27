package com.finsights.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The last known live price for one ticker/scheme symbol — one row per symbol, shared across
 * every user and every holding/watchlist item that references it, refreshed by
 * {@link com.finsights.portfolio.service.LivePriceRefreshScheduler} at most every 15 minutes.
 * Reading a price is then a local lookup instead of a synchronous Yahoo/mfapi call on every
 * Holdings/Insights page load, and two users holding the same ticker share one fetch instead
 * of duplicating it (and doubling the odds of tripping Yahoo's rate limit).
 */
@Entity
@Table(name = "live_prices")
public class LivePrice {
    /** The ticker/scheme symbol itself (already namespaced, e.g. {@code RELIANCE.NS} or
     *  {@code MF:120503}, see {@link com.finsights.portfolio.service.MarketDataService}) —
     *  the natural key, so no separate id column. */
    @Id
    @Column(length = 24)
    private String symbol;
    @Column(nullable = false, length = 128)
    private String name;
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal price;
    @Column(nullable = false, length = 8)
    private String currency;
    @Column(nullable = false)
    private Instant fetchedAt;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
