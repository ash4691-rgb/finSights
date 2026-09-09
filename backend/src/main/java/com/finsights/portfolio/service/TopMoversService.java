package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.MovementThresholdResponse;
import com.finsights.portfolio.dto.TopMoverResponse;
import com.finsights.portfolio.dto.TopMoverResponse.PeriodMovement;
import com.finsights.portfolio.dto.WatchlistResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.springframework.stereotype.Service;

/**
 * Market-linked holdings and watchlist items whose price movement, over at least one configured
 * lookback window (daily/weekly/monthly/quarterly/yearly — see {@link MovementService#PERIOD_DAYS}),
 * exceeds that period's up or down threshold. An uptrend is checked against the up threshold, a
 * downtrend against the down threshold — either can be configured independently, or left off.
 * Movement itself is always computed in the holding's native currency (percentages are
 * unit-invariant); only the displayed current value is converted.
 */
@Service
public class TopMoversService {
    private final HoldingService holdings;
    private final WatchlistService watchlist;
    private final MovementService movements;
    private final MovementThresholdService thresholds;
    private final FxRateService fx;

    public TopMoversService(HoldingService holdings, WatchlistService watchlist, MovementService movements,
                             MovementThresholdService thresholds, FxRateService fx) {
        this.holdings = holdings;
        this.watchlist = watchlist;
        this.movements = movements;
        this.thresholds = thresholds;
        this.fx = fx;
    }

    public List<TopMoverResponse> topMovers(String displayCurrency) {
        MovementThresholdResponse cfg = thresholds.get();
        Map<String, BigDecimal> up = new LinkedHashMap<>();
        up.put("DAILY", cfg.dailyUpPercent());
        up.put("WEEKLY", cfg.weeklyUpPercent());
        up.put("MONTHLY", cfg.monthlyUpPercent());
        up.put("QUARTERLY", cfg.quarterlyUpPercent());
        up.put("YEARLY", cfg.yearlyUpPercent());
        Map<String, BigDecimal> down = new LinkedHashMap<>();
        down.put("DAILY", cfg.dailyDownPercent());
        down.put("WEEKLY", cfg.weeklyDownPercent());
        down.put("MONTHLY", cfg.monthlyDownPercent());
        down.put("QUARTERLY", cfg.quarterlyDownPercent());
        down.put("YEARLY", cfg.yearlyDownPercent());

        List<TopMoverResponse> results = new ArrayList<>();
        for (HoldingResponse h : holdings.list()) {
            if (h.kind() == HoldingKind.LIABILITY) continue; // movement/threshold tracking is for assets
            if (!isMarketLinked(h.valuationMethod())) continue; // Top movers only tracks live market pricing
            List<PeriodMovement> triggered = evaluate(up, down, days -> movements.holdingMovement(h, days));
            if (!triggered.isEmpty()) {
                BigDecimal shown = displayCurrency == null || displayCurrency.isBlank()
                        ? h.currentValue() : fx.convert(h.currentValue(), h.currency(), displayCurrency);
                String currency = displayCurrency == null || displayCurrency.isBlank() ? h.currency() : displayCurrency.trim().toUpperCase();
                results.add(new TopMoverResponse("HOLDING", h.id(), h.name(), h.categoryName(), h.tickerSymbol(), shown, currency, triggered));
            }
        }
        for (WatchlistResponse w : watchlist.list()) {
            List<PeriodMovement> triggered = evaluate(up, down, days -> movements.watchlistMovement(w, days));
            if (!triggered.isEmpty()) {
                results.add(new TopMoverResponse("WATCHLIST", w.id(), w.name(), null, w.tickerSymbol(), w.currentValue(), null, triggered));
            }
        }
        return results;
    }

    private boolean isMarketLinked(ValuationMethod method) {
        return method == ValuationMethod.MARKET_PRICE || method == ValuationMethod.BROKER_SYNC;
    }

    private List<PeriodMovement> evaluate(Map<String, BigDecimal> up, Map<String, BigDecimal> down, IntFunction<BigDecimal> movementForDays) {
        List<PeriodMovement> triggered = new ArrayList<>();
        for (Map.Entry<String, Integer> period : MovementService.PERIOD_DAYS.entrySet()) {
            BigDecimal percent = movementForDays.apply(period.getValue());
            if (percent == null) continue; // not enough history yet — never fabricate
            BigDecimal threshold = percent.signum() >= 0 ? up.get(period.getKey()) : down.get(period.getKey());
            if (threshold == null) continue; // that direction isn't configured for this period — skip
            if (percent.abs().compareTo(threshold) >= 0) {
                triggered.add(new PeriodMovement(period.getKey(), percent, threshold));
            }
        }
        return triggered;
    }
}
