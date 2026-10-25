package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.server.ResponseStatusException;

class ProvidentFundContributionServiceTest {
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final HoldingService holdingService = mock(HoldingService.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final ProvidentFundContributionService service =
            new ProvidentFundContributionService(holdings, transactions, holdingService, currentUser);
    private final LocalDate today = LocalDate.of(2026, 3, 5);
    private Holding fund;

    private static BigDecimal n(String v) { return new BigDecimal(v); }

    @BeforeEach
    void setUp() {
        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
        fund = new Holding();
        fund.setName("EPF - Acme");
        fund.setBroker("EPFO");
        fund.setCurrency("INR");
        fund.setUser(user);
        fund.setManualType(ManualType.PROVIDENT_FUND);
        fund.setMonthlyEmployeeContribution(n("1800"));
        fund.setMonthlyEmployerContribution(n("1500"));
        fund.setCurrentValue(n("100000"));
        fund.setLastContributionMonth("2026-01");
        when(holdings.findByIdAndUser_Id(any(), any())).thenReturn(Optional.of(fund));
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(any())).thenReturn(List.of(fund));
    }

    @Test
    void askedOnTheFirstForLastMonthDueEndOfThisMonth() {
        List<ActionItemResponse> items = service.dueItems(today);

        assertThat(items).hasSize(1);
        ActionItemResponse task = items.get(0);
        assertThat(task.kind()).isEqualTo("PROVIDENT_FUND_CONTRIBUTION");
        assertThat(task.severity()).isEqualTo("INFO");
        assertThat(task.period()).isEqualTo("2026-02");
        assertThat(task.dueDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(task.amount()).isEqualByComparingTo("3300");
        assertThat(task.detail()).contains("February 2026").contains("employee 1800").contains("employer 1500");
        assertThat(task.key()).isEqualTo(ActionItemResponse.keyOf("PROVIDENT_FUND_CONTRIBUTION", fund.getId(), "2026-02"));
    }

    @Test
    void unconfirmedEarlierMonthsStackUpAndTurnOverdue() {
        fund.setLastContributionMonth("2025-11");

        List<ActionItemResponse> items = service.dueItems(today);

        assertThat(items).extracting(ActionItemResponse::period).containsExactly("2025-12", "2026-01", "2026-02");
        assertThat(items.get(0).severity()).isEqualTo("WARN");
        assertThat(items.get(2).severity()).isEqualTo("INFO");
    }

    @Test
    void nothingPendingInTheMonthAfterTheLastConfirmedOne() {
        fund.setLastContributionMonth("2026-02");

        assertThat(service.dueItems(today)).isEmpty();
    }

    @Test
    void backlogIsCappedAtTwelveMonths() {
        fund.setLastContributionMonth("2020-01");

        assertThat(service.dueItems(today)).hasSize(12);
    }

    @Test
    void onlyTrackedProvidentFundsGetTasks() {
        Holding other = new Holding();
        other.setManualType(ManualType.REAL_ESTATE);
        other.setLastContributionMonth("2026-01");
        Holding untracked = new Holding();
        untracked.setManualType(ManualType.PROVIDENT_FUND);
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(any())).thenReturn(List.of(other, untracked));

        assertThat(service.dueItems(today)).isEmpty();
    }

    @Test
    void theDefaultEntryPointUsesToday() {
        fund.setLastContributionMonth(java.time.YearMonth.now().toString());

        assertThat(service.dueItems()).isEmpty();
    }

    @Test
    void confirmingLogsAnAdjustmentAndAddsToTheBalance() {
        service.confirm(fund.getId(), "2026-02", today);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactions).save(captor.capture());
        Transaction t = captor.getValue();
        assertThat(t.getType()).isEqualTo(TransactionType.ADJUSTMENT);
        assertThat(t.getAmount()).isEqualByComparingTo("3300");
        assertThat(t.getDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(t.getNotes()).contains("February 2026");
        assertThat(fund.getCurrentValue()).isEqualByComparingTo("103300");
        assertThat(fund.getLastContributionMonth()).isEqualTo("2026-02");
        InOrder order = inOrder(holdingService);
        order.verify(holdingService).ensureOpeningTransaction(fund);
        order.verify(holdingService).syncFromTransactions(fund);
    }

    @Test
    void confirmingKeepsTheEmployeeEmployerSplitInStep() {
        fund.setEmployeeContribution(n("50000"));
        fund.setEmployerContribution(n("40000"));

        service.confirm(fund.getId(), "2026-02", today);

        assertThat(fund.getEmployeeContribution()).isEqualByComparingTo("51800");
        assertThat(fund.getEmployerContribution()).isEqualByComparingTo("41500");
    }

    @Test
    void confirmingWithOneSideOfTheSplitTreatsTheOtherAsZero() {
        fund.setEmployeeContribution(n("50000"));

        service.confirm(fund.getId(), "2026-02", today);

        assertThat(fund.getEmployerContribution()).isEqualByComparingTo("1500");
    }

    @Test
    void aMissingMonthlyAmountCountsAsZero() {
        fund.setMonthlyEmployerContribution(null);
        fund.setCurrentValue(null);

        service.confirm(fund.getId(), "2026-02", today);

        assertThat(fund.getCurrentValue()).isEqualByComparingTo("1800");
    }

    @Test
    void confirmUsesTodayInTheDefaultEntryPoint() {
        fund.setLastContributionMonth(java.time.YearMonth.now().minusMonths(2).toString());

        service.confirm(fund.getId(), java.time.YearMonth.now().minusMonths(1).toString());

        assertThat(fund.getLastContributionMonth()).isEqualTo(java.time.YearMonth.now().minusMonths(1).toString());
    }

    @Test
    void monthsMustBeConfirmedInOrder() {
        assertThatThrownBy(() -> service.confirm(fund.getId(), "2026-01", today))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("February 2026 first");
        fund.setLastContributionMonth("2025-11");
        assertThatThrownBy(() -> service.confirm(fund.getId(), "2026-02", today))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("December 2025 first");
    }

    @Test
    void aMonthThatIsNotOverYetCannotBeConfirmed() {
        fund.setLastContributionMonth("2026-02");

        assertThatThrownBy(() -> service.confirm(fund.getId(), "2026-03", today))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("isn't over yet");
    }

    @Test
    void rejectsBadMonthsUnknownHoldingsAndUntrackedOnes() {
        assertThatThrownBy(() -> service.confirm(fund.getId(), "not-a-month", today))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("2026-02");
        assertThatThrownBy(() -> service.confirm(fund.getId(), null, today)).isInstanceOf(ResponseStatusException.class);
        fund.setLastContributionMonth(null);
        assertThatThrownBy(() -> service.confirm(fund.getId(), "2026-02", today))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no monthly contribution");
        when(holdings.findByIdAndUser_Id(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirm("nope", "2026-02", today))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not found");
    }
}
