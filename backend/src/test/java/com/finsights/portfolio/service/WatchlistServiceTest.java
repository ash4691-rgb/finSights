package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.WatchlistItem;
import com.finsights.portfolio.dto.MarketHistoryResponse;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.dto.WatchlistCreateRequest;
import com.finsights.portfolio.dto.WatchlistResponse;
import com.finsights.portfolio.dto.WatchlistUpdateRequest;
import com.finsights.portfolio.repository.WatchlistRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    @Mock WatchlistRepository repository;
    @Mock PriceSnapshotService snapshots;
    @Mock CurrentUserService currentUser;
    @Mock FxRateService fx;
    @Mock MarketDataService marketData;
    private WatchlistService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new WatchlistService(repository, snapshots, currentUser, fx, marketData);
        user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
    }

    @Test
    void createRejectsATickerAlreadyOnTheUsersWatchlist() {
        when(repository.existsByUser_IdAndTickerSymbolIgnoreCase(user.getId(), "NIFTY")).thenReturn(true);
        WatchlistCreateRequest request = new WatchlistCreateRequest("Nifty 50", "NIFTY", "INR", null, new BigDecimal("25000"));

        try {
            service.create(request);
            org.junit.jupiter.api.Assertions.fail("expected ResponseStatusException");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(409);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void createAllowsATickerNotAlreadyTracked() {
        when(repository.existsByUser_IdAndTickerSymbolIgnoreCase(user.getId(), "NIFTY")).thenReturn(false);
        when(repository.save(any(WatchlistItem.class))).thenAnswer(inv -> {
            WatchlistItem item = inv.getArgument(0);
            return item;
        });
        WatchlistCreateRequest request = new WatchlistCreateRequest("Nifty 50", "NIFTY", "INR", null, new BigDecimal("25000"));

        WatchlistResponse response = service.create(request);

        assertThat(response.name()).isEqualTo("Nifty 50");
        assertThat(response.tickerSymbol()).isEqualTo("NIFTY");
    }

    @Test
    void createDuplicateCheckIsCaseInsensitive() {
        when(repository.existsByUser_IdAndTickerSymbolIgnoreCase(user.getId(), "nifty")).thenReturn(true);
        WatchlistCreateRequest request = new WatchlistCreateRequest("Nifty 50", "nifty", "INR", null, new BigDecimal("25000"));

        try {
            service.create(request);
            org.junit.jupiter.api.Assertions.fail("expected ResponseStatusException");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(409);
        }
    }

    @Test
    void updateRejectsRenamingToAnotherItemsTicker() {
        WatchlistItem existing = new WatchlistItem();
        existing.setUser(user);
        existing.setName("Old name");
        existing.setTickerSymbol("OLD");
        when(repository.findByIdAndUser_Id("w-1", user.getId())).thenReturn(Optional.of(existing));
        when(repository.existsByUser_IdAndTickerSymbolIgnoreCaseAndIdNot(user.getId(), "NEW", "w-1")).thenReturn(true);
        WatchlistUpdateRequest request = new WatchlistUpdateRequest("New name", "NEW", "USD", null);

        try {
            service.update("w-1", request);
            org.junit.jupiter.api.Assertions.fail("expected ResponseStatusException");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(409);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void updateAllowsKeepingItsOwnTicker() {
        WatchlistItem existing = new WatchlistItem();
        existing.setUser(user);
        existing.setName("Old name");
        existing.setTickerSymbol("NIFTY");
        when(repository.findByIdAndUser_Id("w-1", user.getId())).thenReturn(Optional.of(existing));
        when(repository.existsByUser_IdAndTickerSymbolIgnoreCaseAndIdNot(user.getId(), "NIFTY", "w-1")).thenReturn(false);
        when(repository.save(any(WatchlistItem.class))).thenAnswer(inv -> inv.getArgument(0));
        WatchlistUpdateRequest request = new WatchlistUpdateRequest("Nifty 50", "NIFTY", "INR", null);

        WatchlistResponse response = service.update("w-1", request);

        assertThat(response.name()).isEqualTo("Nifty 50");
    }

    @Test
    void nativeListIsNotConverted() {
        when(repository.findByUser_IdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of());

        service.list();

        verify(fx, never()).convert(any(), anyString(), anyString());
    }

    @Test
    void displayCurrencyListConvertsEachItemsValue() {
        WatchlistItem item = new WatchlistItem();
        item.setUser(user);
        item.setName("Reliance");
        item.setTickerSymbol("RELIANCE.NS");
        item.setCurrency("INR");
        when(repository.findByUser_IdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(item));
        when(snapshots.latest(any(), any())).thenReturn(new BigDecimal("2934.55"));
        when(fx.convert(eq(new BigDecimal("2934.55")), eq("INR"), eq("USD"))).thenReturn(new BigDecimal("35.20"));

        List<WatchlistResponse> result = service.list("USD");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currentValue()).isEqualByComparingTo("35.20");
        assertThat(result.get(0).currency()).isEqualTo("USD");
    }

    @Test
    void blankDisplayCurrencyIsANoOp() {
        when(repository.findByUser_IdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of());

        service.list("");

        verify(fx, never()).convert(any(), anyString(), anyString());
    }

    @Test
    void staleTickerBackedItemIsRefreshedFromTheLiveFeedOnList() {
        WatchlistItem item = new WatchlistItem();
        item.setUser(user);
        item.setName("Reliance");
        item.setTickerSymbol("RELIANCE.NS");
        item.setCurrency("INR");
        when(repository.findByUser_IdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(item));
        when(snapshots.latestRecordedAt(any(), any())).thenReturn(null); // never priced yet
        when(marketData.quotes(any())).thenReturn(Map.of("RELIANCE.NS",
                new MarketQuoteResponse("RELIANCE.NS", "Reliance", new BigDecimal("2950.00"), "INR", Instant.now())));

        service.list();

        verify(snapshots).record(eq(SnapshotSubject.WATCHLIST), any(), eq(user), eq(new BigDecimal("2950.00")));
    }

    @Test
    void recentlySnapshottedItemIsNotRefetchedFromTheLiveFeed() {
        WatchlistItem item = new WatchlistItem();
        item.setUser(user);
        item.setName("Reliance");
        item.setTickerSymbol("RELIANCE.NS");
        item.setCurrency("INR");
        when(repository.findByUser_IdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(item));
        when(snapshots.latestRecordedAt(any(), any())).thenReturn(Instant.now().minusSeconds(30)); // well within the 15-minute window

        service.list();

        verify(marketData, never()).quotes(any());
    }

    @Test
    void itemsWithoutATickerAreNeverLiveRefreshed() {
        WatchlistItem item = new WatchlistItem();
        item.setUser(user);
        item.setName("Custom index");
        when(repository.findByUser_IdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(item));

        service.list();

        verify(marketData, never()).quotes(any());
    }

    @Test
    void listBackfillsRealHistoricalPricesWhenNoDeepHistoryExistsYet() {
        WatchlistItem item = new WatchlistItem();
        item.setUser(user);
        item.setName("Reliance");
        item.setTickerSymbol("RELIANCE.NS");
        item.setCurrency("INR");
        when(repository.findByUser_IdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(item));
        when(snapshots.latestRecordedAt(any(), any())).thenReturn(null);
        when(marketData.quotes(any())).thenReturn(Map.of("RELIANCE.NS",
                new MarketQuoteResponse("RELIANCE.NS", "Reliance", new BigDecimal("2950.00"), "INR", Instant.now())));
        when(snapshots.hasSnapshotAtOrBefore(any(), any(), any())).thenReturn(false);
        when(marketData.history(eq("RELIANCE.NS"), eq("1Y"))).thenReturn(Optional.of(new MarketHistoryResponse("RELIANCE.NS", "INR", List.of(
                new MarketHistoryResponse.Point(Instant.now().minus(300, ChronoUnit.DAYS), new BigDecimal("2800")),
                new MarketHistoryResponse.Point(Instant.now().minus(100, ChronoUnit.DAYS), new BigDecimal("2900"))))));

        service.list();

        verify(snapshots, times(2)).recordAt(eq(SnapshotSubject.WATCHLIST), any(), eq(user), any(), any());
    }

    @Test
    void listSkipsTheHistoryBackfillOnceDeepHistoryAlreadyExists() {
        WatchlistItem item = new WatchlistItem();
        item.setUser(user);
        item.setName("Reliance");
        item.setTickerSymbol("RELIANCE.NS");
        item.setCurrency("INR");
        when(repository.findByUser_IdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(item));
        when(snapshots.latestRecordedAt(any(), any())).thenReturn(null);
        when(marketData.quotes(any())).thenReturn(Map.of("RELIANCE.NS",
                new MarketQuoteResponse("RELIANCE.NS", "Reliance", new BigDecimal("2950.00"), "INR", Instant.now())));
        when(snapshots.hasSnapshotAtOrBefore(any(), any(), any())).thenReturn(true);

        service.list();

        verify(marketData, never()).history(any(), any());
    }
}
