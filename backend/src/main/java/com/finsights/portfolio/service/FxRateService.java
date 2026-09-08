package com.finsights.portfolio.service;

import com.finsights.portfolio.dto.FxRatesResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Converts between currencies for display purposes only. Rates below are a static,
 * hand-maintained reference table — NOT a live market feed. Swap this class for a real
 * FX provider before relying on it for anything beyond an approximate view; every
 * response carries {@link FxRatesResponse#asOf()} and a disclaimer so the UI can say so.
 */
@Service
public class FxRateService {

    private static final Instant AS_OF = Instant.parse("2026-09-04T00:00:00Z");
    private static final String NOTE = "Static reference rates for display only — not a live market feed. "
            + "Update FxRateService.RATES_TO_INR (or wire in a live provider) to refresh them.";

    // INR value of one unit of the given currency.
    private static final Map<String, BigDecimal> RATES_TO_INR = Map.of(
            "INR", BigDecimal.ONE,
            "USD", new BigDecimal("88.50"),
            "EUR", new BigDecimal("95.00"),
            "GBP", new BigDecimal("111.00"),
            "SGD", new BigDecimal("66.00"),
            "AED", new BigDecimal("24.10"));

    public FxRatesResponse rates() {
        Map<String, BigDecimal> ordered = new LinkedHashMap<>();
        RATES_TO_INR.keySet().stream().sorted().forEach(c -> ordered.put(c, RATES_TO_INR.get(c)));
        return new FxRatesResponse("INR", AS_OF, ordered, NOTE);
    }

    public boolean supports(String currency) {
        return currency != null && RATES_TO_INR.containsKey(normalize(currency));
    }

    public BigDecimal convert(BigDecimal amount, String from, String to) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        String f = normalize(from), t = normalize(to);
        if (f.equals(t)) return value;
        BigDecimal inrValue = value.multiply(rateToInr(f), MathContext.DECIMAL64);
        return inrValue.divide(rateToInr(t), 2, RoundingMode.HALF_UP);
    }

    /** Returns a copy of {@code holding} re-expressed in {@code targetCurrency}. P&amp;L % is currency-invariant. */
    public HoldingResponse convert(HoldingResponse holding, String targetCurrency) {
        String target = normalize(targetCurrency);
        if (target.equals(normalize(holding.currency()))) return holding;
        BigDecimal invested = convert(holding.investedValue(), holding.currency(), target);
        BigDecimal current = convert(holding.currentValue(), holding.currency(), target);
        BigDecimal realised = convert(holding.realisedProfitLoss(), holding.currency(), target);
        BigDecimal emi = holding.emiAmount() == null ? null : convert(holding.emiAmount(), holding.currency(), target);
        return new HoldingResponse(
                holding.id(), holding.holdingId(), holding.categoryId(), holding.categoryName(), holding.name(), holding.kind(), holding.valuationMethod(),
                holding.tickerSymbol(), holding.broker(), target,
                invested, current, current.subtract(invested), holding.profitLossPercentage(), realised,
                holding.quantity(), holding.fixedAnnualRate(), holding.compoundingFrequency(),
                holding.fixedRateStartDate(), holding.fixedRateEndDate(), emi, holding.emiDayOfMonth(),
                holding.liquidWithinSevenDays(), holding.blocked(), holding.description(), holding.notes(),
                holding.tags(), holding.createdAt(), holding.updatedAt(), holding.priceUpdatedAt());
    }

    private BigDecimal rateToInr(String currency) {
        BigDecimal rate = RATES_TO_INR.get(currency);
        if (rate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported currency: " + currency
                    + ". Supported: " + String.join(", ", RATES_TO_INR.keySet()));
        }
        return rate;
    }

    private String normalize(String currency) {
        return currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase();
    }
}
