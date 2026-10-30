package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time upgrade for holdings saved before manual types existed: any holding that already
 * carries an area becomes REAL_ESTATE, with unit prices derived from its stored values. Safe to
 * re-run — it only touches holdings that still have no manual type.
 */
@Component
class RealEstateBackfill implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RealEstateBackfill.class);
    private final HoldingRepository holdings;

    RealEstateBackfill(HoldingRepository holdings) {
        this.holdings = holdings;
    }

    @Override
    public void run(ApplicationArguments args) {
        int updated = backfill();
        if (updated > 0) log.info("Marked {} existing area-based holdings as real estate", updated);
    }

    @Transactional
    int backfill() {
        List<Holding> legacy = holdings.findByAreaIsNotNullAndManualTypeIsNull();
        for (Holding holding : legacy) {
            holding.setManualType(ManualType.REAL_ESTATE);
            holding.setPurchaseUnitPrice(perUnit(holding.getInvestedValue(), holding.getArea()));
            holding.setCurrentUnitPrice(perUnit(holding.getCurrentValue(), holding.getArea()));
        }
        holdings.saveAll(legacy);
        return legacy.size();
    }

    private BigDecimal perUnit(BigDecimal value, BigDecimal area) {
        if (value == null || area == null || area.signum() <= 0) return null;
        return value.divide(area, 4, RoundingMode.HALF_UP);
    }
}
