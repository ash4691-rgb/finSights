package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.api.ReturnsControllerAccess;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.ReturnsResponse;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReturnsServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 1);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock HoldingService holdings;
    @Mock TransactionRepository transactions;
    @Mock CurrentUserService currentUser;
    @Mock FxRateService fx;
    private ReturnsService service;
    private UserAccount user;
    private final List<Transaction> ledger = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ReturnsService(holdings, transactions, currentUser, fx, CLOCK);
        user = new UserAccount("demo@finsights.local", "Demo");
        ReflectionTestUtils.setField(user, "id", "u-1");
        lenient().when(currentUser.currentUser()).thenReturn(user);
        lenient().when(transactions.findByUser_IdOrderByDateDescCreatedAtDesc("u-1")).thenReturn(ledger);
        // Identity FX unless a test says otherwise.
        lenient().when(fx.convert(any(BigDecimal.class), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void holdingThatDoubledInAYearReturnsOneHundredPercent() {
        when(holdings.list("INR")).thenReturn(List.of(asset("h-1", "c-1", "Equity", "INR", "2000")));
        ledger.add(tx("h-1", TransactionType.BUY, "1000", TODAY.minusDays(365), false));

        ReturnsResponse result = service.returns(null);

        assertThat(result.currency()).isEqualTo("INR");
        assertThat(result.asOf()).isEqualTo(TODAY);
        ReturnsResponse.Figure holding = result.holdings().get(0).figure();
        assertThat(holding.xirrPercent()).isEqualByComparingTo("100.00");
        assertThat(holding.netInvested()).isEqualByComparingTo("1000.00");
        assertThat(holding.gain()).isEqualByComparingTo("1000.00");
        assertThat(holding.since()).isEqualTo(TODAY.minusDays(365));
        assertThat(holding.note()).isNull();
        assertThat(result.portfolio().xirrPercent()).isEqualByComparingTo("100.00");
        assertThat(result.categories()).singleElement()
                .satisfies(g -> assertThat(g.categoryName()).isEqualTo("Equity"));
    }

    @Test
    void sellsAndPaidInterestAreInflowsWhileSplitsAndAccruedInterestAreIgnored() {
        when(holdings.list("INR")).thenReturn(List.of(asset("h-1", "c-1", "Equity", "INR", "600")));
        ledger.add(tx("h-1", TransactionType.BUY, "1000", TODAY.minusDays(730), false));
        ledger.add(tx("h-1", TransactionType.SELL, "500", TODAY.minusDays(365), false));
        ledger.add(tx("h-1", TransactionType.INTEREST, "50", TODAY.minusDays(200), true));
        ledger.add(tx("h-1", TransactionType.INTEREST, "999", TODAY.minusDays(100), false));
        ledger.add(tx("h-1", TransactionType.SPLIT, "2", TODAY.minusDays(50), false));

        ReturnsResponse.Figure figure = service.returns("INR").holdings().get(0).figure();

        assertThat(figure.netInvested()).isEqualByComparingTo("450.00");   // 1000 − 500 − 50
        assertThat(figure.gain()).isEqualByComparingTo("150.00");
        assertThat(figure.xirrPercent()).isNotNull().isPositive();
    }

    @Test
    void liabilitiesAreExcluded() {
        HoldingResponse loan = holding("l-1", "c-9", "Loans", "INR", "5000", HoldingKind.LIABILITY);
        when(holdings.list("INR")).thenReturn(List.of(loan));

        ReturnsResponse result = service.returns("inr");

        assertThat(result.holdings()).isEmpty();
        assertThat(result.categories()).isEmpty();
        assertThat(result.portfolio().note()).isEqualTo(ReturnsService.NOTE_NO_FLOWS);
        assertThat(result.portfolio().xirrPercent()).isNull();
    }

    @Test
    void recentHoldingIsNotAnnualised() {
        when(holdings.list("INR")).thenReturn(List.of(asset("h-1", "c-1", "Equity", "INR", "1100")));
        ledger.add(tx("h-1", TransactionType.BUY, "1000", TODAY.minusDays(10), false));

        ReturnsResponse.Figure figure = service.returns("INR").holdings().get(0).figure();

        assertThat(figure.xirrPercent()).isNull();
        assertThat(figure.note()).isEqualTo(ReturnsService.NOTE_TOO_RECENT);
        assertThat(figure.gain()).isEqualByComparingTo("100.00");
    }

    @Test
    void fullyExitedHoldingWithZeroValueStillHasAReturn() {
        when(holdings.list("INR")).thenReturn(List.of(asset("h-1", "c-1", "Equity", "INR", "0")));
        ledger.add(tx("h-1", TransactionType.BUY, "1000", TODAY.minusDays(730), false));
        ledger.add(tx("h-1", TransactionType.SELL, "1210", TODAY.minusDays(0), false));

        ReturnsResponse.Figure figure = service.returns("INR").holdings().get(0).figure();

        assertThat(figure.xirrPercent()).isEqualByComparingTo("10.00");
    }

    @Test
    void unsolvableFlowsReportNoFlowsNote() {
        // Only an outflow and a zero current value: no inflow, so no IRR.
        when(holdings.list("INR")).thenReturn(List.of(asset("h-1", "c-1", "Equity", "INR", "0")));
        ledger.add(tx("h-1", TransactionType.BUY, "1000", TODAY.minusDays(400), false));

        ReturnsResponse.Figure figure = service.returns("INR").holdings().get(0).figure();

        assertThat(figure.xirrPercent()).isNull();
        assertThat(figure.note()).isEqualTo(ReturnsService.NOTE_NO_FLOWS);
    }

    @Test
    void mixedCurrencyGroupsConvertFlowsAndSayHow() {
        when(holdings.list("INR")).thenReturn(List.of(
                asset("h-1", "c-1", "Equity", "INR", "2000"),
                asset("h-2", "c-1", "Equity", "USD", "1770")));
        ledger.add(tx("h-1", TransactionType.BUY, "1000", TODAY.minusDays(365), false));
        ledger.add(tx("h-2", TransactionType.BUY, "10", TODAY.minusDays(365), false));
        when(fx.convert(any(BigDecimal.class), eq("USD"), eq("INR")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("88.5")));

        ReturnsResponse result = service.returns("INR");

        assertThat(result.portfolio().netInvested()).isEqualByComparingTo("1885.00");
        assertThat(result.portfolio().note()).isEqualTo(ReturnsService.NOTE_FX);
        assertThat(result.categories().get(0).figure().note()).isEqualTo(ReturnsService.NOTE_FX);
        assertThat(result.holdings()).extracting(ReturnsResponse.Item::holdingId).containsExactly("h-1", "h-2");
        verify(fx).convert(any(BigDecimal.class), eq("USD"), eq("INR"));
    }

    @Test
    void categoriesAndHoldingsAreSortedByCurrentValue() {
        when(holdings.list("INR")).thenReturn(List.of(
                asset("h-1", "c-1", "Small", "INR", "100"),
                asset("h-2", "c-2", "Big", "INR", "900")));

        ReturnsResponse result = service.returns("INR");

        assertThat(result.categories()).extracting(ReturnsResponse.Group::categoryName).containsExactly("Big", "Small");
        assertThat(result.holdings()).extracting(ReturnsResponse.Item::holdingId).containsExactly("h-2", "h-1");
    }

    @Test
    void currencyFallsBackToUserBaseThenInr() {
        user.setBaseCurrency("USD");
        when(holdings.list("USD")).thenReturn(List.of());
        assertThat(service.returns(" ").currency()).isEqualTo("USD");

        user.setBaseCurrency(" ");
        when(holdings.list("INR")).thenReturn(List.of());
        assertThat(service.returns(null).currency()).isEqualTo("INR");
    }

    @Test
    void signedAmountIgnoresIncompleteRowsAndNonCashTypes() {
        Transaction noAmount = tx("h", TransactionType.BUY, "1", TODAY, false);
        noAmount.setAmount(null);
        Transaction noType = tx("h", TransactionType.BUY, "1", TODAY, false);
        noType.setType(null);
        assertThat(ReturnsService.signedAmount(noAmount)).isZero();
        assertThat(ReturnsService.signedAmount(noType)).isZero();
        assertThat(ReturnsService.signedAmount(tx("h", TransactionType.REPAY, "5", TODAY, false))).isZero();
        assertThat(ReturnsService.signedAmount(tx("h", TransactionType.ADJUSTMENT, "5", TODAY, false))).isEqualTo(-5);
    }

    @Test
    void controllerDelegatesToService() {
        when(holdings.list("INR")).thenReturn(List.of());
        assertThat(ReturnsControllerAccess.call(service, "INR").currency()).isEqualTo("INR");
    }

    private static Transaction tx(String holdingId, TransactionType type, String amount, LocalDate date, boolean paid) {
        Holding holding = new Holding();
        ReflectionTestUtils.setField(holding, "id", holdingId);
        Transaction t = new Transaction();
        t.setHolding(holding);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setDate(date);
        t.setInterestPaid(paid);
        return t;
    }

    private static HoldingResponse asset(String id, String categoryId, String categoryName, String currency, String current) {
        return holding(id, categoryId, categoryName, currency, current, HoldingKind.ASSET);
    }

    private static HoldingResponse holding(String id, String categoryId, String categoryName, String currency, String current,
                                           HoldingKind kind) {
        return new HoldingResponse(
                id, id, categoryId, categoryName, "Holding " + id, kind, ValuationMethod.MANUAL,
                null, "Kite", "INR", currency, BigDecimal.ZERO, new BigDecimal(current), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, null, null, null, null, null, null, null, null, false, false, null, null,
                Set.of(), Instant.now(), Instant.now(), null, false, null);
    }
}
