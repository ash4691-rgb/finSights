package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.WatchlistResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Percentage price movement over a lookback window, for both holdings and watchlist items —
 * the basis for Insights' Hot picks. FIXED_RATE holdings are valued analytically (the compounding
 * formula gives an exact value for any past date); everything else is diffed against the closest
 * recorded {@link com.finsights.portfolio.domain.PriceSnapshot} at or before that date.
 */
@Service
public class MovementService {
    /** Lookback window, in days, per period key — insertion order drives display order everywhere. */
    public static final Map<String, Integer> PERIOD_DAYS = new LinkedHashMap<>();
    static {
        PERIOD_DAYS.put("DAILY", 1);
        PERIOD_DAYS.put("WEEKLY", 7);
        PERIOD_DAYS.put("MONTHLY", 30);
        PERIOD_DAYS.put("QUARTERLY", 90);
        PERIOD_DAYS.put("YEARLY", 365);
    }

    private final PriceSnapshotService snapshots;
    private final ValuationService valuations;

    public MovementService(PriceSnapshotService snapshots, ValuationService valuations) {
        this.snapshots = snapshots;
        this.valuations = valuations;
    }

    /** Null when there isn't enough history yet to say — never fabricated. */
    public BigDecimal holdingMovement(HoldingResponse holding, int lookbackDays) {
        BigDecimal now = holding.currentValue();
        BigDecimal then = isFixedRateReady(holding)
                ? valuations.compoundedValue(holding.investedValue(), holding.fixedAnnualRate(),
                        holding.compoundingFrequency(), holding.fixedRateStartDate(), holding.fixedRateEndDate(),
                        LocalDate.now().minusDays(lookbackDays))
                : snapshots.closestAtOrBefore(SnapshotSubject.HOLDING, holding.id(), cutoff(lookbackDays));
        return percentChange(then, now);
    }

    public BigDecimal watchlistMovement(WatchlistResponse item, int lookbackDays) {
        BigDecimal then = snapshots.closestAtOrBefore(SnapshotSubject.WATCHLIST, item.id(), cutoff(lookbackDays));
        return percentChange(then, item.currentValue());
    }

    public BigDecimal percentChange(BigDecimal then, BigDecimal now) {
        if (then == null || now == null || then.signum() == 0) return null;
        return now.subtract(then).divide(then, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isFixedRateReady(HoldingResponse holding) {
        return holding.valuationMethod() == ValuationMethod.FIXED_RATE
                && holding.fixedAnnualRate() != null && holding.fixedRateStartDate() != null && holding.compoundingFrequency() != null;
    }

    private Instant cutoff(int lookbackDays) {
        return Instant.now().minus(lookbackDays, ChronoUnit.DAYS);
    }
}
