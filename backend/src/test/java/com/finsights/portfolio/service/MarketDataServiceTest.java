package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finsights.portfolio.domain.MarketHistoryCache;
import com.finsights.portfolio.dto.MarketHistoryResponse;
import com.finsights.portfolio.repository.MarketHistoryCacheRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketDataServiceTest {

    @Mock MarketHistoryCacheRepository historyCacheRepo;
    // A real mapper (not mocked) so the daily-series JSON round-trips exactly like it does in prod —
    // same JavaTimeModule Spring auto-registers on the bean this constructor normally receives.
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private MarketDataService service;

    @BeforeEach
    void setUp() {
        service = new MarketDataService(historyCacheRepo, objectMapper);
    }

    @Test
    void blankInputsReturnNothingWithoutTouchingTheNetwork() {
        assertThat(service.search("  ")).isEmpty();
        assertThat(service.search(null)).isEmpty();
        assertThat(service.quote(null)).isEmpty();
        assertThat(service.quote("")).isEmpty();
        assertThat(service.quotes(null)).isEmpty();
        assertThat(service.history(null, "1M")).isEmpty();
        assertThat(service.history("", "1M")).isEmpty();
    }

    @Test
    void intradayRangesNeverConsultTheSharedDailySeriesCache() {
        // Whatever the live feed does or doesn't return isn't the point, and isn't deterministic
        // across environments — a sandbox with no egress degrades to empty, but a CI runner with
        // real internet access gets real data back. What this test actually guarantees, regardless
        // of network reachability, is that 1D/1W never even look at the daily-series cache, since
        // they need intraday granularity it can't give.
        service.history("RELIANCE.NS", "1D");
        service.history("RELIANCE.NS", "1W");
        verify(historyCacheRepo, never()).findBySymbol(any());
    }

    @Test
    void derivedRangesServeFromAFreshCachedDailySeriesWithoutTouchingTheNetwork() throws Exception {
        List<MarketHistoryResponse.Point> points = List.of(
                new MarketHistoryResponse.Point(Instant.now().minus(200, ChronoUnit.DAYS), new BigDecimal("100")),
                new MarketHistoryResponse.Point(Instant.now().minus(10, ChronoUnit.DAYS), new BigDecimal("120")));
        when(historyCacheRepo.findBySymbol("RELIANCE.NS")).thenReturn(Optional.of(freshRow("RELIANCE.NS", points)));

        Optional<MarketHistoryResponse> result = service.history("RELIANCE.NS", "1Y");

        assertThat(result).isPresent();
        assertThat(result.get().currency()).isEqualTo("INR");
        assertThat(result.get().points()).hasSize(2);
        verify(historyCacheRepo, never()).save(any());
    }

    @Test
    void oneMonthRangeSlicesTheSharedDailySeriesInsteadOfReturningTheWholeYear() throws Exception {
        List<MarketHistoryResponse.Point> points = List.of(
                new MarketHistoryResponse.Point(Instant.now().minus(200, ChronoUnit.DAYS), new BigDecimal("100")), // outside 1M
                new MarketHistoryResponse.Point(Instant.now().minus(10, ChronoUnit.DAYS), new BigDecimal("120")),  // inside 1M
                new MarketHistoryResponse.Point(Instant.now().minus(2, ChronoUnit.DAYS), new BigDecimal("125")));  // inside 1M
        when(historyCacheRepo.findBySymbol("RELIANCE.NS")).thenReturn(Optional.of(freshRow("RELIANCE.NS", points)));

        Optional<MarketHistoryResponse> result = service.history("RELIANCE.NS", "1M");

        assertThat(result).isPresent();
        assertThat(result.get().points()).hasSize(2);
        assertThat(result.get().points()).allSatisfy(p -> assertThat(p.price()).isGreaterThanOrEqualTo(new BigDecimal("120")));
    }

    @Test
    void unknownRangeKeysFallBackToTheOneMonthDerivedSeries() throws Exception {
        List<MarketHistoryResponse.Point> points = List.of(
                new MarketHistoryResponse.Point(Instant.now().minus(10, ChronoUnit.DAYS), new BigDecimal("120")),
                new MarketHistoryResponse.Point(Instant.now().minus(2, ChronoUnit.DAYS), new BigDecimal("125")));
        when(historyCacheRepo.findBySymbol("RELIANCE.NS")).thenReturn(Optional.of(freshRow("RELIANCE.NS", points)));

        Optional<MarketHistoryResponse> result = service.history("RELIANCE.NS", "bogus");

        assertThat(result).isPresent();
        assertThat(result.get().points()).hasSize(2);
    }

    private MarketHistoryCache freshRow(String symbol, List<MarketHistoryResponse.Point> points) throws Exception {
        MarketHistoryCache row = new MarketHistoryCache();
        row.setSymbol(symbol);
        row.setCurrency("INR");
        row.setFetchedAt(Instant.now());
        row.setPointsJson(objectMapper.writeValueAsString(points));
        return row;
    }
}
