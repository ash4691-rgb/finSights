package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.ValuationMethod;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface HoldingRepository extends JpaRepository<Holding, String> {
    List<Holding> findByUser_IdOrderBySortOrderAscUpdatedAtDesc(String userId);
    List<Holding> findByCategory_Id(String categoryId);
    Optional<Holding> findByIdAndUser_Id(String id, String userId);
    Optional<Holding> findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(String userId, String name, String broker);
    long countByUser_Id(String userId);

    /** Every distinct ticker/scheme symbol across every user's MARKET_PRICE holdings — what
     *  LivePriceRefreshScheduler keeps priced. Not scoped to one user: the live-price cache is
     *  shared, so the job needs the union across everyone. */
    @Query("select distinct h.tickerSymbol from Holding h where h.valuationMethod = :method and h.tickerSymbol is not null")
    List<String> findDistinctTickerSymbolsByValuationMethod(ValuationMethod method);

    @Transactional
    long deleteByUser_Id(String userId);

    @Transactional
    long deleteByCategory_Id(String categoryId);
}
