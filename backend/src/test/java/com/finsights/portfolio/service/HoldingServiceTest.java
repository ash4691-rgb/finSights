package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class HoldingServiceTest {

    @Mock HoldingRepository holdings;
    @Mock CategoryRepository categories;
    @Mock CurrentUserService currentUser;
    @Mock ValuationService valuations;
    @Mock FxRateService fx;
    @Mock TransactionRepository transactions;
    @Mock PriceSnapshotService snapshots;
    @Mock MarketDataService marketData;
    @Mock com.finsights.portfolio.repository.EmiPaymentRepository emiPayments;

    private HoldingService service;
    private Holding holding;

    @BeforeEach
    void setUp() {
        service = new HoldingService(holdings, categories, currentUser, valuations, fx, transactions, snapshots, marketData, emiPayments);
        holding = new Holding();
        holding.setName("Reliance");
    }

    private Transaction txn(TransactionType type, String amount, String quantity) {
        Transaction t = new Transaction();
        t.setType(type);
        t.setDate(LocalDate.now());
        t.setAmount(new BigDecimal(amount));
        t.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        return t;
    }

    @Test
    void sellMatchesLotsFifoForRealisedPnl() {
        // Lot 1: 40 @ 2500. Lot 2: 20 @ 2500. Sell 10 → consumes lot 1 → cost 25000, realised +5000.
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "100000", "40"),
                txn(TransactionType.BUY, "50000", "20"),
                txn(TransactionType.SELL, "30000", "10")));

        service.syncFromTransactions(holding);

        assertThat(holding.getInvestedValue()).isEqualByComparingTo("125000.00");
        assertThat(holding.getQuantity()).isEqualByComparingTo("50");
        assertThat(holding.getRealisedProfitLoss()).isEqualByComparingTo("5000.00");
        verify(holdings).save(holding);
    }

    @Test
    void fifoUsesTheOldestLotFirst() {
        // Lot 1: 10 @ 100. Lot 2: 10 @ 200. Sell 15 → 10@100 + 5@200 = 2000 cost; proceeds 3000 → realised +1000.
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "1000", "10"),
                txn(TransactionType.BUY, "2000", "10"),
                txn(TransactionType.SELL, "3000", "15")));

        service.syncFromTransactions(holding);

        assertThat(holding.getQuantity()).isEqualByComparingTo("5");        // 5 left from lot 2
        assertThat(holding.getInvestedValue()).isEqualByComparingTo("1000.00"); // 5 @ 200
        assertThat(holding.getRealisedProfitLoss()).isEqualByComparingTo("1000.00");
    }

    @Test
    void interestAccruesAsIncomeAndSplitScalesLots() {
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "100000", "40"),
                txn(TransactionType.INTEREST, "5000", null),
                txn(TransactionType.SPLIT, "0", "2")));

        service.syncFromTransactions(holding);

        assertThat(holding.getInvestedValue()).isEqualByComparingTo("100000.00");
        assertThat(holding.getQuantity()).isEqualByComparingTo("80"); // 40 * 2
        assertThat(holding.getRealisedProfitLoss()).isEqualByComparingTo("0.00");
        assertThat(holding.getAccruedIncome()).isEqualByComparingTo("5000.00");
    }

    @Test
    void closingThePositionZeroesTheBasisAndBooksTheGain() {
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "10000", "10"),
                txn(TransactionType.SELL, "40000", "10")));

        service.syncFromTransactions(holding);

        assertThat(holding.getInvestedValue()).isEqualByComparingTo("0.00");
        assertThat(holding.getQuantity()).isNull();
        assertThat(holding.getRealisedProfitLoss()).isEqualByComparingTo("30000.00");
    }

    @Test
    void openingTransactionIsRecordedOnceForAHoldingThatHasValueButNoLedger() {
        holding.setInvestedValue(new BigDecimal("75000"));
        holding.setQuantity(new BigDecimal("30"));
        when(transactions.existsByHolding_Id(any())).thenReturn(false);

        service.ensureOpeningTransaction(holding);

        verify(transactions).save(any(Transaction.class));
    }

    @Test
    void openingTransactionIsSkippedWhenTheLedgerAlreadyExists() {
        holding.setInvestedValue(new BigDecimal("75000"));
        when(transactions.existsByHolding_Id(any())).thenReturn(true);

        service.ensureOpeningTransaction(holding);

        verify(transactions, org.mockito.Mockito.never()).save(any(Transaction.class));
    }

    // Regression: a BUY with a wildly oversized quantity/amount used to compute an invested
    // value the `holdings` table's NUMERIC(20,2) column can't hold, and the raw DB overflow
    // exception on save took down every page that touches this holding.
    @Test
    void syncFromTransactionsRejectsAnOverflowingComputedInvestedValue() {
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "999999999999999999999999", "1")));

        assertThatThrownBy(() -> service.syncFromTransactions(holding))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("too large");
        verify(holdings, never()).save(any(Holding.class));
    }

    // Regression: the same oversized-value problem, but reached through the unattended
    // market-price refresh that runs on every Holdings-page load (list()) rather than through
    // a user-submitted request — this is what actually took prod down, since one bad holding's
    // reprice-and-save failure propagated and failed the whole list for every holding.
    @Test
    void listSkipsAMarketRepriceThatWouldOverflowInsteadOfCrashingThePage() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        category.setName("Growth Equity");
        holding.setCategory(category);
        holding.setValuationMethod(ValuationMethod.MARKET_PRICE);
        holding.setTickerSymbol("HUGE");
        holding.setCurrency("INR");
        holding.setQuantity(new BigDecimal("999999999999999999")); // fat-fingered — far past the column's max
        holding.setCurrentValue(new BigDecimal("100.00"));
        holding.setInvestedValue(new BigDecimal("100.00"));

        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(any())).thenReturn(List.of(holding));
        when(transactions.findHoldingIdsWithTransactions(any())).thenReturn(new HashSet<>());
        when(marketData.quotes(any())).thenReturn(Map.of("HUGE", new MarketQuoteResponse("HUGE", "Huge Co", new BigDecimal("500"), "INR", null)));
        when(fx.convert(any(), any(), any())).thenReturn(new BigDecimal("500"));
        when(valuations.currentValue(any())).thenReturn(new BigDecimal("100.00"));

        assertThatCode(service::list).doesNotThrowAnyException();
        assertThat(holding.getCurrentValue()).isEqualByComparingTo("100.00"); // last known-good value kept, not overwritten
        verify(holdings, never()).save(holding);
    }
}
