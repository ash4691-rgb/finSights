package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.DashboardLayoutRepository;
import com.finsights.portfolio.repository.EmiPaymentRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TagSuggestionRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import com.finsights.portfolio.repository.UserAccountRepository;
import com.finsights.portfolio.repository.UserPersonaRepository;
import com.finsights.portfolio.repository.WatchlistRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock UserAccountRepository users;
    @Mock HoldingRepository holdings;
    @Mock CategoryRepository categories;
    @Mock TransactionRepository transactions;
    @Mock HoldingService holdingService;
    @Mock CurrentUserService currentUser;
    @Mock CountryCurrencyService countries;
    @Mock WatchlistRepository watchlist;
    @Mock PriceSnapshotService snapshots;
    @Mock PortfolioSnapshotService portfolioSnapshots;
    @Mock ActionDismissalService actionDismissals;
    @Mock TagSuggestionRepository tagSuggestions;
    @Mock EmiPaymentRepository emiPayments;
    @Mock DashboardLayoutRepository dashboardLayouts;
    @Mock UserPersonaRepository personas;

    private SettingsService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new SettingsService(users, holdings, categories, transactions, holdingService, currentUser,
                countries, watchlist, snapshots, portfolioSnapshots, actionDismissals, tagSuggestions,
                emiPayments, dashboardLayouts, personas);
        user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
    }

    // Regression test: user_personas.user_id is a NOT NULL FK back to users, so deleting the
    // account without first clearing its persona row throws a referential-integrity violation
    // for any user who's ever submitted or skipped the persona questionnaire. Order matters, not
    // just that both calls happen.
    @Test
    void deleteAccountRemovesThePersonaRowBeforeTheAccountItself() {
        service.deleteAccount();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(personas, users);
        order.verify(personas).deleteByUser_Id(any());
        order.verify(users).delete(user);
    }

    @Test
    void deleteHoldingsAndTransactionsWipesHoldingsAndLedgerButKeepsCategoriesAndAccount() {
        Holding a = new Holding(); Holding b = new Holding();
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(any())).thenReturn(List.of(a, b));

        service.deleteHoldingsAndTransactions();

        verify(snapshots, times(2)).deleteFor(eq(SnapshotSubject.HOLDING), any());
        verify(emiPayments).deleteByUser_Id(any());
        verify(transactions).deleteByUser_Id(any());
        verify(holdings).deleteByUser_Id(any());
        verify(categories, never()).deleteByUser_Id(any());
        verify(users, never()).delete(any());
    }

    @Test
    void deleteTransactionsResyncsEachHoldingToZeroInsteadOfDeletingIt() {
        Holding holding = new Holding();
        holding.setCurrentValue(new BigDecimal("500.00"));
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(any())).thenReturn(List.of(holding));

        service.deleteTransactions();

        verify(emiPayments).deleteByUser_Id(any());
        verify(transactions).deleteByUser_Id(any());
        verify(holdingService).syncFromTransactions(holding);
        verify(holdings, never()).deleteByUser_Id(any());
        assertThat(holding.getCurrentValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(holding.getPriceUpdatedAt()).isNull();
    }
}
