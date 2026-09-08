package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.WatchlistItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface WatchlistRepository extends JpaRepository<WatchlistItem, String> {
    List<WatchlistItem> findByUser_IdOrderByCreatedAtAsc(String userId);
    Optional<WatchlistItem> findByIdAndUser_Id(String id, String userId);

    @Transactional
    long deleteByUser_Id(String userId);
}
