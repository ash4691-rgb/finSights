package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** The latest price the market-data feed returned for a symbol. */
public record MarketQuoteResponse(String symbol, String name, BigDecimal price, String currency, Instant asOf) { }
