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
                null, null, null);
    }

    private HoldingRequest providentFund(BigDecimal invested, ManualDetails details) {
        return request(ManualType.PROVIDENT_FUND, ValuationMethod.MANUAL, "INR", null, null, invested, details);
    }

    private ManualDetails rateOnly(String rate) {
        return new ManualDetails(null, null, rate == null ? null : n(rate), null, null);
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
        assertRejected(request(ManualType.PROVIDENT_FUND, ValuationMethod.MARKET_PRICE, "INR", null, null, n("1"), null), "manual valuation");
        assertThatThrownBy(() -> ManualHoldingRules.resolve(providentFund(n("1"), null), true))
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
    void providentFundWithoutInterestHasCurrentEqualToInvestedAndTheRateIsKept() {
        HoldingRequest resolved = ManualHoldingRules.resolve(providentFund(n("200"), rateOnly("0.09")), false);

        assertThat(resolved.investedValue()).isEqualByComparingTo("200");
        assertThat(resolved.currentValue()).isEqualByComparingTo("200");
        assertThat(resolved.manual().expectedInterestRate()).isEqualByComparingTo("0.09");
        assertThat(resolved.manual().accruedInterest()).isEqualByComparingTo("0");
    }

    @Test
    void providentFundCurrentValueIsInvestedValuePlusAccruedInterest() {
        ManualDetails withInterest = new ManualDetails(null, null, null, null, n("12.345"));
        HoldingRequest resolved = ManualHoldingRules.resolve(providentFund(n("200"), withInterest), false);

        assertThat(resolved.investedValue()).isEqualByComparingTo("200");
        assertThat(resolved.currentValue()).isEqualByComparingTo("212.35");
        assertThat(resolved.manual().accruedInterest()).isEqualByComparingTo("12.35");
    }

    @Test
    void providentFundRejectsNegativeAccruedInterestAndNegativeInvestedValue() {
        assertRejected(providentFund(n("200"), new ManualDetails(null, null, null, null, n("-1"))), "Accrued interest");
        assertRejected(providentFund(n("-5"), null), "Invested value");
    }

    @Test
    void providentFundRateStaysEmptyWhenLeftOut() {
        HoldingRequest resolved = ManualHoldingRules.resolve(providentFund(n("200"), null), false);

        assertThat(resolved.manual().expectedInterestRate()).isNull();
    }

    @Test
    void providentFundKeepsTheMonthlyContributionAndRejectsANegativeOne() {
        ManualDetails monthly = new ManualDetails(null, null, null, n("3600"), null);
        HoldingRequest resolved = ManualHoldingRules.resolve(providentFund(n("100"), monthly), false);

        assertThat(resolved.manual().monthlyContribution()).isEqualByComparingTo("3600");
        assertRejected(providentFund(n("100"), new ManualDetails(null, null, null, n("-1"), null)), "Monthly contribution");
    }

    @Test
    void providentFundKeepsTheChosenCurrencyAndNeedsOne() {
        HoldingRequest resolved = ManualHoldingRules.resolve(providentFund(n("100"), null), false);
        assertThat(resolved.currency()).isEqualTo("INR");

        assertRejected(request(ManualType.PROVIDENT_FUND, ValuationMethod.MANUAL, " ", null, null, n("100"), null), "currency");
        assertRejected(request(ManualType.PROVIDENT_FUND, ValuationMethod.MANUAL, null, null, null, n("100"), null), "currency");
    }

    @Test
    void providentFundWithoutAnInvestedValueIsRejected() {
        assertRejected(providentFund(null, null), "invested value");
    }

    @Test
    void providentFundValidationRejectsBadValues() {
        assertRejected(providentFund(n("-1"), null), "Invested value");
        assertRejected(providentFund(n("5"), rateOnly("-0.01")), "between 0% and 100%");
        assertRejected(providentFund(n("5"), rateOnly("1.5")), "between 0% and 100%");
    }
}
