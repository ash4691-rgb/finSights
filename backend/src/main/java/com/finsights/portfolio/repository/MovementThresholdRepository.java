package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.MovementThreshold;
import com.finsights.portfolio.domain.UserAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovementThresholdRepository extends JpaRepository<MovementThreshold, String> {
    Optional<MovementThreshold> findByUser(UserAccount user);
}
