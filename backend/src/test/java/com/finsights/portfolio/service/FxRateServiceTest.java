package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FxRateServiceTest {

    private final FxRateService fx = new FxRateService();

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
}
