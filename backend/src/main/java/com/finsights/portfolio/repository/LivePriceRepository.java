package com.finsights.portfolio.repository;

import com.finsights.portfolio.domain.LivePrice;
import org.springframework.data.jpa.repository.JpaRepository;

/** Symbol is the id itself, so the inherited findAllById/save(All) already cover every access
 *  pattern LivePriceService needs — no custom query methods required. */
public interface LivePriceRepository extends JpaRepository<LivePrice, String> { }
