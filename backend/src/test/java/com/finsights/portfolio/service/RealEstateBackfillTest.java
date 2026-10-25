package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RealEstateBackfillTest {
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final RealEstateBackfill backfill = new RealEstateBackfill(holdings);

    private Holding legacy(String area, String invested, String current) {
        Holding h = new Holding();
        h.setArea(area == null ? null : new BigDecimal(area));
        h.setInvestedValue(invested == null ? null : new BigDecimal(invested));
        h.setCurrentValue(current == null ? null : new BigDecimal(current));
        return h;
    }

    @Test
    void marksAreaHoldingsAsRealEstateWithDerivedUnitPrices() {
        Holding flat = legacy("1000", "5000000", "6000000");
        when(holdings.findByAreaIsNotNullAndManualTypeIsNull()).thenReturn(List.of(flat));

        assertThat(backfill.backfill()).isEqualTo(1);

        assertThat(flat.getManualType()).isEqualTo(ManualType.REAL_ESTATE);
        assertThat(flat.getPurchaseUnitPrice()).isEqualByComparingTo("5000");
        assertThat(flat.getCurrentUnitPrice()).isEqualByComparingTo("6000");
        verify(holdings).saveAll(List.of(flat));
    }

    @Test
    void leavesUnitPricesEmptyWhenValuesOrAreaAreUnusable() {
        Holding noValues = legacy("10", null, null);
        Holding zeroArea = legacy("0", "100", "100");
        when(holdings.findByAreaIsNotNullAndManualTypeIsNull()).thenReturn(List.of(noValues, zeroArea));

        backfill.run(null);

        assertThat(noValues.getPurchaseUnitPrice()).isNull();
        assertThat(zeroArea.getCurrentUnitPrice()).isNull();
        assertThat(zeroArea.getManualType()).isEqualTo(ManualType.REAL_ESTATE);
    }

    @Test
    void doesNothingWhenNoLegacyHoldingsExist() {
        when(holdings.findByAreaIsNotNullAndManualTypeIsNull()).thenReturn(List.of());

        backfill.run(null);

        assertThat(backfill.backfill()).isZero();
    }
}
