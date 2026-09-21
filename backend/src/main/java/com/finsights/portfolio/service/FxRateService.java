package com.finsights.portfolio.service;

import com.finsights.portfolio.dto.FxRatesResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Converts between currencies for display purposes only. INR crosses for every supported
 * currency are pulled from the same live, best-effort feed {@link MarketDataService} already
 * uses for holding prices, refreshed at most every {@link #RATE_MAX_AGE} — the same ~15-30
 * minute staleness the rest of the app already accepts for market-linked assets (see
 * {@code HoldingService.PRICE_MAX_AGE}). A currency the feed can't reach on a given refresh
 * just keeps its last known-good rate (seeded from a hand-maintained fallback table on a cold
 * start) instead of failing — a dead feed degrades the numbers shown, never the app. Every
 * response carries {@link FxRatesResponse#asOf()} so the UI can say how fresh the rates are.
 */
@Service
public class FxRateService {

    /** INR value of one unit of the given currency — used to seed the live cache and as its
     *  floor when the live feed has never returned a quote for that currency. */
    private static final Map<String, BigDecimal> FALLBACK_RATES_TO_INR = Map.of(
            "INR", BigDecimal.ONE,
            "USD", new BigDecimal("88.50"),
            "EUR", new BigDecimal("95.00"),
            "GBP", new BigDecimal("111.00"),
            "SGD", new BigDecimal("66.00"),
            "AED", new BigDecimal("24.10"));

    // Yahoo Finance's symbol for "one unit of this currency, priced in INR" — the same
    // "=X" cross-rate convention as any other quote MarketDataService already fetches.
    private static final Map<String, String> CURRENCY_TICKERS = Map.of(
            "USD", "USDINR=X",
            "EUR", "EURINR=X",
            "GBP", "GBPINR=X",
            "SGD", "SGDINR=X",
            "AED", "AEDINR=X");

    /** Live rates are re-fetched no more often than this — mirrors {@code HoldingService}'s
     *  own market-price refresh window. */
    private static final Duration RATE_MAX_AGE = Duration.ofMinutes(20);

    private final MarketDataService marketData;
    private final Map<String, BigDecimal> liveRatesToInr = new ConcurrentHashMap<>(FALLBACK_RATES_TO_INR);
    private final AtomicReference<Instant> lastRefreshedAt = new AtomicReference<>();

    public FxRateService(MarketDataService marketData) {
        this.marketData = marketData;
    }

    public FxRatesResponse rates() {
        refreshIfStale();
        Map<String, BigDecimal> ordered = new LinkedHashMap<>();
        liveRatesToInr.keySet().stream().sorted().forEach(c -> ordered.put(c, liveRatesToInr.get(c)));
        String note = "Rates refresh from a live market feed at most every " + RATE_MAX_AGE.toMinutes()
                + " minutes; a currency the feed can't reach keeps its last known rate.";
        return new FxRatesResponse("INR", lastRefreshedAt.get(), ordered, note);
    }

    public boolean supports(String currency) {
        return currency != null && FALLBACK_RATES_TO_INR.containsKey(normalize(currency));
    }

    public BigDecimal convert(BigDecimal amount, String from, String to) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        String f = normalize(from), t = normalize(to);
        if (f.equals(t)) return value;
        BigDecimal inrValue = value.multiply(rateToInr(f), MathContext.DECIMAL64);
        return inrValue.divide(rateToInr(t), 2, RoundingMode.HALF_UP);
    }

    /** Returns a copy of {@code holding} re-expressed in {@code targetCurrency}. P&amp;L % is currency-invariant.
     *  An unsupported currency on this one holding (bad/legacy data) never fails the whole list — it's
     *  returned unconverted and flagged instead. */
    public HoldingResponse convert(HoldingResponse holding, String targetCurrency) {
        String target = normalize(targetCurrency);
        if (target.equals(normalize(holding.currency()))) return holding;
        if (!supports(holding.currency()) || !supports(target)) {
            return holding.withDataIssue("Couldn't convert this holding's currency (" + holding.currency()
                    + ") — showing its original value instead of " + target + ".");
        }
        BigDecimal invested = convert(holding.investedValue(), holding.currency(), target);
        BigDecimal current = convert(holding.currentValue(), holding.currency(), target);
        BigDecimal realised = convert(holding.realisedProfitLoss(), holding.currency(), target);
        BigDecimal emi = holding.emiAmount() == null ? null : convert(holding.emiAmount(), holding.currency(), target);
        return new HoldingResponse(
                holding.id(), holding.holdingId(), holding.categoryId(), holding.categoryName(), holding.name(), holding.kind(), holding.valuationMethod(),
                holding.tickerSymbol(), holding.broker(), target, holding.defaultCurrency(),
                invested, current, current.subtract(invested), holding.profitLossPercentage(), realised,
                convert(holding.accruedIncome(), holding.currency(), target),
                holding.quantity(), holding.fixedAnnualRate(), holding.compoundingFrequency(),
                holding.fixedRateStartDate(), holding.fixedRateEndDate(),
                holding.repaymentFrequency(), emi, holding.emiDayOfMonth(), holding.loanTermMonths(), holding.repaymentDueDate(),
                holding.liquidWithinSevenDays(), holding.blocked(), holding.description(), holding.notes(),
                holding.tags(), holding.createdAt(), holding.updatedAt(), holding.priceUpdatedAt(),
                holding.dataIssue(), holding.dataIssueMessage());
    }

    /**
     * Re-fetches INR crosses for every supported currency from the live feed, no more often
     * than {@link #RATE_MAX_AGE}. A currency whose quote can't be fetched this round simply
     * keeps whatever rate it already had (live or fallback) — the feed being down never blocks
     * a caller, it only means the shown rate is up to {@link #RATE_MAX_AGE} plus one refresh
     * cycle older than live.
     */
    private synchronized void refreshIfStale() {
        Instant last = lastRefreshedAt.get();
        if (last != null && last.plus(RATE_MAX_AGE).isAfter(Instant.now())) return;
        Map<String, MarketQuoteResponse> quotes = marketData.quotes(CURRENCY_TICKERS.values());
        for (Map.Entry<String, String> entry : CURRENCY_TICKERS.entrySet()) {
            MarketQuoteResponse quote = quotes.get(entry.getValue());
            if (quote != null && quote.price() != null && quote.price().signum() > 0) {
                liveRatesToInr.put(entry.getKey(), quote.price());
            }
        }
        lastRefreshedAt.set(Instant.now());
    }

    private BigDecimal rateToInr(String currency) {
        refreshIfStale();
        BigDecimal rate = liveRatesToInr.get(currency);
        if (rate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported currency: " + currency
                    + ". Supported: " + String.join(", ", FALLBACK_RATES_TO_INR.keySet()));
        }
        return rate;
    }

    private String normalize(String currency) {
        return currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase();
    }
}
