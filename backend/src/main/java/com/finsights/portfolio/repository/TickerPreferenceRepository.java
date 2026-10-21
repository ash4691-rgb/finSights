package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.TickerPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TickerPreferenceRepository extends JpaRepository<TickerPreference, String> {
    Optional<TickerPreference> findByUser_IdAndSymbol(String userId, String symbol);
    List<TickerPreference> findByUser_Id(String userId);
}
