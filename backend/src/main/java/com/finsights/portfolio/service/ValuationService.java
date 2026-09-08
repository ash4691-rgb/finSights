package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.CompoundingFrequency;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ValuationDetailResponse;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ValuationService {

    private static final double DAYS_PER_YEAR = 365.25d;

    public BigDecimal currentValue(Holding holding) {
        if (!isFixedRate(holding)) {
            return zeroIfNull(holding.getCurrentValue());
        }
        return compoundedValue(holding.getInvestedValue(), holding.getFixedAnnualRate(),
                holding.getCompoundingFrequency(), holding.getFixedRateStartDate(),
                holding.getFixedRateEndDate(), LocalDate.now());
    }

    /** After a fixed-rate holding's maturity date it stops accruing, so value it as of that date. */
    private static LocalDate cap(LocalDate asOf, LocalDate maturity) {
        return maturity != null && asOf.isAfter(maturity) ? maturity : asOf;
    }

    /**
     * Compound-interest value of {@code principal} as of {@code asOf}, given an annual rate and
     * compounding frequency starting on {@code start}. Shared by {@link #currentValue} (asOf = today)
     * and {@link MovementService}, which calls it with past dates to compute Hot picks movement for
     * FIXED_RATE holdings analytically instead of from stored snapshots.
     */
    public BigDecimal compoundedValue(BigDecimal principal, BigDecimal annualRate, CompoundingFrequency frequency,
                                       LocalDate start, LocalDate asOf) {
        return compoundedValue(principal, annualRate, frequency, start, null, asOf);
    }

    public BigDecimal compoundedValue(BigDecimal principal, BigDecimal annualRate, CompoundingFrequency frequency,
                                       LocalDate start, LocalDate maturity, LocalDate asOf) {
        asOf = cap(asOf, maturity);
        int periodsPerYear = frequency.periodsPerYear();
        long completedPeriods = completedPeriods(start, periodsPerYear, asOf);
        double periodicRate = annualRate.doubleValue() / periodsPerYear;
        double factor = Math.pow(1d + periodicRate, completedPeriods);
        return zeroIfNull(principal).multiply(BigDecimal.valueOf(factor), MathContext.DECIMAL64).setScale(2, RoundingMode.HALF_UP);
    }

    public ValuationDetailResponse explain(Holding holding) {
        BigDecimal invested = zeroIfNull(holding.getInvestedValue());
        BigDecimal current = currentValue(holding);
        BigDecimal pnl = current.subtract(invested);
        BigDecimal pnlPct = invested.signum() == 0 ? BigDecimal.ZERO
                : pnl.divide(invested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        List<String> steps = new ArrayList<>();
        LocalDate maturityDate = null;
        BigDecimal maturityValue = null;

        switch (holding.getValuationMethod()) {
            case MANUAL -> {
                steps.add("Current value entered manually: " + current.toPlainString());
                steps.add("Last updated " + holding.getUpdatedAt());
            }
            case MARKET_PRICE -> {
                boolean hasTicker = holding.getTickerSymbol() != null && !holding.getTickerSymbol().isBlank();
                if (hasTicker && holding.getPriceUpdatedAt() != null) {
                    steps.add("Ticker " + holding.getTickerSymbol() + " — last priced from the live feed at "
                            + holding.getPriceUpdatedAt());
                    if (holding.getQuantity() != null) {
                        steps.add("Quantity " + holding.getQuantity().toPlainString()
                                + " × latest price → " + current.toPlainString() + " " + holding.getCurrency());
                    }
                    steps.add("Refreshes each time you open the Holdings page (at most every 15 minutes).");
                } else if (hasTicker) {
                    steps.add("Ticker " + holding.getTickerSymbol()
                            + " on file — open the Holdings page to pull the first live price.");
                    steps.add("Current value held at its last manual figure: " + current.toPlainString());
                } else {
                    steps.add("Marked to the last entered market value: " + current.toPlainString());
                    steps.add("Add a ticker symbol to have this priced from the live feed automatically.");
                }
            }
            case BROKER_SYNC -> {
                steps.add("Value last received from broker sync: " + current.toPlainString());
                steps.add("Broker: " + (holding.getBroker() == null ? "unassigned" : holding.getBroker()));
                steps.add("Live broker sync is not connected yet; the value is held at its last known figure.");
            }
            case FIXED_RATE -> {
                if (isFixedRate(holding)) {
                    int ppy = holding.getCompoundingFrequency().periodsPerYear();
                    long periods = completedPeriods(holding.getFixedRateStartDate(), ppy, LocalDate.now());
                    long days = Math.max(0, ChronoUnit.DAYS.between(holding.getFixedRateStartDate(), LocalDate.now()));
                    double periodicRate = holding.getFixedAnnualRate().doubleValue() / ppy;
                    double factor = Math.pow(1d + periodicRate, periods);
                    steps.add("Principal: " + invested.toPlainString());
                    steps.add("Annual rate: " + pct(holding.getFixedAnnualRate()));
                    steps.add("Compounding: " + holding.getCompoundingFrequency() + " (" + ppy + " periods/year)");
                    steps.add("Start date: " + holding.getFixedRateStartDate() + " → " + days + " days elapsed = "
                            + periods + " completed period(s)");
                    steps.add("Growth factor: (1 + " + round(periodicRate, 6) + ") ^ " + periods + " = " + round(factor, 6));
                    steps.add("Current value = principal × growth factor = " + current.toPlainString());
                    maturityDate = LocalDate.now().plusYears(1);
                    maturityValue = current.multiply(
                            BigDecimal.valueOf(Math.pow(1d + periodicRate, ppy)), MathContext.DECIMAL64)
                            .setScale(2, RoundingMode.HALF_UP);
                } else {
                    steps.add("Fixed-rate inputs are incomplete; falling back to the stored current value.");
                }
            }
        }
        return new ValuationDetailResponse(holding.getValuationMethod(), invested, current, pnl, pnlPct,
                steps, maturityDate, maturityValue);
    }

    private long completedPeriods(LocalDate start, int periodsPerYear, LocalDate asOf) {
        long days = Math.max(0, ChronoUnit.DAYS.between(start, asOf));
        return (long) Math.floor(days / (DAYS_PER_YEAR / periodsPerYear));
    }

    private boolean isFixedRate(Holding holding) {
        return holding.getValuationMethod() == ValuationMethod.FIXED_RATE
                && holding.getFixedAnnualRate() != null
                && holding.getFixedRateStartDate() != null
                && holding.getCompoundingFrequency() != null;
    }

    private String pct(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    private double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
