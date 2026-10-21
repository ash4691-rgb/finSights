package com.finsights.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One symbol's closing-ish price for one calendar day — at most one row per (symbol, date),
 * upserted by {@link com.finsights.portfolio.service.LivePriceRefreshScheduler}'s run on that day
 * and pruned past a year old. Deliberately daily granularity only ("minimal required data points"),
 * not one row per 15-minute refresh — a year of 15-minute ticks per symbol would be ~35,000 rows
 * each; a year of daily points is ~365. {@link com.finsights.portfolio.service.LivePriceService#ownHistory}
 * serves the 1M/3M/6M/1Y chart ranges straight from this table once enough back-history has
 * accumulated, instead of a Yahoo chart call — 1D/1W stay Yahoo-only since they need intraday
 * granularity this table doesn't keep.
 */
@Entity
@Table(name = "price_history_points", uniqueConstraints = @UniqueConstraint(columnNames = { "symbol", "price_date" }))
public class PriceHistoryPoint {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(nullable = false, length = 24)
    private String symbol;
    @Column(name = "price_date", nullable = false)
    private LocalDate priceDate;
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal price;
    @Column(length = 8)
    private String currency;
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public LocalDate getPriceDate() { return priceDate; }
    public void setPriceDate(LocalDate priceDate) { this.priceDate = priceDate; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
