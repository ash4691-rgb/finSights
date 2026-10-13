package com.finsights.portfolio.dto;

import java.time.Instant;

/** One news headline matched to a market-linked holding or watchlist item, for Signals' Radar.
 * Mirrors {@link TopMoverResponse}'s HOLDING/WATCHLIST shape. */
public record NewsItemResponse(
        String subjectType, // "HOLDING" | "WATCHLIST"
        String id,
        String name,
        String tickerSymbol,
        String title,
        String link,
        String source,
        Instant publishedAt
) { }
