package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.Holding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface HoldingRepository extends JpaRepository<Holding, String> {
    List<Holding> findByUser_IdOrderBySortOrderAscUpdatedAtDesc(String userId);
    Optional<Holding> findByIdAndUser_Id(String id, String userId);
    Optional<Holding> findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(String userId, String name, String broker);
    long countByUser_Id(String userId);

    @Transactional
    long deleteByUser_Id(String userId);

    @Transactional
    long deleteByCategory_Id(String categoryId);
}
