package com.finsights.portfolio.service;

import static com.finsights.portfolio.service.TestHoldings.holding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.AllocationResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AllocationServiceTest {
    @Mock CurrentUserService currentUser;
    @Mock CategoryRepository categories;
    @Mock HoldingService holdings;
    private AllocationService service;
    private UserAccount user;
    private Category equity;
    private Category debt;
    private Category gold;

    @BeforeEach
    void setUp() {
        service = new AllocationService(currentUser, categories, holdings);
        user = new UserAccount("asha@example.com", "Asha");
        ReflectionTestUtils.setField(user, "id", "u-1");
        lenient().when(currentUser.currentUser()).thenReturn(user);
        equity = category("eq", "Equity", HoldingKind.ASSET, "60");
        debt = category("dt", "Debt", HoldingKind.ASSET, "40");
        gold = category("gd", "Gold", HoldingKind.ASSET, null);
        Category loans = category("ln", "Loans", HoldingKind.LIABILITY, null);
        lenient().when(categories.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u-1")).thenReturn(List.of(equity, debt, gold, loans));
        lenient().when(holdings.list("INR")).thenReturn(List.of(
                holding("a").category("eq", "Equity").current("700").build(),
                holding("b").category("eq", "Equity").current("100").build(),
                holding("c").category("dt", "Debt").current("200").build(),
                holding("l").category("ln", "Loans").kind(HoldingKind.LIABILITY).current("5000").build()));
    }

    @Test
    void showsDriftAndHowMuchToMoveForEachTargetedCategory() {
        AllocationResponse result = service.allocation(null);

        assertThat(result.currency()).isEqualTo("INR");
        assertThat(result.totalAssets()).isEqualByComparingTo("1000");
        assertThat(result.complete()).isTrue();
        assertThat(result.rows()).extracting(AllocationResponse.Row::name).containsExactly("Equity", "Debt", "Gold");
        var eq = result.rows().get(0);
        assertThat(eq.currentPercent()).isEqualByComparingTo("80");
        assertThat(eq.driftPercent()).isEqualByComparingTo("20");
        assertThat(eq.amountToTarget()).isEqualByComparingTo("-200");    // trim 200
        var dt = result.rows().get(1);
        assertThat(dt.amountToTarget()).isEqualByComparingTo("200");     // add 200
        var gd = result.rows().get(2);
        assertThat(gd.currentPercent()).isEqualByComparingTo("0");
        assertThat(gd.targetPercent()).isNull();
        assertThat(gd.amountToTarget()).isNull();
    }

    @Test
    void emptyPortfolioHasZeroPercentsAndIncompleteTargets() {
        equity.setTargetAllocationPercent(new BigDecimal("50"));
        lenient().when(holdings.list("USD")).thenReturn(List.of());
        user.setBaseCurrency("USD");

        AllocationResponse result = service.allocation(" ");

        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.complete()).isFalse();
        assertThat(result.targetSum()).isEqualByComparingTo("90");
        assertThat(result.rows().get(0).currentPercent()).isEqualByComparingTo("0");
    }

    @Test
    void savesTargetsClearingWithNullAndRejectsBadInput() {
        Map<String, BigDecimal> change = new HashMap<>();
        change.put("eq", new BigDecimal("50.005"));
        change.put("dt", null);
        change.put("gd", new BigDecimal("30"));

        AllocationResponse result = service.saveTargets(change, "inr");

        assertThat(equity.getTargetAllocationPercent()).isEqualByComparingTo("50.01");
        assertThat(debt.getTargetAllocationPercent()).isNull();
        assertThat(result.targetSum()).isEqualByComparingTo("80.01");
        verify(categories).saveAll(anyCollection());

        assertThatThrownBy(() -> service.saveTargets(Map.of("ln", BigDecimal.ONE), null)).hasMessageContaining("Unknown asset category");
        assertThatThrownBy(() -> service.saveTargets(Map.of("eq", new BigDecimal("-1")), null)).hasMessageContaining("between 0 and 100");
        assertThatThrownBy(() -> service.saveTargets(Map.of("eq", new BigDecimal("101")), null)).hasMessageContaining("between 0 and 100");
        assertThatThrownBy(() -> service.saveTargets(Map.of("eq", new BigDecimal("80")), null)).hasMessageContaining("more than 100%");
    }

    @Test
    void blankBaseCurrencyFallsBackToInr() {
        user.setBaseCurrency(null);
        assertThat(service.allocation(null).currency()).isEqualTo("INR");
        verify(categories, never()).saveAll(anyCollection());
    }

    private static Category category(String id, String name, HoldingKind kind, String target) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", id);
        c.setName(name);
        c.setKind(kind);
        c.setTargetAllocationPercent(target == null ? null : new BigDecimal(target));
        return c;
    }
}
