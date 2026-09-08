package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.HotPickResponse;
import com.finsights.portfolio.dto.HotPickResponse.PeriodMovement;
import com.finsights.portfolio.dto.WatchlistResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.springframework.stereotype.Service;

/**
 * Holdings and watchlist items whose price movement, over at least one configured lookback window
 * (daily/weekly/monthly/quarterly/yearly — see {@link MovementService#PERIOD_DAYS}), exceeds that
 * period's threshold from Settings. Movement itself is always computed in the holding's native
 * currency (percentages are unit-invariant); only the displayed current value is converted.
 */
@Service
public class HotPicksService {
    private final HoldingService holdings;
    private final WatchlistService watchlist;
    private final MovementService movements;
    private final FxRateService fx;
    private final CurrentUserService currentUser;

    public HotPicksService(HoldingService holdings, WatchlistService watchlist, MovementService movements,
                           FxRateService fx, CurrentUserService currentUser) {
        this.holdings = holdings;
        this.watchlist = watchlist;
        this.movements = movements;
        this.fx = fx;
        this.currentUser = currentUser;
    }

    public List<HotPickResponse> hotPicks(String displayCurrency) {
        UserAccount user = currentUser.currentUser();
        Map<String, BigDecimal> thresholds = new LinkedHashMap<>();
        thresholds.put("DAILY", user.getDailyThresholdPercent());
        thresholds.put("WEEKLY", user.getWeeklyThresholdPercent());
        thresholds.put("MONTHLY", user.getMonthlyThresholdPercent());
        thresholds.put("QUARTERLY", user.getQuarterlyThresholdPercent());
        thresholds.put("YEARLY", user.getYearlyThresholdPercent());

        List<HotPickResponse> results = new ArrayList<>();
        for (HoldingResponse h : holdings.list()) {
            if (h.kind() == HoldingKind.LIABILITY) continue; // movement/threshold tracking is for assets
            List<PeriodMovement> triggered = evaluate(thresholds, days -> movements.holdingMovement(h, days));
            if (!triggered.isEmpty()) {
                BigDecimal shown = displayCurrency == null || displayCurrency.isBlank()
                        ? h.currentValue() : fx.convert(h.currentValue(), h.currency(), displayCurrency);
                String currency = displayCurrency == null || displayCurrency.isBlank() ? h.currency() : displayCurrency.trim().toUpperCase();
                results.add(new HotPickResponse("HOLDING", h.id(), h.name(), h.categoryName(), h.tickerSymbol(), shown, currency, triggered));
            }
        }
        for (WatchlistResponse w : watchlist.list()) {
            List<PeriodMovement> triggered = evaluate(thresholds, days -> movements.watchlistMovement(w, days));
            if (!triggered.isEmpty()) {
                results.add(new HotPickResponse("WATCHLIST", w.id(), w.name(), null, w.tickerSymbol(), w.currentValue(), null, triggered));
            }
        }
        return results;
    }

    private List<PeriodMovement> evaluate(Map<String, BigDecimal> thresholds, IntFunction<BigDecimal> movementForDays) {
        List<PeriodMovement> triggered = new ArrayList<>();
        for (Map.Entry<String, Integer> period : MovementService.PERIOD_DAYS.entrySet()) {
            BigDecimal threshold = thresholds.get(period.getKey());
            if (threshold == null) continue; // period not configured — skip, never fabricate a default
            BigDecimal percent = movementForDays.apply(period.getValue());
            if (percent != null && percent.abs().compareTo(threshold) >= 0) {
                triggered.add(new PeriodMovement(period.getKey(), percent, threshold));
            }
        }
        return triggered;
    }
}
