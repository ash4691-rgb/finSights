package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.TagSuggestion;
import com.finsights.portfolio.domain.ValuationMethod;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface TagSuggestionRepository extends JpaRepository<TagSuggestion, String> {

    Optional<TagSuggestion> findByUser_IdAndTagAndCategoryIdAndValuationMethod(
            String userId, String tag, String categoryId, ValuationMethod valuationMethod);

    List<TagSuggestion> findByUser_IdOrderByUsageCountDescUpdatedAtDesc(String userId);

    @Transactional
    long deleteByUser_Id(String userId);
}
