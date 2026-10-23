package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ExtractedHolding;
import com.finsights.portfolio.dto.InstrumentKind;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatementRowsTest {

    private static ExtractedHolding fund(String name, String units, String invested, String current) {
        BigDecimal quantity = new BigDecimal(units);
        BigDecimal investedValue = invested == null ? null : new BigDecimal(invested);
        BigDecimal currentValue = current == null ? null : new BigDecimal(current);
        return new ExtractedHolding(name, null, quantity, StatementRows.averageCost(investedValue, quantity), null, null, null,
                InstrumentKind.MUTUAL_FUND, investedValue, currentValue);
    }

    @Test
    void keyIsTheIsinWhenThereIsOneAndTheNormalisedNameWhenThereIsNot() {
        ExtractedHolding stock = new ExtractedHolding("Infosys", "INE009A01021", BigDecimal.ONE, null, null, null, null);
        assertThat(StatementRows.holdingKey(stock)).isEqualTo("INE009A01021");
        assertThat(StatementRows.holdingKey(fund("  Tata   Arbitrage Fund ", "1", null, null))).isEqualTo("TATA ARBITRAGE FUND");
    }

    @Test
    void foliosOfOneSchemeMergeIntoOneHoldingWithSummedUnitsAndRecomputedAverageCost() {
        List<ExtractedHolding> merged = StatementRows.consolidate(List.of(
                fund("Alpha Small Cap Fund", "10", "1000", "1500"),
                fund("Beta Mid Cap Fund", "5", "500", "400"),
                fund("alpha small cap fund", "30", "5000", "6000")));

        assertThat(merged).hasSize(2);
        ExtractedHolding alpha = merged.get(0);
        assertThat(alpha.quantity()).isEqualByComparingTo("40");
        assertThat(alpha.investedValue()).isEqualByComparingTo("6000");
        assertThat(alpha.currentValue()).isEqualByComparingTo("7500");
        assertThat(alpha.averageCostPerUnit()).isEqualByComparingTo("150");
    }

    @Test
    void aMissingAmountOnAnyFolioLeavesTheMergedTotalUnknownRatherThanUnderstated() {
        List<ExtractedHolding> merged = StatementRows.consolidate(List.of(
                fund("Alpha Fund", "10", "1000", null), fund("Alpha Fund", "10", null, "2000")));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).investedValue()).isNull();
        assertThat(merged.get(0).currentValue()).isNull();
        assertThat(merged.get(0).averageCostPerUnit()).isNull();
    }

    @Test
    void aRepeatedStockKeepsItsFirstSightingInsteadOfDoubleCountingIt() {
        ExtractedHolding first = new ExtractedHolding("Infosys", "INE009A01021", BigDecimal.TEN, null, null, null, null);
        ExtractedHolding again = new ExtractedHolding("Infosys", "INE009A01021", BigDecimal.ONE, null, null, null, null);

        assertThat(StatementRows.consolidate(List.of(first, again))).containsExactly(first);
    }

    @Test
    void averageCostIsUnknownWithoutAnInvestedValueOrPositiveUnits() {
        assertThat(StatementRows.averageCost(null, BigDecimal.TEN)).isNull();
        assertThat(StatementRows.averageCost(BigDecimal.TEN, null)).isNull();
        assertThat(StatementRows.averageCost(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
        assertThat(StatementRows.averageCost(new BigDecimal("100"), new BigDecimal("3"))).isEqualByComparingTo("33.3333");
    }

    private static Holding holding(String units, ValuationMethod method, HoldingKind kind, boolean realEstate) {
        Holding h = new Holding();
        h.setQuantity(units == null ? null : new BigDecimal(units));
        h.setValuationMethod(method);
        Category c = new Category();
        c.setKind(kind);
        c.setRealEstate(realEstate);
        h.setCategory(c);
        return h;
    }

    @Test
    void onlyMarketLinkedAssetsWithUnitsAreExpectedOnABrokerStatement() {
        assertThat(StatementRows.listedOnBrokerStatements(holding("5", ValuationMethod.MARKET_PRICE, HoldingKind.ASSET, false))).isTrue();
        assertThat(StatementRows.listedOnBrokerStatements(holding(null, ValuationMethod.MANUAL, HoldingKind.ASSET, false))).isFalse();
        assertThat(StatementRows.listedOnBrokerStatements(holding("0", ValuationMethod.MANUAL, HoldingKind.ASSET, false))).isFalse();
        assertThat(StatementRows.listedOnBrokerStatements(holding("5", ValuationMethod.FIXED_RATE, HoldingKind.ASSET, false))).isFalse();
        assertThat(StatementRows.listedOnBrokerStatements(holding("5", ValuationMethod.MANUAL, HoldingKind.LIABILITY, false))).isFalse();
        assertThat(StatementRows.listedOnBrokerStatements(holding("1", ValuationMethod.MANUAL, HoldingKind.ASSET, true))).isFalse();

        Holding noCategory = holding("5", ValuationMethod.MANUAL, HoldingKind.ASSET, false);
        noCategory.setCategory(null);
        assertThat(StatementRows.listedOnBrokerStatements(noCategory)).isTrue();
    }

    @Test
    void brokerNamesCompareCaseInsensitivelyAndTrimmedButNeverWhenEitherIsMissing() {
        assertThat(StatementRows.sameBroker(" Groww ", "groww")).isTrue();
        assertThat(StatementRows.sameBroker("Groww", "Zerodha")).isFalse();
        assertThat(StatementRows.sameBroker(null, "Groww")).isFalse();
        assertThat(StatementRows.sameBroker("Groww", null)).isFalse();
    }
}
