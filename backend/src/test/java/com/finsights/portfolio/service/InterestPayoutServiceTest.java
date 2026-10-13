package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.CompoundingFrequency;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InterestPayoutServiceTest {

    @Mock HoldingRepository holdings;
    @Mock TransactionRepository transactions;
    @Mock TransactionService transactionService;
    @Mock CurrentUserService currentUserService;

    private InterestPayoutService service;
    private UserAccount user;
    private Holding fd;

    @BeforeEach
    void setUp() throws Exception {
        service = new InterestPayoutService(holdings, transactions, transactionService, currentUserService);
        user = new UserAccount("demo@finsights.local", "Demo");
        setField(user, "id", "u-1");
        when(currentUserService.currentUser()).thenReturn(user);

        Category assets = new Category();
        assets.setKind(HoldingKind.ASSET);

        fd = new Holding();
        setField(fd, "id", "h-1");
        fd.setCategory(assets);
        fd.setName("HDFC FD");
        fd.setCurrency("INR");
        fd.setValuationMethod(ValuationMethod.FIXED_RATE);
        fd.setCompoundingFrequency(CompoundingFrequency.MONTHLY);
        fd.setFixedAnnualRate(new BigDecimal("0.07"));
        fd.setInvestedValue(new BigDecimal("100000"));
        // dueDates() anchors the first payout one step (1 month) after fixedRateStartDate — set
        // this so the first payout lands exactly today, for deterministic grid dates.
        fd.setFixedRateStartDate(LocalDate.now().minusMonths(1));

        lenient().when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of());
        lenient().when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u-1")).thenReturn(List.of(fd));
    }

    private static void setField(Object entity, String name, Object value) throws Exception {
        Field field = entity.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(entity, value);
    }

    @Test
    void dueItemsOnlyIncludesTheDueSoonWindow() {
        List<ActionItemResponse> due = service.dueItems();
        assertThat(due).hasSize(1);
        assertThat(due.get(0).dueDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void upcomingItemsExcludesTheDueSoonWindowAndStopsAtItsOwnHorizon() {
        List<ActionItemResponse> upcoming = service.upcomingItems();
        LocalDate today = LocalDate.now();
        assertThat(upcoming).extracting(ActionItemResponse::dueDate)
                .allMatch(d -> d.isAfter(today.plusDays(5)) && !d.isAfter(today.plusDays(35)));
        // Monthly grid beyond day 5 and within day 30: just next month's payout.
        assertThat(upcoming).extracting(ActionItemResponse::dueDate).containsExactly(today.plusMonths(1));
    }
}
