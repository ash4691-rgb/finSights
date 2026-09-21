package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.TransactionRequest;
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
class TransactionServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock HoldingRepository holdingRepository;
    @Mock CurrentUserService currentUserService;
    @Mock HoldingService holdingService;
    @Mock com.finsights.portfolio.repository.EmiPaymentRepository emiPaymentRepository;
    @Mock MarketDataService marketData;

    private TransactionService service;
    private UserAccount user;
    private Holding reliance;
    private Holding fd;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(marketData.quotes(any())).thenReturn(java.util.Map.of());
        service = new TransactionService(transactionRepository, holdingRepository, currentUserService, new FxRateService(marketData), holdingService, emiPaymentRepository);
        user = new UserAccount("demo@finsights.local", "Demo");
        setId(user, "u-1");
        when(currentUserService.currentUser()).thenReturn(user);

        Category equity = new Category();
        setId(equity, "c-1");
        equity.setName("Growth Equity");
        equity.setKind(HoldingKind.ASSET);

        Category debt = new Category();
        setId(debt, "c-2");
        debt.setName("Fixed Income");
        debt.setKind(HoldingKind.ASSET);

        reliance = new Holding();
        setId(reliance, "h-1");
        reliance.setCategory(equity);
        reliance.setName("Reliance");
        reliance.setValuationMethod(ValuationMethod.MANUAL);
        reliance.setBroker("Zerodha Kite");
        reliance.setCurrency("INR");

        fd = new Holding();
        setId(fd, "h-2");
        fd.setCategory(debt);
        fd.setName("HDFC FD");
        fd.setValuationMethod(ValuationMethod.FIXED_RATE);
        fd.setBroker("HDFC Bank");
        fd.setCurrency("INR");
    }

    private static void setId(Object entity, String id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private Transaction txn(Holding holding, TransactionType type, LocalDate date, String amount) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setHolding(holding);
        t.setType(type);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    @Test
    void createValidatesHoldingOwnershipAndPersists() {
        when(holdingRepository.findByIdAndUser_Id("h-1", "u-1")).thenReturn(java.util.Optional.of(reliance));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.create(new TransactionRequest("h-1", TransactionType.BUY,
                LocalDate.of(2026, 1, 15), new BigDecimal("50000"), new BigDecimal("10"), null, "Initial buy"));

        assertThat(response.holdingName()).isEqualTo("Reliance");
        assertThat(response.categoryName()).isEqualTo("Growth Equity");
        assertThat(response.type()).isEqualTo(TransactionType.BUY);
        assertThat(response.currency()).isEqualTo("INR");
    }

    // Edit Transaction must pre-fill the amount actually saved (the holding's own currency), never
    // whatever the view-currency-converted list happened to show — get() takes no currency param
    // for exactly that reason.
    @Test
    void getReturnsTheAmountUnconverted() throws Exception {
        Transaction existing = txn(reliance, TransactionType.BUY, LocalDate.of(2026, 1, 15), "50000");
        setId(existing, "t-1");
        when(transactionRepository.findByIdAndUser_Id("t-1", "u-1")).thenReturn(java.util.Optional.of(existing));

        var response = service.get("t-1");

        assertThat(response.amount()).isEqualByComparingTo("50000");
        assertThat(response.currency()).isEqualTo("INR");
    }

    @Test
    void listFiltersByHoldingTypeAndDateRange() {
        List<Transaction> all = List.of(
                txn(reliance, TransactionType.BUY, LocalDate.of(2026, 1, 15), "50000"),
                txn(reliance, TransactionType.SELL, LocalDate.of(2026, 3, 1), "20000"),
                txn(fd, TransactionType.INTEREST, LocalDate.of(2026, 2, 1), "200000"));
        when(transactionRepository.findByUser_IdOrderByDateDescCreatedAtDesc("u-1")).thenReturn(all);

        var byHolding = service.list("h-1", null, null, null, null, null);
        assertThat(byHolding).hasSize(2).allMatch(t -> t.holdingId().equals("h-1"));

        var byType = service.list(null, TransactionType.INTEREST, null, null, null, null);
        assertThat(byType).hasSize(1);
        assertThat(byType.get(0).holdingName()).isEqualTo("HDFC FD");

        var byDateRange = service.list(null, null, null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1), null);
        assertThat(byDateRange).hasSize(2);

        var byBroker = service.list(null, null, "hdfc", null, null, null);
        assertThat(byBroker).hasSize(1);
        assertThat(byBroker.get(0).broker()).isEqualTo("HDFC Bank");
    }

    // Regression: current value used to only ever move via a manual Edit Holding edit or the
    // (throttled) market-price refresh, so a BUY/SELL logged from the Transactions page left it
    // stale — most visibly a partial sell, where invested value dropped correctly but current
    // value didn't, overstating what the remaining position was worth.
    @Test
    void createBuyIncreasesCurrentValueByTheAmount() {
        reliance.setCurrentValue(new BigDecimal("100000"));
        when(holdingRepository.findByIdAndUser_Id("h-1", "u-1")).thenReturn(java.util.Optional.of(reliance));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(new TransactionRequest("h-1", TransactionType.BUY, LocalDate.of(2026, 1, 15),
                new BigDecimal("20000"), new BigDecimal("5"), null, null));

        assertThat(reliance.getCurrentValue()).isEqualByComparingTo("120000.00");
    }

    @Test
    void createSellDecreasesCurrentValueByTheProceeds() {
        reliance.setCurrentValue(new BigDecimal("120000"));
        when(holdingRepository.findByIdAndUser_Id("h-1", "u-1")).thenReturn(java.util.Optional.of(reliance));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(new TransactionRequest("h-1", TransactionType.SELL, LocalDate.of(2026, 3, 1),
                new BigDecimal("20000"), new BigDecimal("5"), null, null));

        assertThat(reliance.getCurrentValue()).isEqualByComparingTo("100000.00");
    }

    @Test
    void updateChangingAmountAdjustsCurrentValueByTheDifference() throws Exception {
        reliance.setCurrentValue(new BigDecimal("120000"));
        Transaction existing = txn(reliance, TransactionType.BUY, LocalDate.of(2026, 1, 15), "20000");
        setId(existing, "t-1");
        when(transactionRepository.findByIdAndUser_Id("t-1", "u-1")).thenReturn(java.util.Optional.of(existing));
        when(holdingRepository.findByIdAndUser_Id("h-1", "u-1")).thenReturn(java.util.Optional.of(reliance));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update("t-1", new TransactionRequest("h-1", TransactionType.BUY, LocalDate.of(2026, 1, 15),
                new BigDecimal("30000"), new BigDecimal("5"), null, null));

        // Old 20000 reversed, new 30000 applied — net +10000 on top of the starting 120000.
        assertThat(reliance.getCurrentValue()).isEqualByComparingTo("130000.00");
    }

    @Test
    void deleteReversesTheCurrentValueEffect() throws Exception {
        reliance.setCurrentValue(new BigDecimal("120000"));
        Transaction existing = txn(reliance, TransactionType.BUY, LocalDate.of(2026, 1, 15), "20000");
        setId(existing, "t-1");
        when(transactionRepository.findByIdAndUser_Id("t-1", "u-1")).thenReturn(java.util.Optional.of(existing));

        service.delete("t-1");

        assertThat(reliance.getCurrentValue()).isEqualByComparingTo("100000.00");
    }

    @Test
    void adjustmentDoesNotTouchCurrentValue() {
        reliance.setCurrentValue(new BigDecimal("100000"));
        when(holdingRepository.findByIdAndUser_Id("h-1", "u-1")).thenReturn(java.util.Optional.of(reliance));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(new TransactionRequest("h-1", TransactionType.ADJUSTMENT, LocalDate.of(2026, 1, 15),
                new BigDecimal("5000"), null, null, null));

        assertThat(reliance.getCurrentValue()).isEqualByComparingTo("100000.00");
    }
}
