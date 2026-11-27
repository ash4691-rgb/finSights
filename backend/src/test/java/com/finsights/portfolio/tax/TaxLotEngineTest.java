package com.finsights.portfolio.tax;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.tax.TaxLotEngine.Entry;
import com.finsights.portfolio.tax.TaxLotEngine.Result;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaxLotEngineTest {
    private static final LocalDate D1 = LocalDate.of(2023, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2024, 1, 1);
    private static final LocalDate D3 = LocalDate.of(2025, 1, 1);

    private static Entry e(LocalDate date, TransactionType type, String amount, String units) {
        return new Entry(date, type, amount == null ? null : new BigDecimal(amount), units == null ? null : new BigDecimal(units));
    }

    @Test
    void sellConsumesOldestLotsFirstAndSplitsAcrossThem() {
        Result result = TaxLotEngine.match(List.of(
                e(D1, TransactionType.BUY, "1000", "10"),     // 100/unit
                e(D2, TransactionType.BUY, "2000", "10"),     // 200/unit
                e(D3, TransactionType.SELL, "4500", "15")));  // 300/unit

        assertThat(result.realised()).hasSize(2);
        var first = result.realised().get(0);
        assertThat(first.bought()).isEqualTo(D1);
        assertThat(first.units()).isEqualByComparingTo("10");
        assertThat(first.cost()).isEqualByComparingTo("1000");
        assertThat(first.proceeds()).isEqualByComparingTo("3000");
        assertThat(first.gain()).isEqualByComparingTo("2000");
        var second = result.realised().get(1);
        assertThat(second.bought()).isEqualTo(D2);
        assertThat(second.units()).isEqualByComparingTo("5");
        assertThat(second.cost()).isEqualByComparingTo("1000");
        assertThat(second.proceeds()).isEqualByComparingTo("1500");
        assertThat(result.open()).singleElement().satisfies(lot -> {
            assertThat(lot.bought()).isEqualTo(D2);
            assertThat(lot.units()).isEqualByComparingTo("5");
            assertThat(lot.cost()).isEqualByComparingTo("1000");
        });
    }

    @Test
    void splitScalesUnitsAndKeepsCost() {
        Result result = TaxLotEngine.match(List.of(
                e(D1, TransactionType.BUY, "1000", "10"),
                e(D2, TransactionType.SPLIT, null, "2"),
                e(D2, TransactionType.SPLIT, null, "0"),
                e(D3, TransactionType.SELL, "600", "10")));

        assertThat(result.realised().get(0).cost()).isEqualByComparingTo("500");
        assertThat(result.open().get(0).units()).isEqualByComparingTo("10");
        assertThat(result.open().get(0).cost()).isEqualByComparingTo("500");
    }

    @Test
    void sellWithoutUnitsClosesEverythingAndOversellIsCapped() {
        Result all = TaxLotEngine.match(List.of(
                e(D1, TransactionType.BUY, "1000", "10"), e(D3, TransactionType.SELL, "1200", null)));
        assertThat(all.realised()).singleElement().satisfies(r -> assertThat(r.units()).isEqualByComparingTo("10"));
        assertThat(all.open()).isEmpty();

        Result capped = TaxLotEngine.match(List.of(
                e(D1, TransactionType.BUY, "1000", "10"), e(D3, TransactionType.SELL, "1200", "20")));
        assertThat(capped.realised().get(0).proceeds()).isEqualByComparingTo("1200");
        assertThat(capped.realised().get(0).units()).isEqualByComparingTo("10");
    }

    @Test
    void sellingWithNothingHeldIsIgnored() {
        Result result = TaxLotEngine.match(List.of(e(D1, TransactionType.SELL, "100", "1")));
        assertThat(result.realised()).isEmpty();
        assertThat(result.open()).isEmpty();
    }

    @Test
    void valueOnlyLedgerTracksLotsByAmount() {
        Result result = TaxLotEngine.match(List.of(
                e(D1, TransactionType.BUY, "1000", null),
                e(D2, TransactionType.ADJUSTMENT, "500", "0"),
                e(D2, TransactionType.ADJUSTMENT, "-50", null),       // a negative nudge adds no lot
                e(D2, TransactionType.INTEREST, "70", null),
                e(D3, TransactionType.SELL, "2000", null)));

        assertThat(result.realised()).hasSize(2);
        assertThat(result.realised()).extracting(TaxLotEngine.RealisedLot::cost)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1000"), new BigDecimal("500"));
        assertThat(result.realised().stream().map(TaxLotEngine.RealisedLot::proceeds).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("2000");
    }

    @Test
    void unitlessBuyInAUnitLedgerIsLeftOutAndNullTypesAreSkipped() {
        Result result = TaxLotEngine.match(List.of(
                e(D1, TransactionType.BUY, "1000", "10"),
                e(D1, TransactionType.BUY, "99", null),
                new Entry(D2, null, BigDecimal.ONE, BigDecimal.ONE),
                e(D2, TransactionType.REPAY, "5", null)));
        assertThat(result.open()).singleElement().satisfies(lot -> assertThat(lot.cost()).isEqualByComparingTo("1000"));
    }
}
