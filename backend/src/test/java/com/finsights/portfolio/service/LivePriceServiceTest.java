package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.LivePrice;
import com.finsights.portfolio.domain.PriceHistoryPoint;
import com.finsights.portfolio.dto.MarketHistoryResponse;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.repository.LivePriceRepository;
import com.finsights.portfolio.repository.PriceHistoryPointRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    @Mock PriceHistoryPointRepository priceHistory;
    private LivePriceService service;

    @BeforeEach
    void setUp() {
        service = new LivePriceService(repository, marketData, priceHistory);
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

    // --- price history durability ------------------------------------------------------------

    @Test
    void refreshAllUpsertsTodaysHistoryPointForEveryQuotedSymbol() {
        when(marketData.quotes(any())).thenReturn(Map.of("RELIANCE.NS",
                new MarketQuoteResponse("RELIANCE.NS", "Reliance", new BigDecimal("2950.00"), "INR", Instant.now())));
        when(repository.findAllById(any())).thenReturn(List.of());
        when(priceHistory.findBySymbolAndPriceDate(eq("RELIANCE.NS"), any())).thenReturn(Optional.empty());

        service.refreshAll(Set.of("RELIANCE.NS"));

        ArgumentCaptor<PriceHistoryPoint> captor = ArgumentCaptor.forClass(PriceHistoryPoint.class);
        verify(priceHistory).save(captor.capture());
        assertThat(captor.getValue().getSymbol()).isEqualTo("RELIANCE.NS");
        assertThat(captor.getValue().getPriceDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(captor.getValue().getPrice()).isEqualByComparingTo("2950.00");
    }

    @Test
    void refreshAllUpdatesTodaysHistoryPointInPlaceRatherThanAddingASecondOneForTheSameDay() {
        PriceHistoryPoint existingPoint = new PriceHistoryPoint();
        existingPoint.setSymbol("RELIANCE.NS");
        existingPoint.setPriceDate(LocalDate.now(ZoneOffset.UTC));
        existingPoint.setPrice(new BigDecimal("2900.00"));
        when(marketData.quotes(any())).thenReturn(Map.of("RELIANCE.NS",
                new MarketQuoteResponse("RELIANCE.NS", "Reliance", new BigDecimal("2960.00"), "INR", Instant.now())));
        when(repository.findAllById(any())).thenReturn(List.of());
        when(priceHistory.findBySymbolAndPriceDate(eq("RELIANCE.NS"), any())).thenReturn(Optional.of(existingPoint));

        service.refreshAll(Set.of("RELIANCE.NS"));

        assertThat(existingPoint.getPrice()).isEqualByComparingTo("2960.00"); // same row, updated — not a second insert
        verify(priceHistory).save(existingPoint);
    }

    @Test
    void refreshAllPrunesHistoryPointsOlderThanTheRetentionWindow() {
        when(marketData.quotes(any())).thenReturn(Map.of()); // empty quotes → upsert() short-circuits before touching repository

        service.refreshAll(Set.of("RELIANCE.NS"));

        verify(priceHistory).deleteOlderThan(LocalDate.now(ZoneOffset.UTC).minusDays(366));
    }

    @Test
    void ownHistoryNeverServesIntradayRangesSinceTheTableIsDailyOnly() {
        assertThat(service.ownHistory("RELIANCE.NS", "1D")).isEmpty();
        assertThat(service.ownHistory("RELIANCE.NS", "1W")).isEmpty();
        verify(priceHistory, never()).findBySymbolAndPriceDateGreaterThanEqualOrderByPriceDateAsc(any(), any());
    }

    @Test
    void ownHistoryReturnsEmptyWhenThereIsNotEnoughBackHistoryYetForTheRequestedRange() {
        // Only 5 days of accumulated history — nowhere near covering "1M" (31 days back).
        List<PriceHistoryPoint> fewPoints = List.of(
                point("RELIANCE.NS", LocalDate.now(ZoneOffset.UTC).minusDays(4), "2900"),
                point("RELIANCE.NS", LocalDate.now(ZoneOffset.UTC), "2950"));
        when(priceHistory.findBySymbolAndPriceDateGreaterThanEqualOrderByPriceDateAsc(eq("RELIANCE.NS"), any()))
                .thenReturn(fewPoints);

        assertThat(service.ownHistory("RELIANCE.NS", "1M")).isEmpty();
    }

    @Test
    void ownHistoryServesFromAccumulatedDailyPointsOnceThereIsEnoughCoverage() {
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(31);
        List<PriceHistoryPoint> points = List.of(
                point("RELIANCE.NS", start, "2800"),
                point("RELIANCE.NS", LocalDate.now(ZoneOffset.UTC), "2950"));
        when(priceHistory.findBySymbolAndPriceDateGreaterThanEqualOrderByPriceDateAsc(eq("RELIANCE.NS"), any()))
                .thenReturn(points);

        Optional<MarketHistoryResponse> result = service.ownHistory("RELIANCE.NS", "1M");

        assertThat(result).isPresent();
        assertThat(result.get().symbol()).isEqualTo("RELIANCE.NS");
        assertThat(result.get().currency()).isEqualTo("INR");
        assertThat(result.get().points()).hasSize(2);
        assertThat(result.get().points().get(1).price()).isEqualByComparingTo("2950");
    }

    @Test
    void supportsOwnHistoryIsTrueOnlyForTheDailyGranularityRanges() {
        assertThat(service.supportsOwnHistory("1M")).isTrue();
        assertThat(service.supportsOwnHistory("3M")).isTrue();
        assertThat(service.supportsOwnHistory("6M")).isTrue();
        assertThat(service.supportsOwnHistory("1Y")).isTrue();
        assertThat(service.supportsOwnHistory("1D")).isFalse();
        assertThat(service.supportsOwnHistory("1W")).isFalse();
    }

    // --- backfillHistory() ---------------------------------------------------------------------

    @Test
    void backfillHistoryInsertsOneRowPerPointForASymbolWithNoExistingHistory() {
        when(priceHistory.findBySymbol("RELIANCE.NS")).thenReturn(List.of());
        MarketHistoryResponse live = new MarketHistoryResponse("RELIANCE.NS", "INR", List.of(
                new MarketHistoryResponse.Point(Instant.parse("2026-09-01T00:00:00Z"), new BigDecimal("2800")),
                new MarketHistoryResponse.Point(Instant.parse("2026-09-02T00:00:00Z"), new BigDecimal("2820"))));

        service.backfillHistory("RELIANCE.NS", live);

        ArgumentCaptor<List<PriceHistoryPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(priceHistory).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).extracting(PriceHistoryPoint::getPriceDate)
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));
        assertThat(captor.getValue()).extracting(p -> p.getPrice().toString()).containsExactly("2800", "2820");
    }

    @Test
    void backfillHistoryUpdatesAnExistingDayInPlaceRatherThanDuplicatingIt() {
        PriceHistoryPoint existingPoint = point("RELIANCE.NS", LocalDate.of(2026, 9, 1), "2750");
        when(priceHistory.findBySymbol("RELIANCE.NS")).thenReturn(List.of(existingPoint));
        MarketHistoryResponse live = new MarketHistoryResponse("RELIANCE.NS", "INR", List.of(
                new MarketHistoryResponse.Point(Instant.parse("2026-09-01T00:00:00Z"), new BigDecimal("2800"))));

        service.backfillHistory("RELIANCE.NS", live);

        assertThat(existingPoint.getPrice()).isEqualByComparingTo("2800"); // same row, updated in place
        ArgumentCaptor<List<PriceHistoryPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(priceHistory).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    void backfillHistoryDoesNothingForAnEmptySeries() {
        service.backfillHistory("RELIANCE.NS", new MarketHistoryResponse("RELIANCE.NS", "INR", List.of()));

        verify(priceHistory, never()).findBySymbol(any());
        verify(priceHistory, never()).saveAll(any());
    }

    private PriceHistoryPoint point(String symbol, LocalDate date, String price) {
        PriceHistoryPoint p = new PriceHistoryPoint();
        p.setSymbol(symbol);
        p.setPriceDate(date);
        p.setPrice(new BigDecimal(price));
        p.setCurrency("INR");
        return p;
    }
}
