package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.PriceHistoryPoint;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PriceHistoryPointRepository extends JpaRepository<PriceHistoryPoint, String> {
    Optional<PriceHistoryPoint> findBySymbolAndPriceDate(String symbol, LocalDate priceDate);

    List<PriceHistoryPoint> findBySymbolAndPriceDateGreaterThanEqualOrderByPriceDateAsc(String symbol, LocalDate from);

    /** Every point currently stored for a symbol, regardless of date — used by backfillHistory()
     *  to upsert a live-fetched series in one batch rather than one row at a time. */
    List<PriceHistoryPoint> findBySymbol(String symbol);

    @Modifying
    @Transactional
    @Query("delete from PriceHistoryPoint p where p.priceDate < :cutoff")
    void deleteOlderThan(LocalDate cutoff);
}
