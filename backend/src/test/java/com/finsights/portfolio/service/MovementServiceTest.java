package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.CompoundingFrequency;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.WatchlistResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MovementServiceTest {

    @Mock PriceSnapshotService snapshots;
    private final ValuationService valuations = new ValuationService();
    private MovementService service;

    @BeforeEach
    void setUp() {
        service = new MovementService(snapshots, valuations);
    }

    @Test
    void percentChangeComputesSignedMove() {
        assertThat(service.percentChange(new BigDecimal("100"), new BigDecimal("110"))).isEqualByComparingTo("10.00");
        assertThat(service.percentChange(new BigDecimal("100"), new BigDecimal("90"))).isEqualByComparingTo("-10.00");
    }

    @Test
    void percentChangeIsNullWhenEitherSideIsUnknownOrBaselineIsZero() {
        assertThat(service.percentChange(null, new BigDecimal("110"))).isNull();
        assertThat(service.percentChange(new BigDecimal("100"), null)).isNull();
        assertThat(service.percentChange(BigDecimal.ZERO, new BigDecimal("10"))).isNull();
    }

    @Test
    void manualHoldingMovementComesFromTheClosestPriorSnapshot() {
        HoldingResponse holding = manualHolding("120");
        when(snapshots.closestAtOrBefore(any(SnapshotSubject.class), anyString(), any(Instant.class)))
                .thenReturn(new BigDecimal("100"));

        BigDecimal movement = service.holdingMovement(holding, 7);

        assertThat(movement).isEqualByComparingTo("20.00");
        verify(snapshots).closestAtOrBefore(eq(SnapshotSubject.HOLDING), eq(holding.id()), any(Instant.class));
    }

    @Test
    void manualHoldingMovementIsNullWithoutAPriorSnapshot() {
        HoldingResponse holding = manualHolding("120");
        when(snapshots.closestAtOrBefore(any(SnapshotSubject.class), anyString(), any(Instant.class))).thenReturn(null);

        assertThat(service.holdingMovement(holding, 30)).isNull();
    }

    @Test
    void fixedRateHoldingMovementIsComputedAnalyticallyWithoutTouchingSnapshots() {
        // 1 year ago at 12%/monthly compounding, valued 30 days ago vs today.
        HoldingResponse holding = fixedRateHolding("100000", "0.12", CompoundingFrequency.MONTHLY, LocalDate.now().minusYears(1));

        BigDecimal movement = service.holdingMovement(holding, 30);

        assertThat(movement).isNotNull();
        // Growing at ~1%/month, ~30 days of additional growth should be a small positive move.
        assertThat(movement.doubleValue()).isBetween(0.0, 3.0);
        verifyNoInteractions(snapshots);
    }

    @Test
    void fixedRateWithIncompleteInputsFallsBackToSnapshots() {
        HoldingResponse holding = new HoldingResponse(
                "h-1", "hr-1", "c-1", "Fixed Income", "Incomplete FD", HoldingKind.ASSET, ValuationMethod.FIXED_RATE,
                null, "Bank", null, "INR", new BigDecimal("1000"), new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, false, false, null, null, Set.of(), Instant.now(), Instant.now(), null);
        when(snapshots.closestAtOrBefore(any(SnapshotSubject.class), anyString(), any(Instant.class)))
                .thenReturn(new BigDecimal("900"));

        assertThat(service.holdingMovement(holding, 1)).isEqualByComparingTo("11.11");
    }

    @Test
    void watchlistMovementDiffsCurrentValueAgainstClosestPriorSnapshot() {
        WatchlistResponse item = new WatchlistResponse("w-1", "Nifty 50", null, null, new BigDecimal("25000"), Instant.now(), Instant.now());
        when(snapshots.closestAtOrBefore(any(SnapshotSubject.class), anyString(), any(Instant.class))).thenReturn(new BigDecimal("24000"));

        BigDecimal movement = service.watchlistMovement(item, 1);

        assertThat(movement).isEqualByComparingTo("4.17"); // (25000-24000)/24000 * 100
        verify(snapshots).closestAtOrBefore(eq(SnapshotSubject.WATCHLIST), eq("w-1"), any(Instant.class));
    }

    private HoldingResponse manualHolding(String currentValue) {
        return new HoldingResponse(
                "h-1", "hr-1", "c-1", "Growth Equity", "Reliance", HoldingKind.ASSET, ValuationMethod.MANUAL,
                null, "Kite", null, "INR", new BigDecimal("100"), new BigDecimal(currentValue), BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, false, false, null, null, Set.of(), Instant.now(), Instant.now(), null);
    }

    private HoldingResponse fixedRateHolding(String principal, String rate, CompoundingFrequency frequency, LocalDate start) {
        // currentValue must reflect today's compounded value, exactly like HoldingService.toResponse() does,
        // for the analytic "now vs 30-days-ago" comparison inside holdingMovement to be meaningful.
        BigDecimal principalValue = new BigDecimal(principal);
        BigDecimal rateValue = new BigDecimal(rate);
        BigDecimal currentValue = valuations.compoundedValue(principalValue, rateValue, frequency, start, LocalDate.now());
        return new HoldingResponse(
                "h-2", "hr-2", "c-2", "Fixed Income", "HDFC FD", HoldingKind.ASSET, ValuationMethod.FIXED_RATE,
                null, "HDFC", null, "INR", principalValue, currentValue, BigDecimal.ZERO, BigDecimal.ZERO,
                null, rateValue, frequency, start, false, false, null, null, Set.of(), Instant.now(), Instant.now(), null);
    }
}
