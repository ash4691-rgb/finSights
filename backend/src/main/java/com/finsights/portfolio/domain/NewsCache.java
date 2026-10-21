package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A ticker's recent news headlines (Google News RSS search) — fetched once and shared across
 * every user, never per-account, same reasoning as {@link MarketHistoryCache}: tickers overlap
 * heavily across accounts, so one row per symbol serves everyone instead of a fetch per user.
 */
@Entity
@Table(name = "news_cache")
public class NewsCache {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(nullable = false, unique = true, length = 24)
    private String symbol;
    @Column(nullable = false)
    private Instant fetchedAt;
    /** JSON-serialized List<NewsService.Headline> — opaque here, same convention as MarketHistoryCache.pointsJson. */
    @Lob @Column(nullable = false)
    private String itemsJson;

    public String getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
    public String getItemsJson() { return itemsJson; }
    public void setItemsJson(String itemsJson) { this.itemsJson = itemsJson; }
}
