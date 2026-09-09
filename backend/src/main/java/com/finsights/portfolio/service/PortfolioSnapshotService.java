package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.PortfolioSnapshot;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.PortfolioTimelineResponse;
import com.finsights.portfolio.dto.PortfolioTimelineResponse.CategoryPoint;
import com.finsights.portfolio.dto.PortfolioTimelineResponse.Week;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.PortfolioSnapshotRepository;
import com.finsights.portfolio.repository.UserAccountRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly portfolio history, captured per category (keyed on the Monday of the ISO week).
 * A scheduled job records it every Monday; opening the dashboard or Insights also captures
 * lazily if the week is still missing, and the user can force a refresh from the UI.
 */
@Service
public class PortfolioSnapshotService {

    private final PortfolioSnapshotRepository snapshots;
    private final HoldingRepository holdings;
    private final UserAccountRepository users;
    private final ValuationService valuations;
    private final CurrentUserService currentUser;
    private final FxRateService fx;

    public PortfolioSnapshotService(PortfolioSnapshotRepository snapshots, HoldingRepository holdings,
                                    UserAccountRepository users, ValuationService valuations,
                                    CurrentUserService currentUser, FxRateService fx) {
        this.snapshots = snapshots;
        this.holdings = holdings;
        this.users = users;
        this.valuations = valuations;
        this.currentUser = currentUser;
        this.fx = fx;
    }

    /** Monday of the ISO week that contains {@code date}. */
    static LocalDate weekOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Capture this week for the signed-in user; {@code force} re-captures even if the week is already on file. */
    @Transactional
    public void captureCurrentUser(boolean force) {
        captureFor(currentUser.currentUser(), force);
    }

    /** All accounts, for the weekly scheduled job — the scheduler calls {@link #captureFor} per user so each runs in its own transaction. */
    @Transactional(readOnly = true)
    public List<UserAccount> allUsers() {
        return users.findAll();
    }

    @Transactional
    public void captureFor(UserAccount user, boolean force) {
        LocalDate week = weekOf(LocalDate.now());
        List<PortfolioSnapshot> existing = snapshots.findByUser_IdAndWeekOf(user.getId(), week);
        if (!existing.isEmpty() && !force) return;

        Map<String, Aggregate> byCategory = aggregateByCategory(user);
        if (byCategory.isEmpty() && existing.isEmpty()) return;

        Map<String, PortfolioSnapshot> stale = existing.stream()
                .collect(Collectors.toMap(PortfolioSnapshot::getCategoryId, s -> s, (a, b) -> a));
        Instant now = Instant.now();
        List<PortfolioSnapshot> toSave = new ArrayList<>();
        for (Map.Entry<String, Aggregate> entry : byCategory.entrySet()) {
            Aggregate agg = entry.getValue();
            PortfolioSnapshot row = stale.remove(entry.getKey());
            if (row == null) {
                row = new PortfolioSnapshot();
                row.setUser(user);
                row.setCategoryId(entry.getKey());
                row.setWeekOf(week);
            }
            row.setCategoryName(agg.name);
            row.setKind(agg.kind);
            row.setInvestedValue(agg.invested.setScale(2, RoundingMode.HALF_UP));
            row.setCurrentValue(agg.current.setScale(2, RoundingMode.HALF_UP));
            row.setRecordedAt(now);
            toSave.add(row);
        }
        snapshots.saveAll(toSave);
        if (!stale.isEmpty()) snapshots.deleteAll(stale.values()); // categories that vanished this week
    }

    @Transactional(readOnly = true)
    public PortfolioTimelineResponse timeline(String displayCurrency) {
        UserAccount user = currentUser.currentUser();
        String base = user.getBaseCurrency();
        String target = displayCurrency == null || displayCurrency.isBlank()
                ? base : displayCurrency.trim().toUpperCase();

        Map<LocalDate, List<PortfolioSnapshot>> byWeek = new TreeMap<>();
        Instant lastCapturedAt = null;
        for (PortfolioSnapshot s : snapshots.findByUser_IdOrderByWeekOfAscCategoryNameAsc(user.getId())) {
            byWeek.computeIfAbsent(s.getWeekOf(), k -> new ArrayList<>()).add(s);
            if (lastCapturedAt == null || s.getRecordedAt().isAfter(lastCapturedAt)) lastCapturedAt = s.getRecordedAt();
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

        boolean capturedToday = lastCapturedAt != null
                && lastCapturedAt.atZone(ZoneId.systemDefault()).toLocalDate().equals(LocalDate.now());
        return new PortfolioTimelineResponse(weeks, lastCapturedAt, capturedToday);
    }

    public void deleteForUser(String userId) {
        snapshots.deleteByUser_Id(userId);
    }

    /** Per-category invested + current value, converted to the user's base currency. */
    private Map<String, Aggregate> aggregateByCategory(UserAccount user) {
        String base = user.getBaseCurrency();
        Map<String, Aggregate> byCategory = new LinkedHashMap<>();
        for (Holding h : holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(user.getId())) {
            Category category = h.getCategory();
            if (category == null) continue;
            String holdingCurrency = h.getCurrency() == null || h.getCurrency().isBlank() ? base : h.getCurrency();
            BigDecimal invested = fx.convert(h.getInvestedValue() == null ? BigDecimal.ZERO : h.getInvestedValue(),
                    holdingCurrency, base);
            BigDecimal current = fx.convert(valuations.currentValue(h), holdingCurrency, base);
            Aggregate agg = byCategory.computeIfAbsent(category.getId(),
                    k -> new Aggregate(category.getName(), category.getKind()));
            agg.invested = agg.invested.add(invested);
            agg.current = agg.current.add(current);
        }
        return byCategory;
    }

    private static final class Aggregate {
        private final String name;
        private final HoldingKind kind;
        private BigDecimal invested = BigDecimal.ZERO;
        private BigDecimal current = BigDecimal.ZERO;

        private Aggregate(String name, HoldingKind kind) {
            this.name = name;
            this.kind = kind;
        }
    }
}
