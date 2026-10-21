package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.TrackedSymbol;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TrackedSymbolRepository;
import com.finsights.portfolio.repository.WatchlistRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrackedSymbolPoolServiceTest {

    @Mock TrackedSymbolRepository pool;
    @Mock HoldingRepository holdings;
    @Mock WatchlistRepository watchlist;

    private TrackedSymbolPoolService service;

    @BeforeEach
    void setUp() {
        service = new TrackedSymbolPoolService(pool, holdings, watchlist);
    }

    private TrackedSymbol tracked(String symbol) {
        return new TrackedSymbol(symbol);
    }

    @Test
    void syncUnionsMarketPriceHoldingsAndWatchlistAcrossEveryUser() {
        when(holdings.findDistinctTickerSymbolsByValuationMethod(ValuationMethod.MARKET_PRICE))
                .thenReturn(List.of("RELIANCE.NS"));
        when(watchlist.findDistinctTickerSymbols()).thenReturn(List.of("NIFTY.NS"));
        when(pool.findAll()).thenReturn(List.of());

        List<String> result = service.sync();

        assertThat(result).containsExactlyInAnyOrder("RELIANCE.NS", "NIFTY.NS");
    }

    @Test
    void syncAddsANewlyHeldSymbolNotYetInThePool() {
        when(holdings.findDistinctTickerSymbolsByValuationMethod(ValuationMethod.MARKET_PRICE))
                .thenReturn(List.of("RELIANCE.NS", "TCS.NS"));
        when(watchlist.findDistinctTickerSymbols()).thenReturn(List.of());
        when(pool.findAll()).thenReturn(List.of(tracked("RELIANCE.NS"))); // TCS.NS missing

        service.sync();

        ArgumentCaptor<TrackedSymbol> captor = ArgumentCaptor.forClass(TrackedSymbol.class);
        verify(pool).save(captor.capture());
        assertThat(captor.getValue().getSymbol()).isEqualTo("TCS.NS");
    }

    @Test
    void syncRemovesASymbolNoLongerHeldOrWatchedByAnyone() {
        when(holdings.findDistinctTickerSymbolsByValuationMethod(ValuationMethod.MARKET_PRICE))
                .thenReturn(List.of("RELIANCE.NS"));
        when(watchlist.findDistinctTickerSymbols()).thenReturn(List.of());
        when(pool.findAll()).thenReturn(List.of(tracked("RELIANCE.NS"), tracked("DELISTED.NS")));

        service.sync();

        verify(pool).deleteAllById(Set.of("DELISTED.NS"));
    }

    @Test
    void syncLeavesAnUnchangedPoolAlone() {
        when(holdings.findDistinctTickerSymbolsByValuationMethod(ValuationMethod.MARKET_PRICE))
                .thenReturn(List.of("RELIANCE.NS"));
        when(watchlist.findDistinctTickerSymbols()).thenReturn(List.of());
        when(pool.findAll()).thenReturn(List.of(tracked("RELIANCE.NS")));

        service.sync();

        verify(pool, never()).save(any());
        verify(pool, never()).deleteAllById(anySet());
    }

    @Test
    void syncIgnoresBlankTickerSymbols() {
        when(holdings.findDistinctTickerSymbolsByValuationMethod(ValuationMethod.MARKET_PRICE))
                .thenReturn(List.of("RELIANCE.NS", "  "));
        when(watchlist.findDistinctTickerSymbols()).thenReturn(List.of());
        when(pool.findAll()).thenReturn(List.of());

        List<String> result = service.sync();

        assertThat(result).containsExactly("RELIANCE.NS");
    }
}
