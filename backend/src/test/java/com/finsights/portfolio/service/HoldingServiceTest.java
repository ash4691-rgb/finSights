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

    private HoldingService service;
    private Holding holding;

    @BeforeEach
    void setUp() {
        service = new HoldingService(holdings, categories, currentUser, valuations, fx, transactions, snapshots);
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
    void syncNetsBuysAgainstSells() {
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "100000", "40"),
                txn(TransactionType.BUY, "50000", "20"),
                txn(TransactionType.SELL, "30000", "10")));

        service.syncFromTransactions(holding);

        assertThat(holding.getInvestedValue()).isEqualByComparingTo("120000.00");
        assertThat(holding.getQuantity()).isEqualByComparingTo("50");
        verify(holdings).save(holding);
    }

    @Test
    void interestDoesNotChangeCostBasisAndSplitScalesQuantity() {
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "100000", "40"),
                txn(TransactionType.INTEREST, "5000", null),
                txn(TransactionType.SPLIT, "0", "2")));

        service.syncFromTransactions(holding);

        assertThat(holding.getInvestedValue()).isEqualByComparingTo("100000.00");
        assertThat(holding.getQuantity()).isEqualByComparingTo("80"); // 40 * 2
    }

    @Test
    void investedNeverGoesNegativeAndZeroQuantityBecomesNull() {
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "10000", "10"),
                txn(TransactionType.SELL, "40000", "10")));

        service.syncFromTransactions(holding);

        assertThat(holding.getInvestedValue()).isEqualByComparingTo("0.00");
        assertThat(holding.getQuantity()).isNull();
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
