package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A symbol's closing price over a lookback window, oldest point first — backs the price graph in ViewMoverItem. */
public record MarketHistoryResponse(String symbol, String currency, List<Point> points) {
    public record Point(Instant timestamp, BigDecimal price) { }
}
