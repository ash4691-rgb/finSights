package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarketDataServiceTest {

    private final MarketDataService service = new MarketDataService();

    @Test
    void blankInputsReturnNothingWithoutTouchingTheNetwork() {
        assertThat(service.search("  ")).isEmpty();
        assertThat(service.search(null)).isEmpty();
        assertThat(service.quote(null)).isEmpty();
        assertThat(service.quote("")).isEmpty();
        assertThat(service.quotes(null)).isEmpty();
    }
}
