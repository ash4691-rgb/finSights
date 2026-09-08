package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * {@code ratesToBase}: how many units of {@code base} one unit of each currency is worth
 * (e.g. base "INR", {"USD": 88.50} means 1 USD = ₹88.50).
 */
public record FxRatesResponse(String base, Instant asOf, Map<String, BigDecimal> ratesToBase, String note) { }
