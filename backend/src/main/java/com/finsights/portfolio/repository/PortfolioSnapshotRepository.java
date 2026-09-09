package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.PortfolioSnapshot;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, String> {

    List<PortfolioSnapshot> findByUser_IdOrderByWeekOfAscCategoryNameAsc(String userId);

    List<PortfolioSnapshot> findByUser_IdAndWeekOf(String userId, LocalDate weekOf);

    boolean existsByUser_IdAndWeekOf(String userId, LocalDate weekOf);

    @Transactional
    long deleteByUser_Id(String userId);
}
