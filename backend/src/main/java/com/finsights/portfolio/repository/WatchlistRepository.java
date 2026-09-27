package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.WatchlistItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface WatchlistRepository extends JpaRepository<WatchlistItem, String> {
    List<WatchlistItem> findByUser_IdOrderByCreatedAtAsc(String userId);
    Optional<WatchlistItem> findByIdAndUser_Id(String id, String userId);
    boolean existsByUser_IdAndTickerSymbolIgnoreCase(String userId, String tickerSymbol);
    boolean existsByUser_IdAndTickerSymbolIgnoreCaseAndIdNot(String userId, String tickerSymbol, String id);

    /** Every distinct ticker across every user's watchlist — see HoldingRepository's twin query
     *  for why this isn't scoped to one user. */
    @Query("select distinct w.tickerSymbol from WatchlistItem w where w.tickerSymbol is not null")
    List<String> findDistinctTickerSymbols();

    @Transactional
    long deleteByUser_Id(String userId);
}
