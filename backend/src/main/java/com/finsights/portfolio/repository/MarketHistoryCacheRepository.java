package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.MarketHistoryCache;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketHistoryCacheRepository extends JpaRepository<MarketHistoryCache, String> {
    Optional<MarketHistoryCache> findBySymbol(String symbol);
}
