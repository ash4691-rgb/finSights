package com.finsights.portfolio.tax;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsights.portfolio.domain.TaxClass;
import com.finsights.portfolio.tax.TaxRules.Term;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TaxRulesTest {
    private static final LocalDate BOUGHT = LocalDate.of(2024, 1, 15);

    @Test
    void equityTurnsLongTermAfterTwelveMonthsOtherAfterTwentyFour() {
        assertThat(TaxRules.term(TaxClass.LISTED_EQUITY, BOUGHT, LocalDate.of(2025, 1, 15))).isEqualTo(Term.SHORT);
        assertThat(TaxRules.term(TaxClass.LISTED_EQUITY, BOUGHT, LocalDate.of(2025, 1, 16))).isEqualTo(Term.LONG);
        assertThat(TaxRules.term(TaxClass.OTHER_CAPITAL, BOUGHT, LocalDate.of(2026, 1, 15))).isEqualTo(Term.SHORT);
        assertThat(TaxRules.term(TaxClass.OTHER_CAPITAL, BOUGHT, LocalDate.of(2026, 1, 16))).isEqualTo(Term.LONG);
        assertThat(TaxRules.longTermFrom(TaxClass.LISTED_EQUITY, BOUGHT)).isEqualTo(LocalDate.of(2025, 1, 16));
    }

    @Test
    void holdingPeriodDoesNotMatterForSlabFlatOrExempt() {
        assertThat(TaxRules.term(TaxClass.DEBT_SLAB, BOUGHT, BOUGHT)).isEqualTo(Term.SLAB);
        assertThat(TaxRules.term(TaxClass.VIRTUAL_DIGITAL_ASSET, BOUGHT, BOUGHT)).isEqualTo(Term.FLAT);
        assertThat(TaxRules.term(TaxClass.EXEMPT, BOUGHT, BOUGHT)).isEqualTo(Term.EXEMPT);
        assertThat(TaxRules.longTermAfterMonths(TaxClass.DEBT_SLAB)).isZero();
    }

    @Test
    void ratesChangeOnTwentyThirdJuly2024() {
        LocalDate before = TaxRules.RATE_CHANGE.minusDays(1);
        assertThat(TaxRules.equityStcgRate(before)).isEqualByComparingTo("0.15");
        assertThat(TaxRules.equityStcgRate(TaxRules.RATE_CHANGE)).isEqualByComparingTo("0.20");
        assertThat(TaxRules.equityLtcgRate(before)).isEqualByComparingTo("0.10");
        assertThat(TaxRules.equityLtcgRate(TaxRules.RATE_CHANGE)).isEqualByComparingTo("0.125");
        assertThat(TaxRules.otherLtcgRate(before)).isNull();
        assertThat(TaxRules.otherLtcgRate(TaxRules.RATE_CHANGE)).isEqualByComparingTo("0.125");
        assertThat(TaxRules.equityLtcgExemption(new FinancialYear(2023))).isEqualByComparingTo("100000");
        assertThat(TaxRules.equityLtcgExemption(new FinancialYear(2024))).isEqualByComparingTo("125000");
    }
}
