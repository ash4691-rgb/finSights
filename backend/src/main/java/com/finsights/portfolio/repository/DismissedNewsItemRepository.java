package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.DismissedNewsItem;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface DismissedNewsItemRepository extends JpaRepository<DismissedNewsItem, String> {
    boolean existsByUser_IdAndLink(String userId, String link);

    List<DismissedNewsItem> findByUser_Id(String userId);

    @Modifying
    @Transactional
    @Query("delete from DismissedNewsItem d where d.dismissedAt < :cutoff")
    void deleteOlderThan(Instant cutoff);
}
