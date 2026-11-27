package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.Goal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface GoalRepository extends JpaRepository<Goal, String> {
    List<Goal> findByUserIdOrderByTargetDateAsc(String userId);

    Optional<Goal> findByIdAndUserId(String id, String userId);

    /** Derived delete loads each goal first, so its goal_categories rows go too. */
    @Transactional
    void deleteByUserId(String userId);
}
