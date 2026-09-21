package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FxRateServiceTest {

    private final FxRateService fx = new FxRateService();

    private HoldingResponse holding(String currency) {
        return new HoldingResponse("h-1", "hr-1", "c-1", "Growth Equity", "Reliance", HoldingKind.ASSET, ValuationMethod.MANUAL,
                null, "Kite", currency, currency, new BigDecimal("100"), new BigDecimal("110"), BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null, null, null, null, null, null,
                false, false, null, null, Set.of(), Instant.now(), Instant.now(), null, false, null);
    }

    @Test
    void sameCurrencyIsUnchanged() {
        assertThat(fx.convert(new BigDecimal("100"), "INR", "INR")).isEqualByComparingTo("100");
    }

    @Test
    void convertsThroughInrCrossRate() {
        // 100 USD -> INR -> EUR, using the published static table
        BigDecimal result = fx.convert(new BigDecimal("100"), "USD", "INR");
        assertThat(result).isEqualByComparingTo("8850.00");
    }

    @Test
    void unsupportedCurrencyIsRejected() {
        assertThatThrownBy(() -> fx.convert(BigDecimal.TEN, "XYZ", "INR"))
                .hasMessageContaining("Unsupported currency");
    }

    @Test
    void ratesResponseListsSupportedCurrenciesWithInrBase() {
        var rates = fx.rates();
        assertThat(rates.base()).isEqualTo("INR");
        assertThat(rates.ratesToBase()).containsKeys("INR", "USD", "EUR", "GBP", "SGD", "AED");
        assertThat(rates.note()).isNotBlank();
    }

    // A display-currency-converted holding shows converted amounts under the view currency, but
    // callers that must never convert (like logging a transaction) need the holding's real linked
    // currency — defaultCurrency must survive the conversion unchanged.
    @Test
    void convertingAHoldingKeepsItsDefaultCurrencyUnconverted() {
        HoldingResponse converted = fx.convert(holding("INR"), "USD");

        assertThat(converted.currency()).isEqualTo("USD");
        assertThat(converted.defaultCurrency()).isEqualTo("INR");
    }

    @Test
    void convertingToTheSameCurrencyLeavesTheHoldingUntouched() {
        HoldingResponse original = holding("INR");

        HoldingResponse result = fx.convert(original, "INR");

        assertThat(result.currency()).isEqualTo("INR");
        assertThat(result.defaultCurrency()).isEqualTo("INR");
    }
}
