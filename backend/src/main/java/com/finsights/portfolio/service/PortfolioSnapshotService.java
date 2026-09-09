package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.PortfolioSnapshot;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.PortfolioTimelineResponse;
import com.finsights.portfolio.dto.PortfolioTimelineResponse.CategoryPoint;
import com.finsights.portfolio.dto.PortfolioTimelineResponse.Week;
import com.finsights.portfolio.repository.PortfolioSnapshotRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly portfolio history, captured per category. A snapshot for the current ISO week
 * is taken lazily the first time the owner opens a page that needs it, so the trail
 * fills in whenever the app is actually used — no background job required.
 */
@Service
public class PortfolioSnapshotService {

    private final PortfolioSnapshotRepository snapshots;
    private final HoldingService holdingService;
    private final CurrentUserService currentUser;
    private final FxRateService fx;

    public PortfolioSnapshotService(PortfolioSnapshotRepository snapshots, HoldingService holdingService,
                                    CurrentUserService currentUser, FxRateService fx) {
        this.snapshots = snapshots;
        this.holdingService = holdingService;
        this.currentUser = currentUser;
        this.fx = fx;
    }

    /** Monday of the ISO week that contains {@code date}. */
    static LocalDate weekOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Capture this week's per-category values for the current user, unless already done. */
    @Transactional
    public void captureCurrentWeek() {
        UserAccount user = currentUser.currentUser();
        LocalDate week = weekOf(LocalDate.now());
        if (snapshots.existsByUser_IdAndWeekOf(user.getId(), week)) return;

        Map<String, PortfolioSnapshot> byCategory = new LinkedHashMap<>();
        for (HoldingResponse h : holdingService.list(user.getBaseCurrency())) {
            PortfolioSnapshot row = byCategory.computeIfAbsent(h.categoryId(), id -> {
                PortfolioSnapshot fresh = new PortfolioSnapshot();
                fresh.setUser(user);
                fresh.setCategoryId(h.categoryId());
                fresh.setCategoryName(h.categoryName());
                fresh.setKind(h.kind());
                fresh.setWeekOf(week);
                return fresh;
            });
            row.setInvestedValue(row.getInvestedValue().add(h.investedValue()));
            row.setCurrentValue(row.getCurrentValue().add(h.currentValue()));
        }
        if (!byCategory.isEmpty()) snapshots.saveAll(byCategory.values());
    }

    @Transactional(readOnly = true)
    public PortfolioTimelineResponse timeline(String displayCurrency) {
        UserAccount user = currentUser.currentUser();
        String base = user.getBaseCurrency();
        String target = displayCurrency == null || displayCurrency.isBlank()
                ? base : displayCurrency.trim().toUpperCase();

        Map<LocalDate, List<PortfolioSnapshot>> byWeek = new TreeMap<>();
        for (PortfolioSnapshot s : snapshots.findByUser_IdOrderByWeekOfAscCategoryNameAsc(user.getId())) {
            byWeek.computeIfAbsent(s.getWeekOf(), k -> new ArrayList<>()).add(s);
        }

        List<Week> weeks = new ArrayList<>();
        for (Map.Entry<LocalDate, List<PortfolioSnapshot>> entry : byWeek.entrySet()) {
            List<CategoryPoint> categories = new ArrayList<>();
            BigDecimal assetsInvested = BigDecimal.ZERO;
            BigDecimal assetsCurrent = BigDecimal.ZERO;
            BigDecimal liabilities = BigDecimal.ZERO;
            for (PortfolioSnapshot s : entry.getValue()) {
                BigDecimal invested = fx.convert(s.getInvestedValue(), base, target);
                BigDecimal current = fx.convert(s.getCurrentValue(), base, target);
                categories.add(new CategoryPoint(s.getCategoryId(), s.getCategoryName(), s.getKind().name(), invested, current));
                if (s.getKind() == HoldingKind.LIABILITY) {
                    liabilities = liabilities.add(current);
                } else {
                    assetsInvested = assetsInvested.add(invested);
                    assetsCurrent = assetsCurrent.add(current);
                }
            }
            weeks.add(new Week(entry.getKey(), assetsInvested, assetsCurrent, liabilities,
                    assetsCurrent.subtract(liabilities), categories));
        }
        return new PortfolioTimelineResponse(weeks);
    }

    public void deleteForUser(String userId) {
        snapshots.deleteByUser_Id(userId);
    }
}
