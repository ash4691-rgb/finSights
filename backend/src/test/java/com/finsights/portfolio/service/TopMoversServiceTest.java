package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.MovementThresholdResponse;
import com.finsights.portfolio.dto.TopMoverResponse;
import com.finsights.portfolio.dto.WatchlistResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TopMoversServiceTest {

    @Mock HoldingService holdings;
    @Mock WatchlistService watchlist;
    @Mock MovementService movements;
    @Mock MovementThresholdService thresholds;
    @Mock FxRateService fx;
    private TopMoversService service;

    // Only the DAILY period is configured.
    private static final MovementThresholdResponse DAILY_5_PERCENT = new MovementThresholdResponse(
            new BigDecimal("5"), null, null, null, null, null);
    // Only the HALF_YEARLY period is configured.
    private static final MovementThresholdResponse HALF_YEARLY_5_PERCENT = new MovementThresholdResponse(
            null, null, null, null, new BigDecimal("5"), null);

    @BeforeEach
    void setUp() {
        service = new TopMoversService(holdings, watchlist, movements, thresholds, fx);
        when(watchlist.list()).thenReturn(List.of());
        lenient().when(fx.supports(any())).thenReturn(true);
    }

    @Test
    void uptrendPastTheThresholdTriggers() {
        when(thresholds.get()).thenReturn(DAILY_5_PERCENT);
        HoldingResponse holding = holding("h-1", ValuationMethod.MARKET_PRICE, HoldingKind.ASSET);
        when(holdings.list()).thenReturn(List.of(holding));
        when(movements.holdingMovement(holding, 1)).thenReturn(new BigDecimal("6")); // above the 5% threshold
        when(fx.convert(any(), any(), any())).thenReturn(holding.currentValue());

        List<TopMoverResponse> result = service.topMovers("INR");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).triggered()).extracting("period").containsExactly("DAILY");
    }

    @Test
    void halfYearlyThresholdIsWiredThroughAndTriggersOnItsOwn182DayWindow() {
        when(thresholds.get()).thenReturn(HALF_YEARLY_5_PERCENT);
        HoldingResponse holding = holding("h-1", ValuationMethod.MARKET_PRICE, HoldingKind.ASSET);
        when(holdings.list()).thenReturn(List.of(holding));
        when(movements.holdingMovement(holding, 182)).thenReturn(new BigDecimal("6")); // above the 5% threshold
        when(fx.convert(any(), any(), any())).thenReturn(holding.currentValue());

        List<TopMoverResponse> result = service.topMovers("INR");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).triggered()).extracting("period").containsExactly("HALF_YEARLY");
    }

    @Test
    void downtrendPastTheThresholdAlsoTriggers() {
        when(thresholds.get()).thenReturn(DAILY_5_PERCENT);
        HoldingResponse holding = holding("h-1", ValuationMethod.MARKET_PRICE, HoldingKind.ASSET);
        when(holdings.list()).thenReturn(List.of(holding));
        when(movements.holdingMovement(holding, 1)).thenReturn(new BigDecimal("-6")); // same threshold, either direction
        when(fx.convert(any(), any(), any())).thenReturn(holding.currentValue());

        List<TopMoverResponse> result = service.topMovers("INR");

        assertThat(result).hasSize(1);
    }

    @Test
    void movementBelowTheThresholdDoesNotTrigger() {
        when(thresholds.get()).thenReturn(DAILY_5_PERCENT);
        HoldingResponse holding = holding("h-1", ValuationMethod.MARKET_PRICE, HoldingKind.ASSET);
        when(holdings.list()).thenReturn(List.of(holding));
        when(movements.holdingMovement(holding, 1)).thenReturn(new BigDecimal("-3"));

        List<TopMoverResponse> result = service.topMovers("INR");

        assertThat(result).isEmpty();
    }

    @Test
    void nonMarketLinkedHoldingsAreExcludedWithoutEvenCheckingMovement() {
        when(thresholds.get()).thenReturn(DAILY_5_PERCENT);
        HoldingResponse manual = holding("h-1", ValuationMethod.MANUAL, HoldingKind.ASSET);
        HoldingResponse fixedRate = holding("h-2", ValuationMethod.FIXED_RATE, HoldingKind.ASSET);
        when(holdings.list()).thenReturn(List.of(manual, fixedRate));

        List<TopMoverResponse> result = service.topMovers("INR");

        assertThat(result).isEmpty();
    }

    @Test
    void liabilitiesAreExcluded() {
        when(thresholds.get()).thenReturn(DAILY_5_PERCENT);
        HoldingResponse liability = holding("h-1", ValuationMethod.MARKET_PRICE, HoldingKind.LIABILITY);
        when(holdings.list()).thenReturn(List.of(liability));

        List<TopMoverResponse> result = service.topMovers("INR");

        assertThat(result).isEmpty();
    }

    @Test
    void brokerSyncHoldingsCountAsMarketLinked() {
        when(thresholds.get()).thenReturn(DAILY_5_PERCENT);
        HoldingResponse holding = holding("h-1", ValuationMethod.BROKER_SYNC, HoldingKind.ASSET);
        when(holdings.list()).thenReturn(List.of(holding));
        when(movements.holdingMovement(holding, 1)).thenReturn(new BigDecimal("8"));
        when(fx.convert(any(), any(), any())).thenReturn(holding.currentValue());

        List<TopMoverResponse> result = service.topMovers("INR");

        assertThat(result).hasSize(1);
    }

    @Test
    void watchlistItemsAreAlwaysEligibleRegardlessOfHoldingFilters() {
        when(thresholds.get()).thenReturn(DAILY_5_PERCENT);
        when(holdings.list()).thenReturn(List.of());
        WatchlistResponse item = new WatchlistResponse("w-1", "Nifty 50", "NIFTY", null, new BigDecimal("25000"), "INR", Instant.now(), Instant.now());
        when(watchlist.list()).thenReturn(List.of(item));
        when(movements.watchlistMovement(item, 1)).thenReturn(new BigDecimal("7"));
        when(fx.convert(any(), any(), any())).thenReturn(item.currentValue());

        List<TopMoverResponse> result = service.topMovers("INR");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).subjectType()).isEqualTo("WATCHLIST");
    }

    private HoldingResponse holding(String id, ValuationMethod method, HoldingKind kind) {
        return new HoldingResponse(
                id, id, "c-1", "Growth Equity", "Reliance", kind, method,
                "RELIANCE", "Kite", "INR", new BigDecimal("100"), new BigDecimal("110"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, null, null, null, null, null, null, false, false, null, null, Set.of(), Instant.now(), Instant.now(), null, false, null);
    }
}
