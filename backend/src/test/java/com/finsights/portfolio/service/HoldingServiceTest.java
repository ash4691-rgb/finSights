package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
