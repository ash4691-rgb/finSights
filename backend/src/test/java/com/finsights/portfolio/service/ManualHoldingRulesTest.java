package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finsights.portfolio.domain.AreaUnit;
import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.ManualDetails;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ManualHoldingRulesTest {

    private static BigDecimal n(String v) { return new BigDecimal(v); }

    private HoldingRequest request(ManualType type, ValuationMethod method, String currency, BigDecimal area, AreaUnit unit,
                                   BigDecimal invested, ManualDetails details) {
        return new HoldingRequest("c", "Name", method, null, "Self", currency, null, area, unit, invested, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, type, details);
    }

    private HoldingRequest realEstate(BigDecimal area, ManualDetails details) {
        return request(ManualType.REAL_ESTATE, ValuationMethod.MANUAL, "INR", area, AreaUnit.SQ_M, null, details);
    }

    private ManualDetails prices(String purchase, String current) {
        return new ManualDetails(purchase == null ? null : n(purchase), current == null ? null : n(current),
                null, null, null, null, null, null);
    }

    private HoldingRequest epfo(BigDecimal invested, ManualDetails details) {
        return request(ManualType.EPFO, ValuationMethod.MANUAL, "INR", null, null, invested, details);
    }

    private ManualDetails epfoDetails(String emp, String er, String interest, String rate, Integer month, Integer day) {
        return new ManualDetails(null, null, emp == null ? null : n(emp), er == null ? null : n(er),
                interest == null ? null : n(interest), rate == null ? null : n(rate), month, day);
    }

    private void assertRejected(HoldingRequest request, String message) {
        assertThatThrownBy(() -> ManualHoldingRules.resolve(request, false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining(message);
    }

    @Test
    void plainAndNullTypesPassThroughUnchanged() {
        HoldingRequest plain = request(null, ValuationMethod.MARKET_PRICE, "INR", null, null, n("5"), null);
        assertThat(ManualHoldingRules.resolve(plain, false)).isSameAs(plain);
        HoldingRequest explicit = request(ManualType.DEFAULT, ValuationMethod.MANUAL, "INR", null, null, n("5"), null);
        assertThat(ManualHoldingRules.resolve(explicit, true)).isSameAs(explicit);
        assertThat(ManualHoldingRules.effectiveType(null)).isEqualTo(ManualType.DEFAULT);
    }

    @Test
    void typedHoldingMustBeManualAndNotALiability() {
        assertRejected(request(ManualType.EPFO, ValuationMethod.MARKET_PRICE, "INR", null, null, n("1"), null), "manual valuation");
        assertThatThrownBy(() -> ManualHoldingRules.resolve(epfo(n("1"), null), true))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("liability");
    }

    @Test
    void realEstateValuesAreAreaTimesUnitPrice() {
        HoldingRequest resolved = ManualHoldingRules.resolve(realEstate(n("12.5"), prices("1000.10", "1200")), false);

        assertThat(resolved.investedValue()).isEqualByComparingTo("12501.25");
        assertThat(resolved.currentValue()).isEqualByComparingTo("15000.00");
        assertThat(resolved.manual().currentUnitPrice()).isEqualByComparingTo("1200");
    }

    @Test
    void realEstateCurrentPriceDefaultsToPurchasePrice() {
        HoldingRequest resolved = ManualHoldingRules.resolve(realEstate(n("10"), prices("500", null)), false);

        assertThat(resolved.currentValue()).isEqualByComparingTo(resolved.investedValue());
        assertThat(resolved.manual().currentUnitPrice()).isEqualByComparingTo("500");
    }

    @Test
    void realEstateRequiredFieldsAreEnforced() {
        assertRejected(realEstate(null, prices("5", null)), "area greater than zero");
        assertRejected(realEstate(n("0"), prices("5", null)), "area greater than zero");
        assertRejected(request(ManualType.REAL_ESTATE, ValuationMethod.MANUAL, "INR", n("1"), null, null, prices("5", null)), "area unit");
        assertRejected(request(ManualType.REAL_ESTATE, ValuationMethod.MANUAL, " ", n("1"), AreaUnit.ACRE, null, prices("5", null)), "currency");
        assertRejected(request(ManualType.REAL_ESTATE, ValuationMethod.MANUAL, null, n("1"), AreaUnit.ACRE, null, prices("5", null)), "currency");
        assertRejected(realEstate(n("1"), null), "purchase unit price");
        assertRejected(realEstate(n("1"), prices("0", null)), "purchase unit price");
        assertRejected(realEstate(n("1"), prices("5", "0")), "current unit price");
    }

    @Test
    void epfoCurrentIsTheEmployeePlusEmployerBalanceAndInvestedExcludesInterest() {
        HoldingRequest resolved = ManualHoldingRules.resolve(
                epfo(null, epfoDetails("100", "50", "10", "0.0825", 3, 31)), false);

        assertThat(resolved.investedValue()).isEqualByComparingTo("140");
        assertThat(resolved.currentValue()).isEqualByComparingTo("150");
        assertThat(resolved.manual().interestCreditDay()).isEqualTo(31);
    }

    @Test
    void epfoWithOnlyOneContributionTreatsTheOtherAsZero() {
        HoldingRequest resolved = ManualHoldingRules.resolve(epfo(null, epfoDetails(null, "70", null, null, null, null)), false);

        assertThat(resolved.investedValue()).isEqualByComparingTo("70");
        assertThat(resolved.currentValue()).isEqualByComparingTo("70");
        assertThat(resolved.manual().interestAccumulated()).isEqualByComparingTo("0");
    }

    @Test
    void epfoUsesTheSingleTotalBalanceWhenNoSplitIsGiven() {
        HoldingRequest resolved = ManualHoldingRules.resolve(epfo(n("200"), epfoDetails(null, null, "20", null, null, null)), false);

        assertThat(resolved.investedValue()).isEqualByComparingTo("180");
        assertThat(resolved.currentValue()).isEqualByComparingTo("200");
    }

    @Test
    void epfoWithoutAnyContributionIsRejected() {
        assertRejected(epfo(null, null), "total balance");
    }

    @Test
    void epfoValidationRejectsBadValues() {
        assertRejected(epfo(n("-1"), null), "Total balance");
        assertRejected(epfo(n("5"), epfoDetails(null, null, "6", null, null, null)), "more than the current balance");
        assertRejected(epfo(null, epfoDetails("-1", null, null, null, null, null)), "Employee contribution");
        assertRejected(epfo(null, epfoDetails(null, "-1", null, null, null, null)), "Employer contribution");
        assertRejected(epfo(n("5"), epfoDetails(null, null, "-1", null, null, null)), "Interest accumulated");
        assertRejected(epfo(n("5"), epfoDetails(null, null, null, "1.5", null, null)), "between 0% and 100%");
        assertRejected(epfo(n("5"), epfoDetails(null, null, null, "-0.1", null, null)), "between 0% and 100%");
        assertRejected(epfo(n("5"), epfoDetails(null, null, null, null, 3, null)), "both the month and the day");
        assertRejected(epfo(n("5"), epfoDetails(null, null, null, null, null, 3)), "both the month and the day");
        assertRejected(epfo(n("5"), epfoDetails(null, null, null, null, 13, 1)), "valid day");
        assertRejected(epfo(n("5"), epfoDetails(null, null, null, null, 0, 1)), "valid day");
        assertRejected(epfo(n("5"), epfoDetails(null, null, null, null, 1, 32)), "valid day");
        assertRejected(epfo(n("5"), epfoDetails(null, null, null, null, 1, 0)), "valid day");
    }
}
