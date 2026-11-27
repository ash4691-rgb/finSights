package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.AllocationResponse;
import com.finsights.portfolio.dto.AllocationResponse.Row;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Target allocation per asset category, and how far today's portfolio has drifted from it. */
@Service
public class AllocationService {
    static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CurrentUserService currentUser;
    private final CategoryRepository categories;
    private final HoldingService holdings;

    public AllocationService(CurrentUserService currentUser, CategoryRepository categories, HoldingService holdings) {
        this.currentUser = currentUser;
        this.categories = categories;
        this.holdings = holdings;
    }

    public AllocationResponse allocation(String requestedCurrency) {
        UserAccount user = currentUser.currentUser();
        String currency = currencyOf(requestedCurrency, user);
        Map<String, BigDecimal> valueByCategory = holdings.list(currency).stream()
                .filter(h -> h.kind() == HoldingKind.ASSET)
                .collect(Collectors.groupingBy(HoldingResponse::categoryId,
                        Collectors.reducing(BigDecimal.ZERO, h -> zeroIfNull(h.currentValue()), BigDecimal::add)));
        List<Category> assetCategories = assetCategoriesOf(user);
        BigDecimal total = valueByCategory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Row> rows = assetCategories.stream()
                .map(c -> row(c, valueByCategory.getOrDefault(c.getId(), BigDecimal.ZERO), total))
                .toList();
        BigDecimal targetSum = targetSum(assetCategories);
        return new AllocationResponse(currency, money(total), targetSum, targetSum.compareTo(HUNDRED) == 0, rows);
    }

    @Transactional
    public AllocationResponse saveTargets(Map<String, BigDecimal> targets, String currency) {
        UserAccount user = currentUser.currentUser();
        Map<String, Category> owned = assetCategoriesOf(user).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        targets.forEach((id, percent) -> {
            Category category = owned.get(id);
            if (category == null) throw new IllegalArgumentException("Unknown asset category: " + id);
            validatePercent(percent);
            category.setTargetAllocationPercent(percent == null ? null : percent.setScale(2, RoundingMode.HALF_UP));
        });
        if (targetSum(owned.values().stream().toList()).compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("Targets add up to more than 100%");
        }
        categories.saveAll(owned.values());
        return allocation(currency);
    }

    static Row row(Category category, BigDecimal value, BigDecimal total) {
        BigDecimal currentPercent = total.signum() == 0 ? BigDecimal.ZERO
                : value.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
        BigDecimal target = category.getTargetAllocationPercent();
        if (target == null) {
            return new Row(category.getId(), category.getName(), money(value), currentPercent, null, null, null);
        }
        BigDecimal toTarget = total.multiply(target).divide(HUNDRED, 2, RoundingMode.HALF_UP).subtract(money(value));
        return new Row(category.getId(), category.getName(), money(value), currentPercent, target,
                currentPercent.subtract(target), toTarget);
    }

    private static void validatePercent(BigDecimal percent) {
        if (percent == null) return;
        if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("A target must be between 0 and 100%");
        }
    }

    private List<Category> assetCategoriesOf(UserAccount user) {
        return categories.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(user.getId()).stream()
                .filter(c -> c.getKind() == HoldingKind.ASSET).toList();
    }

    private static BigDecimal targetSum(List<Category> list) {
        return list.stream().map(Category::getTargetAllocationPercent).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static String currencyOf(String requested, UserAccount user) {
        if (requested != null && !requested.isBlank()) return requested.trim().toUpperCase(Locale.ROOT);
        String base = user.getBaseCurrency();
        if (base == null || base.isBlank()) return "INR";
        return base;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
