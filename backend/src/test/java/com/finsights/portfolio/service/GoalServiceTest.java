package com.finsights.portfolio.service;

import static com.finsights.portfolio.service.TestHoldings.holding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Goal;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.GoalRequest;
import com.finsights.portfolio.dto.GoalResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.GoalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GoalServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 1);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock CurrentUserService currentUser;
    @Mock GoalRepository goals;
    @Mock CategoryRepository categories;
    @Mock HoldingService holdings;
    private GoalService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new GoalService(currentUser, goals, categories, holdings, CLOCK);
        user = new UserAccount("asha@example.com", "Asha");
        ReflectionTestUtils.setField(user, "id", "u-1");
        lenient().when(currentUser.currentUser()).thenReturn(user);
        lenient().when(categories.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u-1")).thenReturn(List.of(
                category("eq", HoldingKind.ASSET), category("dt", HoldingKind.ASSET), category("ln", HoldingKind.LIABILITY)));
        lenient().when(holdings.list("INR")).thenReturn(List.of(
                holding("a").category("eq", "Equity").current("60000").build(),
                holding("b").category("dt", "Debt").current("40000").build(),
                holding("l").category("ln", "Loans").kind(HoldingKind.LIABILITY).current("999999").build()));
        lenient().when(goals.save(any(Goal.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createsAGoalLinkedToCategoriesWithDefaultsAndProjection() {
        GoalResponse goal = service.create(new GoalRequest("  House  ", new BigDecimal("200000"), TODAY.plusMonths(12),
                null, null, Set.of("eq")));

        assertThat(goal.name()).isEqualTo("House");
        assertThat(goal.currency()).isEqualTo("INR");
        assertThat(goal.expectedReturnPercent()).isEqualByComparingTo("10");
        assertThat(goal.monthlyContribution()).isEqualByComparingTo("0");
        assertThat(goal.currentValue()).isEqualByComparingTo("60000");
        assertThat(goal.progressPercent()).isEqualByComparingTo("30");
        assertThat(goal.monthsLeft()).isEqualTo(12);
        assertThat(goal.projectedValue()).isGreaterThan(new BigDecimal("60000")).isLessThan(new BigDecimal("70000"));
        assertThat(goal.onTrack()).isFalse();
        assertThat(goal.requiredMonthly()).isPositive();
    }

    @Test
    void unlinkedGoalUsesAllAssetsAndCanBeOnTrack() {
        GoalResponse goal = service.create(new GoalRequest("Buffer", new BigDecimal("100000"), TODAY.plusMonths(6),
                new BigDecimal("1000"), new BigDecimal("6"), null));

        assertThat(goal.currentValue()).isEqualByComparingTo("100000");
        assertThat(goal.onTrack()).isTrue();
        assertThat(goal.requiredMonthly()).isEqualByComparingTo("0");
    }

    @Test
    void rejectsLinksToOtherUsersOrLiabilityCategories() {
        assertThatThrownBy(() -> service.create(new GoalRequest("X", BigDecimal.TEN, TODAY.plusDays(1), null, null, Set.of("ln"))))
                .hasMessageContaining("your own asset categories");
        assertThatThrownBy(() -> service.create(new GoalRequest("X", BigDecimal.TEN, TODAY.plusDays(1), null, null, Set.of("other"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsUpdatesAndDeletesOnlyOwnGoals() {
        Goal existing = new Goal("u-1");
        existing.setName("Old");
        existing.setTargetAmount(new BigDecimal("1000"));
        existing.setTargetDate(TODAY.minusMonths(1));      // already past
        existing.setMonthlyContribution(BigDecimal.ZERO);
        existing.setExpectedReturnPercent(BigDecimal.TEN);
        existing.setCategoryIds(Set.of("gone"));            // its category was deleted
        when(goals.findByUserIdOrderByTargetDateAsc("u-1")).thenReturn(List.of(existing));
        when(goals.findByIdAndUserId("g-1", "u-1")).thenReturn(Optional.of(existing));
        when(goals.findByIdAndUserId("nope", "u-1")).thenReturn(Optional.empty());

        GoalResponse listed = service.list().get(0);
        assertThat(listed.currentValue()).isEqualByComparingTo("0");
        assertThat(listed.monthsLeft()).isZero();
        assertThat(listed.requiredMonthly()).isEqualByComparingTo("0");

        GoalResponse updated = service.update("g-1", new GoalRequest("New", new BigDecimal("5000"), TODAY.plusYears(1),
                new BigDecimal("100"), new BigDecimal("8"), Set.of("dt")));
        assertThat(updated.name()).isEqualTo("New");
        assertThat(updated.categoryIds()).containsExactly("dt");

        service.delete("g-1");
        verify(goals).delete(existing);
        assertThatThrownBy(() -> service.delete("nope"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));

        service.deleteForUser("u-1");
        verify(goals).deleteByUserId("u-1");
    }

    @Test
    void currentValueSumsLinkedCategoriesOrEverything() {
        Map<String, BigDecimal> values = Map.of("a", BigDecimal.ONE, "b", BigDecimal.TEN);
        assertThat(GoalService.currentValue(Set.of(), values)).isEqualByComparingTo("11");
        assertThat(GoalService.currentValue(Set.of("b", "x"), values)).isEqualByComparingTo("10");
    }

    @Test
    void blankBaseCurrencyFallsBackToInr() {
        user.setBaseCurrency("");
        when(goals.findByUserIdOrderByTargetDateAsc("u-1")).thenReturn(List.of());
        assertThat(service.list()).isEmpty();
        verify(holdings).list("INR");
    }

    private static Category category(String id, HoldingKind kind) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", id);
        c.setKind(kind);
        return c;
    }
}
