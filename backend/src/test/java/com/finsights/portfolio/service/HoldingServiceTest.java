package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ManualDetails;
import com.finsights.portfolio.dto.MarketHistoryResponse;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    @Mock LivePriceService livePrices;
    @Mock com.finsights.portfolio.repository.EmiPaymentRepository emiPayments;

    private HoldingService service;
    private Holding holding;

    @BeforeEach
    void setUp() {
        service = new HoldingService(holdings, categories, currentUser, valuations, fx, transactions, snapshots, marketData, livePrices, emiPayments);
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
                null, null, null, investedValue, currentValue, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
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
        when(livePrices.getPrices(any())).thenReturn(Map.of("HUGE", new MarketQuoteResponse("HUGE", "Huge Co", new BigDecimal("500"), "INR", null)));
        when(fx.convert(any(), any(), any())).thenReturn(new BigDecimal("500"));
        when(valuations.currentValue(any())).thenReturn(new BigDecimal("100.00"));

        assertThatCode(service::list).doesNotThrowAnyException();
        assertThat(holding.getCurrentValue()).isEqualByComparingTo("100.00"); // last known-good value kept, not overwritten
        verify(holdings, never()).save(holding);
    }

    @Test
    void listBackfillsRealHistoricalPricesWhenNoDeepHistoryExistsYet() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        category.setName("Growth Equity");
        holding.setCategory(category);
        holding.setValuationMethod(ValuationMethod.MARKET_PRICE);
        holding.setTickerSymbol("RELIANCE");
        holding.setCurrency("INR");
        holding.setQuantity(new BigDecimal("10"));
        holding.setCurrentValue(new BigDecimal("100.00"));
        holding.setInvestedValue(new BigDecimal("100.00"));

        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        holding.setUser(user);
        when(currentUser.currentUser()).thenReturn(user);
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(any())).thenReturn(List.of(holding));
        when(transactions.findHoldingIdsWithTransactions(any())).thenReturn(new HashSet<>());
        when(livePrices.getPrices(any())).thenReturn(Map.of("RELIANCE", new MarketQuoteResponse("RELIANCE", "Reliance", new BigDecimal("500"), "INR", null)));
        when(fx.convert(any(), any(), any())).thenReturn(new BigDecimal("500"));
        when(valuations.currentValue(any())).thenReturn(new BigDecimal("100.00"));
        when(snapshots.hasSnapshotAtOrBefore(any(), any(), any())).thenReturn(false);
        when(marketData.history(eq("RELIANCE"), eq("1Y"))).thenReturn(Optional.of(new MarketHistoryResponse("RELIANCE", "INR", List.of(
                new MarketHistoryResponse.Point(Instant.now().minus(300, ChronoUnit.DAYS), new BigDecimal("450")),
                new MarketHistoryResponse.Point(Instant.now().minus(100, ChronoUnit.DAYS), new BigDecimal("480"))))));

        service.list();

        verify(snapshots, times(2)).recordAt(eq(SnapshotSubject.HOLDING), any(), eq(user), any(), any());
    }

    @Test
    void listSkipsTheHistoryBackfillOnceDeepHistoryAlreadyExists() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        category.setName("Growth Equity");
        holding.setCategory(category);
        holding.setValuationMethod(ValuationMethod.MARKET_PRICE);
        holding.setTickerSymbol("RELIANCE");
        holding.setCurrency("INR");
        holding.setQuantity(new BigDecimal("10"));
        holding.setCurrentValue(new BigDecimal("100.00"));
        holding.setInvestedValue(new BigDecimal("100.00"));

        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        holding.setUser(user);
        when(currentUser.currentUser()).thenReturn(user);
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(any())).thenReturn(List.of(holding));
        when(transactions.findHoldingIdsWithTransactions(any())).thenReturn(new HashSet<>());
        when(livePrices.getPrices(any())).thenReturn(Map.of("RELIANCE", new MarketQuoteResponse("RELIANCE", "Reliance", new BigDecimal("500"), "INR", null)));
        when(fx.convert(any(), any(), any())).thenReturn(new BigDecimal("500"));
        when(valuations.currentValue(any())).thenReturn(new BigDecimal("100.00"));
        when(snapshots.hasSnapshotAtOrBefore(any(), any(), any())).thenReturn(true);

        service.list();

        verify(marketData, never()).history(any(), any());
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
                null, null, null, new BigDecimal("999999.00"), new BigDecimal("400000.00"), null, null, null, null,
                com.finsights.portfolio.domain.RepaymentFrequency.MONTHLY, new BigDecimal("10000"), 5, null, null,
                null, null, null, null, null);

        service.update("h-1", request);

        verify(transactions, never()).save(any(Transaction.class));
    }

    // The currency dropdown is disabled in the Edit Holding form, but that's cosmetic — the
    // backend must refuse a currency change too, since existing transactions were booked in
    // whatever currency the holding had at creation ("first entry defines the linked currency").
    @Test
    void updateCannotChangeCurrency() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForUpdate(category);
        holding.setValuationMethod(ValuationMethod.MANUAL);
        holding.setInvestedValue(new BigDecimal("10000.00"));
        holding.setCurrentValue(new BigDecimal("12000.00"));
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "10000", null)));

        HoldingRequest request = new HoldingRequest("cat-1", "Reliance", ValuationMethod.MANUAL, null, "Kite", "USD",
                null, null, null, new BigDecimal("10000.00"), new BigDecimal("12000.00"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        service.update("h-1", request);

        assertThat(holding.getCurrency()).isEqualTo("INR");
    }

    private void stubForCreate(Category category) {
        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
        when(categories.findByIdAndUser_Id(any(), any())).thenReturn(Optional.of(category));
        when(holdings.findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(any(), any(), any())).thenReturn(Optional.empty());
        when(holdings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fx.supports(any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(valuations.currentValue(any())).thenAnswer(inv -> ((Holding) inv.getArgument(0)).getCurrentValue());
    }

    // A market-linked holding's currency must come from its ticker (USD for AMZN), not from
    // whatever the form happened to submit — closes the race where the client's own debounced
    // quote fetch hasn't resolved yet when the holding is saved.
    @Test
    void createForMarketLinkedHoldingUsesTickerCurrency() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForCreate(category);
        when(marketData.quote("AMZN")).thenReturn(Optional.of(
                new MarketQuoteResponse("AMZN", "Amazon.com", new BigDecimal("145.32"), "USD", Instant.now())));

        HoldingRequest request = new HoldingRequest("cat-1", "Amazon", ValuationMethod.MARKET_PRICE, "AMZN", "Zerodha", "INR",
                new BigDecimal("5"), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        var response = service.create(request);

        ArgumentCaptor<Holding> captor = ArgumentCaptor.forClass(Holding.class);
        verify(holdings, times(2)).save(captor.capture()); // once for the holding, once inside syncFromTransactions
        assertThat(captor.getAllValues().get(0).getCurrency()).isEqualTo("USD");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.defaultCurrency()).isEqualTo("USD");
    }

    // If the market feed can't resolve the symbol right now, fall back to whatever the form
    // submitted rather than failing the whole creation.
    @Test
    void createForMarketLinkedHoldingFallsBackToFormCurrencyWhenQuoteUnavailable() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForCreate(category);
        when(marketData.quote("AMZN")).thenReturn(Optional.empty());

        HoldingRequest request = new HoldingRequest("cat-1", "Amazon", ValuationMethod.MARKET_PRICE, "AMZN", "Zerodha", "USD",
                new BigDecimal("5"), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        service.create(request);

        ArgumentCaptor<Holding> captor = ArgumentCaptor.forClass(Holding.class);
        verify(holdings, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getCurrency()).isEqualTo("USD");
    }

    // The display-currency overloads of get/create/update (used by HoldingDrawer's post-
    // transaction refresh and HoldingModal's optimistic save) must be a no-op when the caller
    // doesn't pass a currency — same contract as list(String) — and must delegate to
    // FxRateService.convert(HoldingResponse, String) when it does.
    private void stubForGet(Category category) {
        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        holding.setUser(user);
        holding.setCategory(category);
        when(currentUser.currentUser()).thenReturn(user);
        when(holdings.findByIdAndUser_Id(any(), any())).thenReturn(Optional.of(holding));
        when(valuations.currentValue(any())).thenAnswer(inv -> ((Holding) inv.getArgument(0)).getCurrentValue());
    }

    @Test
    void getWithNoDisplayCurrencyReturnsTheNativeResponseUnconverted() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForGet(category);
        holding.setValuationMethod(ValuationMethod.MANUAL);
        holding.setCurrentValue(new BigDecimal("12000.00"));

        assertThat(service.get("h-1", null).currentValue()).isEqualByComparingTo("12000.00");
        assertThat(service.get("h-1", "").currentValue()).isEqualByComparingTo("12000.00");
        verify(fx, never()).convert(any(HoldingResponse.class), any());
    }

    @Test
    void getWithADisplayCurrencyReturnsTheConvertedResponse() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForGet(category);
        holding.setValuationMethod(ValuationMethod.MANUAL);
        holding.setCurrentValue(new BigDecimal("12000.00"));
        HoldingResponse converted = org.mockito.Mockito.mock(HoldingResponse.class);
        when(fx.convert(any(HoldingResponse.class), eq("USD"))).thenReturn(converted);

        HoldingResponse result = service.get("h-1", "usd");

        assertThat(result).isSameAs(converted);
    }

    @Test
    void createWithADisplayCurrencyReturnsTheConvertedResponse() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForCreate(category);
        HoldingResponse converted = org.mockito.Mockito.mock(HoldingResponse.class);
        when(fx.convert(any(HoldingResponse.class), eq("USD"))).thenReturn(converted);
        HoldingRequest request = new HoldingRequest("cat-1", "Reliance", ValuationMethod.MANUAL, null, "Kite", "INR",
                null, null, null, new BigDecimal("10000"), new BigDecimal("12000"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        HoldingResponse result = service.create(request, "usd");

        assertThat(result).isSameAs(converted);
    }

    @Test
    void updateWithADisplayCurrencyReturnsTheConvertedResponse() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForUpdate(category);
        holding.setValuationMethod(ValuationMethod.MANUAL);
        holding.setInvestedValue(new BigDecimal("10000.00"));
        holding.setCurrentValue(new BigDecimal("12000.00"));
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "10000", null)));
        HoldingResponse converted = org.mockito.Mockito.mock(HoldingResponse.class);
        when(fx.convert(any(HoldingResponse.class), eq("USD"))).thenReturn(converted);

        HoldingResponse result = service.update("h-1", editRequest(new BigDecimal("10000.00"), new BigDecimal("12000.00")), "usd");

        assertThat(result).isSameAs(converted);
    }

    // Real estate is one holding per unit — quantity doesn't mean anything, so it's forced to 1
    // (ignoring whatever the request sends) and the holding takes an area + unit instead.
    @Test
    void createRealEstateHoldingForcesQuantityToOneAndStoresArea() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        category.setRealEstate(true);
        stubForCreate(category);
        // The ledger the just-booked opening BUY (quantity forced to 1 by copy()) would resolve to —
        // syncFromTransactions() re-derives quantity from this, same as it would against a real DB.
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "5000000", "1")));
        HoldingRequest request = realEstateRequest(new BigDecimal("1200"), new BigDecimal("4000"), null);

        HoldingResponse response = service.create(request);

        assertThat(response.quantity()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(response.manualType()).isEqualTo(ManualType.REAL_ESTATE);
        ArgumentCaptor<Holding> saved = ArgumentCaptor.forClass(Holding.class);
        verify(holdings, times(2)).save(saved.capture());
        Holding stored = saved.getAllValues().get(0);
        assertThat(openingAmount()).isEqualByComparingTo("4800000");
        assertThat(stored.getCurrentValue()).isEqualByComparingTo("4800000");
        assertThat(stored.getCurrentUnitPrice()).isEqualByComparingTo("4000");
        assertThat(response.area()).isEqualByComparingTo("1200");
        assertThat(response.areaUnit()).isEqualTo(com.finsights.portfolio.domain.AreaUnit.SQ_FT);
    }

    @Test
    void createRealEstateHoldingWithoutAreaIsRejected() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
        when(categories.findByIdAndUser_Id(any(), any())).thenReturn(Optional.of(category));
        HoldingRequest request = realEstateRequest(null, new BigDecimal("4000"), null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("area");
    }

    @Test
    void createNonRealEstateHoldingNeverPersistsArea() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForCreate(category);
        HoldingRequest request = new HoldingRequest("cat-1", "Reliance", ValuationMethod.MANUAL, null, "Kite", "INR",
                null, new BigDecimal("1200"), com.finsights.portfolio.domain.AreaUnit.SQ_FT,
                new BigDecimal("10000"), new BigDecimal("12000"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        service.create(request);

        ArgumentCaptor<Holding> captor = ArgumentCaptor.forClass(Holding.class);
        verify(holdings, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getArea()).isNull();
        assertThat(captor.getAllValues().get(0).getAreaUnit()).isNull();
    }

    /** The invested value the holding was opened with — the ledger owns it afterwards, so read the opening transaction. */
    private BigDecimal openingAmount() {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactions).save(captor.capture());
        return captor.getValue().getAmount();
    }

    private HoldingRequest realEstateRequest(BigDecimal area, BigDecimal purchasePrice, BigDecimal currentPrice) {
        ManualDetails details = new ManualDetails(purchasePrice, currentPrice, null, null, null, null);
        return new HoldingRequest("cat-1", "2BHK Flat", ValuationMethod.MANUAL, null, "Self", "INR",
                new BigDecimal("99"), area, com.finsights.portfolio.domain.AreaUnit.SQ_FT,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                ManualType.REAL_ESTATE, details);
    }

    private HoldingRequest providentFundRequest(BigDecimal total, ManualDetails details) {
        return new HoldingRequest("cat-1", "EPF", ValuationMethod.MANUAL, null, "EPFO", "INR",
                null, null, null, total, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                ManualType.PROVIDENT_FUND, details);
    }

    @Test
    void createProvidentFundHoldingStoresContributionsAndDerivesCurrentValue() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForCreate(category);
        ManualDetails details = new ManualDetails(null, null, new BigDecimal("100000"), new BigDecimal("60000"),
                new BigDecimal("12000"), new BigDecimal("0.0825"), new BigDecimal("1800"), new BigDecimal("1800"));

        HoldingResponse response = service.create(providentFundRequest(null, details));

        ArgumentCaptor<Holding> saved = ArgumentCaptor.forClass(Holding.class);
        verify(holdings, times(2)).save(saved.capture());
        Holding stored = saved.getAllValues().get(0);
        assertThat(stored.getManualType()).isEqualTo(ManualType.PROVIDENT_FUND);
        assertThat(openingAmount()).isEqualByComparingTo("148000");
        assertThat(stored.getCurrentValue()).isEqualByComparingTo("160000");
        assertThat(stored.getQuantity()).isNull();
        assertThat(stored.getArea()).isNull();
        assertThat(stored.getExpectedInterestRate()).isEqualByComparingTo("0.0825");
        assertThat(stored.getMonthlyEmployeeContribution()).isEqualByComparingTo("1800");
        assertThat(response.manual().monthlyEmployerContribution()).isEqualByComparingTo("1800");
        assertThat(response.manualType()).isEqualTo(ManualType.PROVIDENT_FUND);
        assertThat(stored.getLastContributionMonth()).isEqualTo(java.time.YearMonth.now().toString());
    }

    @Test
    void providentFundWithoutMonthlyAmountsIsNotTrackedForContributions() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForCreate(category);
        ManualDetails details = new ManualDetails(null, null, null, null, null, null, BigDecimal.ZERO, null);

        service.create(providentFundRequest(new BigDecimal("1000"), details));

        ArgumentCaptor<Holding> saved = ArgumentCaptor.forClass(Holding.class);
        verify(holdings, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getLastContributionMonth()).isNull();
    }

    @Test
    void editingKeepsTheContributionStartMonthWhileMonthlyAmountsRemain() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForUpdate(category);
        holding.setValuationMethod(ValuationMethod.MANUAL);
        holding.setManualType(ManualType.PROVIDENT_FUND);
        holding.setLastContributionMonth("2026-01");
        holding.setInvestedValue(new BigDecimal("1000"));
        holding.setCurrentValue(new BigDecimal("1000"));
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(txn(TransactionType.BUY, "1000", null)));
        ManualDetails details = new ManualDetails(null, null, null, null, null, null, new BigDecimal("500"), null);

        service.update("h-1", providentFundRequest(new BigDecimal("1000"), details));

        assertThat(holding.getLastContributionMonth()).isEqualTo("2026-01");
    }

    @Test
    void createPlainHoldingDropsManualDetails() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForCreate(category);
        ManualDetails details = new ManualDetails(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"), null, null, null);
        HoldingRequest request = new HoldingRequest("cat-1", "Reliance", ValuationMethod.MANUAL, null, "Kite", "INR",
                null, null, null, new BigDecimal("10000"), new BigDecimal("12000"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, details);

        service.create(request);

        ArgumentCaptor<Holding> saved = ArgumentCaptor.forClass(Holding.class);
        verify(holdings, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getManualType()).isNull();
        assertThat(saved.getAllValues().get(0).getPurchaseUnitPrice()).isNull();
        assertThat(saved.getAllValues().get(0).getEmployeeContribution()).isNull();
    }

    @Test
    void createRealEstateHoldingInLiabilityCategoryIsRejected() {
        Category category = new Category();
        category.setKind(HoldingKind.LIABILITY);
        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
        when(categories.findByIdAndUser_Id(any(), any())).thenReturn(Optional.of(category));

        assertThatThrownBy(() -> service.create(realEstateRequest(new BigDecimal("10"), new BigDecimal("5"), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("liability");
    }

    @Test
    void editingRealEstateUnitPriceRecalculatesValuesAndBooksAnAdjustment() {
        Category category = new Category();
        category.setKind(HoldingKind.ASSET);
        stubForUpdate(category);
        holding.setValuationMethod(ValuationMethod.MANUAL);
        holding.setManualType(ManualType.REAL_ESTATE);
        holding.setInvestedValue(new BigDecimal("4800000.00"));
        holding.setCurrentValue(new BigDecimal("4800000.00"));
        when(transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(any())).thenReturn(List.of(
                txn(TransactionType.BUY, "4800000", "1"), txn(TransactionType.ADJUSTMENT, "120000", null)));

        service.update("h-1", realEstateRequest(new BigDecimal("1200"), new BigDecimal("4100"), new BigDecimal("5000")));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactions).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("120000.00");
        assertThat(holding.getCurrentValue()).isEqualByComparingTo("6000000.00");
        assertThat(holding.getPurchaseUnitPrice()).isEqualByComparingTo("4100");
    }
}
