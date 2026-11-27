package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Goal;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.GoalRequest;
import com.finsights.portfolio.dto.GoalResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.GoalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Savings goals in the user's base currency, each projected from its linked categories' value today. */
@Service
public class GoalService implements UserDataCleanup {
    static final BigDecimal DEFAULT_RETURN_PERCENT = BigDecimal.TEN;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CurrentUserService currentUser;
    private final GoalRepository goals;
    private final CategoryRepository categories;
    private final HoldingService holdings;
    private final Clock clock;

    @Autowired
    public GoalService(CurrentUserService currentUser, GoalRepository goals, CategoryRepository categories,
                       HoldingService holdings) {
        this(currentUser, goals, categories, holdings, Clock.systemDefaultZone());
    }

    GoalService(CurrentUserService currentUser, GoalRepository goals, CategoryRepository categories,
                HoldingService holdings, Clock clock) {
        this.currentUser = currentUser;
        this.goals = goals;
        this.categories = categories;
        this.holdings = holdings;
        this.clock = clock;
    }

    public List<GoalResponse> list() {
        UserAccount user = currentUser.currentUser();
        String currency = currencyOf(user);
        Map<String, BigDecimal> valueByCategory = assetValueByCategory(currency);
        return goals.findByUserIdOrderByTargetDateAsc(user.getId()).stream()
                .map(goal -> toResponse(goal, currency, valueByCategory))
                .toList();
    }

    @Transactional
    public GoalResponse create(GoalRequest request) {
        UserAccount user = currentUser.currentUser();
        Goal goal = new Goal(user.getId());
        apply(goal, request, user);
        return respond(goals.save(goal), user);
    }

    @Transactional
    public GoalResponse update(String id, GoalRequest request) {
        UserAccount user = currentUser.currentUser();
        Goal goal = owned(id, user);
        apply(goal, request, user);
        return respond(goals.save(goal), user);
    }

    @Transactional
    public void delete(String id) {
        goals.delete(owned(id, currentUser.currentUser()));
    }

    @Override
    public void deleteForUser(String userId) {
        goals.deleteByUserId(userId);
    }

    GoalResponse toResponse(Goal goal, String currency, Map<String, BigDecimal> valueByCategory) {
        BigDecimal current = currentValue(goal.getCategoryIds(), valueByCategory);
        LocalDate today = LocalDate.now(clock);
        int months = (int) Math.max(0, ChronoUnit.MONTHS.between(today, goal.getTargetDate()));
        double rate = goal.getExpectedReturnPercent().doubleValue();
        double target = goal.getTargetAmount().doubleValue();
        BigDecimal projected = money(GoalMath.futureValue(current.doubleValue(), goal.getMonthlyContribution().doubleValue(), rate, months));
        BigDecimal required = money(GoalMath.requiredMonthly(current.doubleValue(), target, rate, months));
        BigDecimal progress = current.multiply(HUNDRED).divide(goal.getTargetAmount(), 2, RoundingMode.HALF_UP);
        return new GoalResponse(goal.getId(), goal.getName(), currency, goal.getTargetAmount(), goal.getTargetDate(),
                goal.getMonthlyContribution(), goal.getExpectedReturnPercent(), Set.copyOf(goal.getCategoryIds()),
                money(current), progress, months, projected, projected.compareTo(goal.getTargetAmount()) >= 0, required);
    }

    /** No linked categories = all assets; links to categories that no longer exist count as zero. */
    static BigDecimal currentValue(Set<String> categoryIds, Map<String, BigDecimal> valueByCategory) {
        if (categoryIds.isEmpty()) return valueByCategory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return categoryIds.stream().map(id -> valueByCategory.getOrDefault(id, BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void apply(Goal goal, GoalRequest request, UserAccount user) {
        Set<String> linked = request.categoryIds() == null ? Set.of() : request.categoryIds();
        Set<String> assetCategoryIds = categories.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(user.getId()).stream()
                .filter(c -> c.getKind() == HoldingKind.ASSET).map(Category::getId).collect(Collectors.toSet());
        if (!assetCategoryIds.containsAll(linked)) throw new IllegalArgumentException("A goal can only link your own asset categories");
        goal.setName(request.name().trim());
        goal.setTargetAmount(request.targetAmount().setScale(2, RoundingMode.HALF_UP));
        goal.setTargetDate(request.targetDate());
        goal.setMonthlyContribution(orDefault(request.monthlyContribution(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        goal.setExpectedReturnPercent(orDefault(request.expectedReturnPercent(), DEFAULT_RETURN_PERCENT).setScale(2, RoundingMode.HALF_UP));
        goal.setCategoryIds(linked);
    }

    private GoalResponse respond(Goal goal, UserAccount user) {
        String currency = currencyOf(user);
        return toResponse(goal, currency, assetValueByCategory(currency));
    }

    private Goal owned(String id, UserAccount user) {
        return goals.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found"));
    }

    private Map<String, BigDecimal> assetValueByCategory(String currency) {
        return holdings.list(currency).stream()
                .filter(h -> h.kind() == HoldingKind.ASSET)
                .collect(Collectors.groupingBy(HoldingResponse::categoryId,
                        Collectors.reducing(BigDecimal.ZERO, h -> h.currentValue() == null ? BigDecimal.ZERO : h.currentValue(), BigDecimal::add)));
    }

    private static String currencyOf(UserAccount user) {
        String base = user.getBaseCurrency();
        if (base == null || base.isBlank()) return "INR";
        return base;
    }

    private static BigDecimal orDefault(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
