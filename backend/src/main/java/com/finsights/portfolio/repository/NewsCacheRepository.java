package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.NewsCache;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsCacheRepository extends JpaRepository<NewsCache, String> {
    Optional<NewsCache> findBySymbol(String symbol);
}
