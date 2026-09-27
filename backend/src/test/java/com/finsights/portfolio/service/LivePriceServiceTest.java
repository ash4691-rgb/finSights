package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.LivePrice;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.repository.LivePriceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LivePriceServiceTest {

    @Mock LivePriceRepository repository;
    @Mock MarketDataService marketData;
    private LivePriceService service;

    @BeforeEach
    void setUp() {
        service = new LivePriceService(repository, marketData);
    }

    private LivePrice row(String symbol, String price, Instant fetchedAt) {
        LivePrice row = new LivePrice();
        row.setSymbol(symbol);
        row.setName(symbol + " Inc");
        row.setPrice(new BigDecimal(price));
        row.setCurrency("INR");
        row.setFetchedAt(fetchedAt);
        return row;
    }

    @Test
    void getPricesServesAFreshCachedRowWithoutTouchingTheLiveFeed() {
        when(repository.findAllById(anySet())).thenReturn(List.of(row("RELIANCE.NS", "2950.00", Instant.now())));

        Map<String, MarketQuoteResponse> result = service.getPrices(Set.of("RELIANCE.NS"));

        assertThat(result).containsKey("RELIANCE.NS");
        assertThat(result.get("RELIANCE.NS").price()).isEqualByComparingTo("2950.00");
        verify(marketData, never()).quotes(any());
    }

    @Test
    void getPricesFallsBackToAnOnDemandFetchForASymbolWithNoCachedRowYet() {
        when(repository.findAllById(anySet())).thenReturn(List.of()); // never priced before
        when(marketData.quotes(Set.of("NEWTICKER"))).thenReturn(Map.of("NEWTICKER",
                new MarketQuoteResponse("NEWTICKER", "New Co", new BigDecimal("100.00"), "INR", Instant.now())));

        Map<String, MarketQuoteResponse> result = service.getPrices(Set.of("NEWTICKER"));

        assertThat(result.get("NEWTICKER").price()).isEqualByComparingTo("100.00");
        verify(repository).saveAll(any());
    }

    @Test
    void getPricesFallsBackWhenTheCachedRowHasFallenFarBehindTheRefreshCadence() {
        Instant longAgo = Instant.now().minus(2, ChronoUnit.HOURS); // scheduled refresh should have long since replaced this
        when(repository.findAllById(anySet())).thenReturn(List.of(row("RELIANCE.NS", "2900.00", longAgo)));
        when(marketData.quotes(Set.of("RELIANCE.NS"))).thenReturn(Map.of("RELIANCE.NS",
                new MarketQuoteResponse("RELIANCE.NS", "Reliance", new BigDecimal("2955.00"), "INR", Instant.now())));

        Map<String, MarketQuoteResponse> result = service.getPrices(Set.of("RELIANCE.NS"));

        assertThat(result.get("RELIANCE.NS").price()).isEqualByComparingTo("2955.00");
    }

    @Test
    void getPricesNormalizesSymbolCasingAndWhitespace() {
        when(repository.findAllById(Set.of("RELIANCE.NS"))).thenReturn(List.of(row("RELIANCE.NS", "2950.00", Instant.now())));

        Map<String, MarketQuoteResponse> result = service.getPrices(Set.of(" reliance.ns "));

        assertThat(result).containsKey("RELIANCE.NS");
    }

    @Test
    void blankInputReturnsEmptyWithoutTouchingAnything() {
        assertThat(service.getPrices(Set.of())).isEmpty();
        assertThat(service.getPrices(null)).isEmpty();
        verify(repository, never()).findAllById(any());
    }

    @Test
    void refreshAllUpsertsEveryQuotedSymbolAndSkipsUnquotedOnes() {
        when(marketData.quotes(any())).thenReturn(Map.of(
                "RELIANCE.NS", new MarketQuoteResponse("RELIANCE.NS", "Reliance", new BigDecimal("2950.00"), "INR", Instant.now()),
                "DEAD", new MarketQuoteResponse("DEAD", "Dead Co", null, "INR", Instant.now()))); // no price → not saved
        when(repository.findAllById(any())).thenReturn(List.of());

        service.refreshAll(Set.of("RELIANCE.NS", "DEAD"));

        ArgumentCaptor<List<LivePrice>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(LivePrice::getSymbol).containsExactly("RELIANCE.NS");
    }

    @Test
    void refreshAllUpdatesAnExistingRowInPlaceRatherThanDuplicatingIt() {
        LivePrice existing = row("RELIANCE.NS", "2900.00", Instant.now().minus(1, ChronoUnit.HOURS));
        when(marketData.quotes(any())).thenReturn(Map.of("RELIANCE.NS",
                new MarketQuoteResponse("RELIANCE.NS", "Reliance", new BigDecimal("2960.00"), "INR", Instant.now())));
        when(repository.findAllById(any())).thenReturn(List.of(existing));

        service.refreshAll(Set.of("RELIANCE.NS"));

        assertThat(existing.getPrice()).isEqualByComparingTo("2960.00"); // same row instance, updated in place
        ArgumentCaptor<List<LivePrice>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }
}
