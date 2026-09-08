package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsights.portfolio.domain.CompoundingFrequency;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.ValuationMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ValuationServiceTest {

    private final ValuationService service = new ValuationService();

    @Test
    void manualHoldingReturnsStoredValue() {
        Holding holding = new Holding();
        holding.setValuationMethod(ValuationMethod.MANUAL);
        holding.setCurrentValue(new BigDecimal("125000"));

        assertThat(service.currentValue(holding)).isEqualByComparingTo("125000");
    }

    @Test
    void fixedRateCompoundsFromPrincipalOverCompletedPeriods() {
        Holding holding = new Holding();
        holding.setValuationMethod(ValuationMethod.FIXED_RATE);
        holding.setInvestedValue(new BigDecimal("100000"));
        holding.setFixedAnnualRate(new BigDecimal("0.12"));
        holding.setCompoundingFrequency(CompoundingFrequency.MONTHLY);
        holding.setFixedRateStartDate(LocalDate.now().minusYears(1));

        // 365 days = 11 completed monthly periods at 1% -> 100000 * 1.01^11 ~= 111566.83
        assertThat(service.currentValue(holding).doubleValue()).isCloseTo(111566.83, org.assertj.core.data.Offset.offset(2.0));
    }

    @Test
    void fixedRateBeforeFirstPeriodStaysAtPrincipal() {
        Holding holding = new Holding();
        holding.setValuationMethod(ValuationMethod.FIXED_RATE);
        holding.setInvestedValue(new BigDecimal("50000"));
        holding.setFixedAnnualRate(new BigDecimal("0.07"));
        holding.setCompoundingFrequency(CompoundingFrequency.ANNUALLY);
        holding.setFixedRateStartDate(LocalDate.now().minusDays(10));

        assertThat(service.currentValue(holding)).isEqualByComparingTo("50000.00");
    }

    @Test
    void explainProducesAuditTrailForFixedRate() {
        Holding holding = new Holding();
        holding.setValuationMethod(ValuationMethod.FIXED_RATE);
        holding.setInvestedValue(new BigDecimal("100000"));
        holding.setFixedAnnualRate(new BigDecimal("0.07"));
        holding.setCompoundingFrequency(CompoundingFrequency.QUARTERLY);
        holding.setFixedRateStartDate(LocalDate.now().minusYears(2));

        var detail = service.explain(holding);
        assertThat(detail.steps()).anyMatch(step -> step.startsWith("Principal:"));
        assertThat(detail.steps()).anyMatch(step -> step.contains("Growth factor"));
        assertThat(detail.projectedMaturityValue()).isNotNull();
        assertThat(detail.currentValue()).isGreaterThan(new BigDecimal("100000"));
    }
}
