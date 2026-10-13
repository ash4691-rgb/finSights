package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.RepaymentFrequency;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.repository.EmiPaymentRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmiServiceTest {

    @Mock HoldingRepository holdings;
    @Mock EmiPaymentRepository payments;
    @Mock TransactionRepository transactions;
    @Mock CurrentUserService currentUserService;

    private EmiService service;
    private UserAccount user;
    private Holding loan;

    @BeforeEach
    void setUp() throws Exception {
        service = new EmiService(holdings, payments, transactions, currentUserService);
        user = new UserAccount("demo@finsights.local", "Demo");
        setField(user, "id", "u-1");
        when(currentUserService.currentUser()).thenReturn(user);

        Category liabilities = new Category();
        liabilities.setKind(HoldingKind.LIABILITY);

        loan = new Holding();
        setField(loan, "id", "h-1");
        loan.setCategory(liabilities);
        loan.setName("HDFC Housing");
        loan.setCurrency("INR");
        loan.setRepaymentFrequency(RepaymentFrequency.WEEKLY);
        loan.setEmiAmount(new BigDecimal("5000"));
        loan.setCurrentValue(new BigDecimal("500000"));
        // firstInstalment() anchors WEEKLY loans one week after createdAt — set createdAt so the
        // first instalment lands exactly today, giving deterministic grid dates to assert against.
        setField(loan, "createdAt", Instant.now().minus(java.time.Duration.ofDays(7)));

        lenient().when(payments.findByHolding_Id(any())).thenReturn(List.of());
        lenient().when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of());
        lenient().when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u-1")).thenReturn(List.of(loan));
    }

    private static void setField(Object entity, String name, Object value) throws Exception {
        Field field = entity.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(entity, value);
    }

    @Test
    void dueItemsOnlyIncludesTheDueSoonWindow() {
        List<ActionItemResponse> due = service.dueItems();
        // Grid is today, today+7, today+14, ... — only "today" falls within DUE_SOON_DAYS (5).
        assertThat(due).hasSize(1);
        assertThat(due.get(0).dueDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void upcomingItemsExcludesTheDueSoonWindowAndStopsAtItsOwnHorizon() {
        List<ActionItemResponse> upcoming = service.upcomingItems();
        LocalDate today = LocalDate.now();
        // today itself (and anything within DUE_SOON_DAYS) must not reappear here.
        assertThat(upcoming).extracting(ActionItemResponse::dueDate)
                .allMatch(d -> d.isAfter(today.plusDays(5)) && !d.isAfter(today.plusDays(35)));
        // Weekly grid beyond day 5 and within day 35: +7, +14, +21, +28, +35.
        assertThat(upcoming).extracting(ActionItemResponse::dueDate)
                .containsExactly(today.plusWeeks(1), today.plusWeeks(2), today.plusWeeks(3),
                        today.plusWeeks(4), today.plusWeeks(5));
    }

    @Test
    void dueAndUpcomingNeverOverlap() {
        List<LocalDate> due = service.dueItems().stream().map(ActionItemResponse::dueDate).toList();
        List<LocalDate> upcoming = service.upcomingItems().stream().map(ActionItemResponse::dueDate).toList();
        assertThat(due).doesNotContainAnyElementsOf(upcoming);
    }
}
