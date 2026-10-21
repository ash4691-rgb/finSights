package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.TrackedSymbol;
import org.springframework.data.jpa.repository.JpaRepository;

/** Symbol is the id itself — findAll/saveAll/deleteAllById already cover every access pattern
 *  TrackedSymbolPoolService needs. */
public interface TrackedSymbolRepository extends JpaRepository<TrackedSymbol, String> { }
