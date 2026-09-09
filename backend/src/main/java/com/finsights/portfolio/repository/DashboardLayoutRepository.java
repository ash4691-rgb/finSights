package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.DashboardLayout;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface DashboardLayoutRepository extends JpaRepository<DashboardLayout, String> {
    List<DashboardLayout> findByUser_Id(String userId);
    Optional<DashboardLayout> findByUser_IdAndPage(String userId, String page);

    @Transactional
    long deleteByUser_Id(String userId);
}
