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
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    private HoldingRequest editRequest(BigDecimal investedValue, BigDecimal currentValue) {
        return new HoldingRequest("cat-1", "Reliance", ValuationMethod.MANUAL, null, "Kite", "INR",
                null, investedValue, currentValue, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
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

    private void stubForUpdate(Category category) {
        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        holding.setUser(user);
        holding.setCategory(category);
        holding.setBroker("Kite");
        holding.setCurrency("INR");
        when(currentUser.currentUser()).thenReturn(user);
        when(holdings.findByIdAndUser_Id(any(), any())).thenReturn(Optional.of(holding));
        when(categories.findByIdAndUser_Id(any(), any())).thenReturn(Optional.of(category));
        when(holdings.findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(any(), any(), any())).thenReturn(Optional.empty());
        when(holdings.save(any())).thenReturn(holding);
        when(fx.supports(any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(transactions.existsByHolding_Id(any())).thenReturn(true); // ledger already backfilled — only consulted when an adjustment is actually booked
        when(valuations.currentValue(any())).thenAnswer(inv -> ((Holding) inv.getArgument(0)).getCurrentValue());
    }

    // A holding-edit invested-value correction (e.g. for a partial-sell mismatch) is booked as a
    // real, visible ADJUSTMENT transaction for the difference — not a silent field overwrite.
    @Test
    void editingInvestedValueBooksAVisibleAdjustmentForTheDifference() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForUpdate(category);
        holding.setValuationMethod(ValuationMethod.MANUAL);
        holding.setInvestedValue(new BigDecimal("10000.00"));
        holding.setCurrentValue(new BigDecimal("12000.00"));
        // After the adjustment is booked, syncFromTransactions replays the ledger to land at the target.
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "10000", null),
                txn(TransactionType.ADJUSTMENT, "500", null)));

        service.update("h-1", editRequest(new BigDecimal("10500.00"), new BigDecimal("12000.00")));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactions).save(captor.capture());
        Transaction adjustment = captor.getValue();
        assertThat(adjustment.getType()).isEqualTo(TransactionType.ADJUSTMENT);
        assertThat(adjustment.getAmount()).isEqualByComparingTo("500.00");
        assertThat(adjustment.getQuantity()).isNull();
        assertThat(holding.getInvestedValue()).isEqualByComparingTo("10500.00");
    }

    @Test
    void editingWithoutChangingInvestedValueBooksNoAdjustment() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForUpdate(category);
        holding.setValuationMethod(ValuationMethod.MANUAL);
        holding.setInvestedValue(new BigDecimal("10000.00"));
        holding.setCurrentValue(new BigDecimal("12000.00"));
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "10000", null)));

        service.update("h-1", editRequest(new BigDecimal("10000.00"), new BigDecimal("12000.00")));

        verify(transactions, never()).save(any(Transaction.class));
    }

    // A liability's "total amount" stays locked to the transaction ledger (the UI disables the
    // field on edit) — the backend never books an invested-value adjustment for a liability.
    @Test
    void editingALiabilityNeverBooksAnInvestedValueAdjustment() {
        Category category = new Category();
        category.setKind(HoldingKind.LIABILITY);
        stubForUpdate(category);
        holding.setValuationMethod(ValuationMethod.MANUAL);
        holding.setInvestedValue(new BigDecimal("500000.00"));
        holding.setCurrentValue(new BigDecimal("400000.00"));
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "500000", null)));

        HoldingRequest request = new HoldingRequest("cat-1", "HDFC Home Loan", null, null, "HDFC Bank", "INR",
                null, new BigDecimal("999999.00"), new BigDecimal("400000.00"), null, null, null, null,
                com.finsights.portfolio.domain.RepaymentFrequency.MONTHLY, new BigDecimal("10000"), 5, null, null,
                null, null, null, null, null);

        service.update("h-1", request);

        verify(transactions, never()).save(any(Transaction.class));
    }
}
