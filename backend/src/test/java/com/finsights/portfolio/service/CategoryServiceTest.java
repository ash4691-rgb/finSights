package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.CategoryReorderRequest;
import com.finsights.portfolio.dto.CategoryResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;
    @Mock HoldingRepository holdingRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock HoldingService holdingService;
    @Mock CurrentUserService currentUserService;
    @Mock PriceSnapshotService priceSnapshotService;

    private CategoryService service;
    private Category equity;
    private Category loan;

    @BeforeEach
    void setUp() throws Exception {
        service = new CategoryService(categoryRepository, holdingRepository, transactionRepository, holdingService, currentUserService, priceSnapshotService);
        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        setId(user, "u-1");
        when(currentUserService.currentUser()).thenReturn(user);

        equity = new Category();
        setId(equity, "c-1");
        equity.setName("Growth Equity");
        equity.setKind(HoldingKind.ASSET);

        loan = new Category();
        setId(loan, "c-2");
        loan.setName("Home Loan");
        loan.setKind(HoldingKind.LIABILITY);

        when(categoryRepository.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u-1")).thenReturn(List.of(equity, loan));
    }

    private static void setId(Object entity, String id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private HoldingResponse holdingOf(String id, String categoryId, HoldingKind kind, String invested, String current, boolean liquid, boolean npa) {
        return new HoldingResponse(id, "ref-" + id, categoryId, "n/a", "Holding " + id, kind, ValuationMethod.MANUAL,
                null, "Broker", "INR", new BigDecimal(invested), new BigDecimal(current),
                new BigDecimal(current).subtract(new BigDecimal(invested)), BigDecimal.ZERO, null, null, null, null,
                liquid, npa, null, null, java.util.Set.of(), null, null, null);
    }

    @Test
    void aggregatesInvestedCurrentWeightageAndLiquidNpaAcrossHoldings() {
        when(holdingService.list((String) null)).thenReturn(List.of(
                holdingOf("h-1", "c-1", HoldingKind.ASSET, "100000", "135000", true, false),
                holdingOf("h-2", "c-1", HoldingKind.ASSET, "80000", "100000", false, true),
                holdingOf("h-3", "c-2", HoldingKind.LIABILITY, "0", "3800000", false, false)));

        List<CategoryResponse> result = service.list(null);

        CategoryResponse equityResult = result.stream().filter(c -> c.id().equals("c-1")).findFirst().orElseThrow();
        assertThat(equityResult.investedValue()).isEqualByComparingTo("180000");
        assertThat(equityResult.currentValue()).isEqualByComparingTo("235000");
        assertThat(equityResult.profitLoss()).isEqualByComparingTo("55000");
        assertThat(equityResult.holdingCount()).isEqualTo(2);
        // weightage relative to total ASSET value (235000 / 235000 = 100%, the only asset category)
        assertThat(equityResult.weightagePercent()).isEqualByComparingTo("100.00");
        // h-1 is liquid (135000 of 235000), h-2 is NPA (100000 of 235000)
        assertThat(equityResult.liquidAmount()).isEqualByComparingTo("135000");
        assertThat(equityResult.liquidPercent().doubleValue()).isCloseTo(57.45, org.assertj.core.data.Offset.offset(0.1));
        assertThat(equityResult.npaAmount()).isEqualByComparingTo("100000");
        assertThat(equityResult.npaPercent().doubleValue()).isCloseTo(42.55, org.assertj.core.data.Offset.offset(0.1));

        CategoryResponse loanResult = result.stream().filter(c -> c.id().equals("c-2")).findFirst().orElseThrow();
        assertThat(loanResult.currentValue()).isEqualByComparingTo("3800000");
        assertThat(loanResult.weightagePercent()).isEqualByComparingTo("100.00");
        assertThat(loanResult.liquidAmount()).isEqualByComparingTo("0");
    }

    @Test
    void reorderAssignsSortOrderFromGivenSequenceAndAppendsLeftovers() {
        when(holdingService.list((String) null)).thenReturn(List.of());

        service.reorder(new CategoryReorderRequest(List.of("c-2", "c-1")));

        assertThat(loan.getSortOrder()).isZero();
        assertThat(equity.getSortOrder()).isEqualTo(1);
        verify(categoryRepository).saveAll(List.of(equity, loan));
    }

    @Test
    void reorderIgnoresUnknownIdsAndKeepsOwnedOnesOmittedFromTheListAtTheEnd() {
        when(holdingService.list((String) null)).thenReturn(List.of());

        // "c-1" only; "c-2" (owned but omitted) should still get a trailing position, "ghost-id" is ignored.
        service.reorder(new CategoryReorderRequest(List.of("c-1", "ghost-id")));

        assertThat(equity.getSortOrder()).isZero();
        assertThat(loan.getSortOrder()).isEqualTo(1);
    }

    @Test
    void categoryWithNoHoldingsHasZeroRollup() {
        when(holdingService.list((String) null)).thenReturn(List.of());

        List<CategoryResponse> result = service.list(null);

        assertThat(result).allSatisfy(c -> {
            assertThat(c.investedValue()).isEqualByComparingTo("0");
            assertThat(c.holdingCount()).isZero();
            assertThat(c.liquidPercent()).isEqualByComparingTo("0");
        });
    }
}
