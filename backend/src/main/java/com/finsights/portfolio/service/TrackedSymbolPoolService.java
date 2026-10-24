package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.TrackedSymbol;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TrackedSymbolRepository;
import com.finsights.portfolio.repository.WatchlistRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@link TrackedSymbol} in sync with reality — every MARKET_PRICE-valued ticker and every
 * watchlisted symbol across ALL users, same union {@link LivePriceRefreshScheduler} and
 * {@link NewsRefreshScheduler} used to each compute themselves before this existed. Deliberately
 * NOT user-scoped: the price/news caches it feeds are shared across everyone (see MarketHistoryCache,
 * NewsCache, LivePrice's own doc comments), so the pool has to be the union too. Cheap (two
 * indexed DISTINCT queries plus a diff) — safe for both schedulers to call at the top of every run.
 */
@Service
public class TrackedSymbolPoolService {
    private final TrackedSymbolRepository pool;
    private final HoldingRepository holdings;
    private final WatchlistRepository watchlist;

    public TrackedSymbolPoolService(TrackedSymbolRepository pool, HoldingRepository holdings, WatchlistRepository watchlist) {
        this.pool = pool;
        this.holdings = holdings;
        this.watchlist = watchlist;
    }

    @Transactional
    public List<String> sync() {
        Set<String> desired = new HashSet<>();
        holdings.findDistinctTickerSymbolsByValuationMethod(ValuationMethod.MARKET_PRICE).forEach(desired::add);
        watchlist.findDistinctTickerSymbols().forEach(desired::add);
        desired.removeIf(s -> s == null || s.isBlank());

        Set<String> current = new HashSet<>();
        pool.findAll().forEach(row -> current.add(row.getSymbol()));

        Set<String> toAdd = new HashSet<>(desired);
        toAdd.removeAll(current);
        Set<String> toRemove = new HashSet<>(current);
        toRemove.removeAll(desired);

        toAdd.forEach(symbol -> pool.save(new TrackedSymbol(symbol)));
        if (!toRemove.isEmpty()) pool.deleteAllById(toRemove);

        return new ArrayList<>(desired);
    }
}
