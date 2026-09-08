package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.Category;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface CategoryRepository extends JpaRepository<Category, String> {
    List<Category> findByUser_IdOrderBySortOrderAscUpdatedAtDesc(String userId);
    Optional<Category> findByIdAndUser_Id(String id, String userId);
    long countByUser_Id(String userId);

    @Transactional
    long deleteByUser_Id(String userId);
}
