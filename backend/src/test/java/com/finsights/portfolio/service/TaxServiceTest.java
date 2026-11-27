package com.finsights.portfolio.service;

import static com.finsights.portfolio.service.TestHoldings.holding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.domain.TaxClass;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.TaxReportResponse;
import com.finsights.portfolio.dto.TaxReportResponse.Realised;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import com.finsights.portfolio.tax.FinancialYear;
import com.finsights.portfolio.tax.TaxRules.Term;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TaxServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2025, 11, 26);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock CurrentUserService currentUser;
    @Mock HoldingService holdingService;
    @Mock HoldingRepository holdingRepo;
    @Mock TransactionRepository transactionRepo;
    @Mock FxRateService fx;
    private TaxService service;
    private UserAccount user;
    private final List<Holding> holdings = new ArrayList<>();
    private final List<Transaction> ledger = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new TaxService(currentUser, holdingService, holdingRepo, transactionRepo, fx, CLOCK);
        user = new UserAccount("asha@example.com", "Asha");
        ReflectionTestUtils.setField(user, "id", "u-1");
        lenient().when(currentUser.currentUser()).thenReturn(user);
        lenient().when(holdingRepo.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u-1")).thenReturn(holdings);
        lenient().when(transactionRepo.findByUser_IdOrderByDateDescCreatedAtDesc("u-1")).thenReturn(ledger);
        lenient().when(fx.convert(any(BigDecimal.class), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void reportsAMixedPortfolioForTheCurrentYear() {
        Holding infy = add("A", "Domestic Stocks", "Infosys", "INFY.NS", ValuationMethod.MARKET_PRICE);
        tx(infy, TransactionType.BUY, "2024-01-10", "100000", "100");
        tx(infy, TransactionType.SELL, "2025-06-01", "300000", "100");          // LT +200,000
        Holding tcs = add("B", "Domestic Stocks", "TCS", "TCS.NS", ValuationMethod.MARKET_PRICE);
        tx(tcs, TransactionType.BUY, "2025-05-01", "10000", "10");
        tx(tcs, TransactionType.SELL, "2025-09-01", "15000", "10");             // ST +5,000
        Holding btc = add("C", "Crypto", "Bitcoin", null, ValuationMethod.MANUAL);
        tx(btc, TransactionType.BUY, "2025-04-10", "1000", null);
        tx(btc, TransactionType.SELL, "2025-10-01", "1500", null);              // VDA +500
        Holding eth = add("D", "Crypto", "Ether", null, ValuationMethod.MANUAL);
        tx(eth, TransactionType.BUY, "2025-04-10", "1000", null);
        tx(eth, TransactionType.SELL, "2025-10-01", "400", null);               // VDA −600, not set off
        Holding gold = add("E", "Commodities", "Gold coins", null, ValuationMethod.MANUAL);
        tx(gold, TransactionType.BUY, "2022-01-01", "1000", null);
        tx(gold, TransactionType.SELL, "2025-05-01", "2000", null);             // other LT +1,000
        Holding fd = add("F", "Savings", "SBI FD", null, ValuationMethod.FIXED_RATE);
        tx(fd, TransactionType.BUY, "2025-04-01", "100000", null);
        interest(fd, "2025-06-30", "700", true);
        interest(fd, "2025-09-30", "300", false);
        interest(fd, "2025-03-01", "999", true);                                // previous FY
        Holding epf = add("G", "Retirement", "EPF", null, ValuationMethod.MANUAL);
        epf.setManualType(ManualType.PROVIDENT_FUND);
        tx(epf, TransactionType.SELL, "2025-06-01", "50000", null);
        Holding open = add("H", "Domestic Stocks", "HDFC Bank", "HDFCBANK.NS", ValuationMethod.MARKET_PRICE);
        open.setTaxClass(TaxClass.LISTED_EQUITY);
        tx(open, TransactionType.BUY, "2025-01-01", "1000", "10");              // still short-term
        tx(open, TransactionType.BUY, "2024-01-01", "1000", "10");              // long-term
        Holding loan = add("L", "Loans", "Home loan", null, ValuationMethod.MANUAL);
        loan.getCategory().setKind(HoldingKind.LIABILITY);
        when(holdingService.list("INR")).thenReturn(List.of(holding("H").current("4000").build(), holding("A").build()));

        TaxReportResponse report = service.report(null);

        assertThat(report.financialYear()).isEqualTo("2025-26");
        assertThat(report.availableYears()).first().isEqualTo("2025-26");
        assertThat(report.availableYears()).last().isEqualTo("2021-22");
        var s = report.summary();
        assertThat(s.equityLongTerm()).isEqualByComparingTo("200000");
        assertThat(s.equityLongTermTax()).isEqualByComparingTo("9375");          // (200,000 − 125,000) × 12.5%
        assertThat(s.equityLongTermExemptionLeft()).isEqualByComparingTo("0");
        assertThat(s.equityShortTerm()).isEqualByComparingTo("5000");
        assertThat(s.equityShortTermTax()).isEqualByComparingTo("1000");
        assertThat(s.virtualDigitalAssetGains()).isEqualByComparingTo("-100");
        assertThat(s.virtualDigitalAssetTax()).isEqualByComparingTo("150");
        assertThat(s.otherLongTerm()).isEqualByComparingTo("1000");
        assertThat(s.otherLongTermTax()).isEqualByComparingTo("125");
        assertThat(s.interestIncome()).isEqualByComparingTo("1000");
        assertThat(s.estimatedTax()).isEqualByComparingTo("10650");
        assertThat(report.realised()).extracting(Realised::name).doesNotContain("EPF");
        assertThat(report.realised()).filteredOn(r -> r.name().equals("Infosys")).singleElement()
                .satisfies(r -> assertThat(r.term()).isEqualTo(Term.LONG));
        assertThat(report.interest()).singleElement().satisfies(i -> {
            assertThat(i.received()).isEqualByComparingTo("700");
            assertThat(i.accrued()).isEqualByComparingTo("300");
        });
        assertThat(report.unrealised()).singleElement().satisfies(u -> {
            assertThat(u.name()).isEqualTo("HDFC Bank");
            assertThat(u.gain()).isEqualByComparingTo("2000");
            assertThat(u.shortTermGain()).isEqualByComparingTo("1000");
            assertThat(u.longTermGain()).isEqualByComparingTo("1000");
            assertThat(u.longTermFrom()).isEqualTo(LocalDate.of(2026, 1, 2));
        });
        assertThat(report.holdings()).extracting(TaxReportResponse.Classification::name)
                .containsExactly("Bitcoin", "EPF", "Ether", "Gold coins", "HDFC Bank", "Infosys", "SBI FD", "TCS");
        assertThat(report.holdings()).filteredOn(c -> c.name().equals("HDFC Bank")).singleElement()
                .satisfies(c -> assertThat(c.inferred()).isFalse());
        assertThat(report.holdings()).filteredOn(c -> c.name().equals("EPF")).singleElement()
                .satisfies(c -> assertThat(c.taxClass()).isEqualTo(TaxClass.EXEMPT));
        assertThat(report.notes()).isNotEmpty();
    }

    @Test
    void anEarlierYearOnlyCountsItsOwnSales() {
        Holding infy = add("A", "Domestic Stocks", "Infosys", "INFY.NS", ValuationMethod.MARKET_PRICE);
        tx(infy, TransactionType.BUY, "2023-01-10", "1000", "10");
        tx(infy, TransactionType.SELL, "2024-06-01", "2000", "5");
        tx(infy, TransactionType.SELL, "2025-06-01", "2000", "5");
        when(holdingService.list("INR")).thenReturn(List.of());

        TaxReportResponse report = service.report("2024-25");

        assertThat(report.realised()).singleElement().satisfies(r -> assertThat(r.sold()).isEqualTo(LocalDate.of(2024, 6, 1)));
        assertThat(report.summary().equityLongTermExemption()).isEqualByComparingTo("125000");
        assertThat(report.unrealised()).isEmpty();
    }

    @Test
    void summaryAppliesOldRatesBeforeTheChangeAndTheExemptionToNewGainsFirst() {
        List<Realised> rows = List.of(
                row(TaxClass.LISTED_EQUITY, Term.SHORT, "2024-07-01", "1000"),
                row(TaxClass.LISTED_EQUITY, Term.LONG, "2024-07-01", "150000"),
                row(TaxClass.LISTED_EQUITY, Term.LONG, "2024-08-01", "50000"),
                row(TaxClass.OTHER_CAPITAL, Term.LONG, "2024-07-01", "1000"),
                row(TaxClass.OTHER_CAPITAL, Term.SHORT, "2024-08-01", "300"),
                row(TaxClass.DEBT_SLAB, Term.SLAB, "2024-08-01", "250"));

        var s = TaxService.summarise(new FinancialYear(2024), rows, List.of());

        assertThat(s.equityShortTermTax()).isEqualByComparingTo("150");          // 15% before 23 Jul
        assertThat(s.equityLongTermTax()).isEqualByComparingTo("7500");          // exemption covers the 50k first
        assertThat(s.equityLongTermExemptionLeft()).isEqualByComparingTo("0");
        assertThat(s.otherLongTerm()).isEqualByComparingTo("1000");
        assertThat(s.otherLongTermTax()).isEqualByComparingTo("0");              // indexation era, not estimated
        assertThat(s.otherShortTerm()).isEqualByComparingTo("300");
        assertThat(s.debtSlabGains()).isEqualByComparingTo("250");
    }

    @Test
    void lossesNeverProduceNegativeTaxAndLeaveTheExemptionUntouched() {
        var s = TaxService.summarise(new FinancialYear(2025), List.of(
                row(TaxClass.LISTED_EQUITY, Term.LONG, "2025-08-01", "-5000"),
                row(TaxClass.LISTED_EQUITY, Term.SHORT, "2025-08-01", "-100")), List.of());
        assertThat(s.estimatedTax()).isEqualByComparingTo("0");
        assertThat(s.equityLongTermExemptionLeft()).isEqualByComparingTo("125000");
    }

    @Test
    void foreignHoldingsAreConvertedToInr() {
        Holding apple = add("A", "International Stocks", "Apple", "AAPL", ValuationMethod.MARKET_PRICE);
        apple.setCurrency("USD");
        tx(apple, TransactionType.BUY, "2022-01-01", "100", "1");
        tx(apple, TransactionType.SELL, "2025-06-01", "200", "1");
        when(holdingService.list("INR")).thenReturn(List.of());
        when(fx.convert(any(BigDecimal.class), org.mockito.ArgumentMatchers.eq("USD"), org.mockito.ArgumentMatchers.eq("INR")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("88")));

        TaxReportResponse report = service.report("2025-26");

        assertThat(report.summary().otherLongTerm()).isEqualByComparingTo("8800");
    }

    @Test
    void slabAndFlatHoldingsShowOnlyATotalUnrealisedGain() {
        Holding fund = add("D", "Debt", "Liquid fund", null, ValuationMethod.MANUAL);
        tx(fund, TransactionType.BUY, "2020-01-01", "1000", null);
        when(holdingService.list("INR")).thenReturn(List.of(holding("D").current("1500").build()));

        var unrealised = service.report(null).unrealised();

        assertThat(unrealised).singleElement().satisfies(u -> {
            assertThat(u.taxClass()).isEqualTo(TaxClass.DEBT_SLAB);
            assertThat(u.gain()).isEqualByComparingTo("500");
            assertThat(u.shortTermGain()).isNull();
            assertThat(u.longTermGain()).isNull();
            assertThat(u.longTermFrom()).isNull();
        });
    }

    @Test
    void badYearIsABadRequest() {
        assertThatThrownBy(() -> service.report("2025"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void setTaxClassSavesTheOverrideOnlyOnTheUsersOwnHolding() {
        Holding infy = add("A", "Domestic Stocks", "Infosys", "INFY.NS", ValuationMethod.MARKET_PRICE);
        when(holdingRepo.findByIdAndUser_Id("A", "u-1")).thenReturn(Optional.of(infy));
        when(holdingRepo.findByIdAndUser_Id("X", "u-1")).thenReturn(Optional.empty());

        service.setTaxClass("A", TaxClass.DEBT_SLAB);

        assertThat(infy.getTaxClass()).isEqualTo(TaxClass.DEBT_SLAB);
        verify(holdingRepo).save(infy);
        assertThatThrownBy(() -> service.setTaxClass("X", null))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
    }

    private Holding add(String id, String categoryName, String name, String ticker, ValuationMethod method) {
        Category category = new Category();
        category.setName(categoryName);
        category.setKind(HoldingKind.ASSET);
        Holding h = new Holding();
        ReflectionTestUtils.setField(h, "id", id);
        h.setCategory(category);
        h.setName(name);
        h.setTickerSymbol(ticker);
        h.setCurrency("INR");
        h.setValuationMethod(method);
        holdings.add(h);
        return h;
    }

    private void tx(Holding holding, TransactionType type, String date, String amount, String units) {
        Transaction t = new Transaction();
        t.setHolding(holding);
        t.setType(type);
        t.setDate(LocalDate.parse(date));
        t.setAmount(new BigDecimal(amount));
        t.setQuantity(units == null ? null : new BigDecimal(units));
        ledger.add(0, t);   // the repository returns newest first
    }

    private void interest(Holding holding, String date, String amount, boolean paid) {
        tx(holding, TransactionType.INTEREST, date, amount, null);
        ledger.get(0).setInterestPaid(paid);
    }

    private static Realised row(TaxClass taxClass, Term term, String sold, String gain) {
        BigDecimal g = new BigDecimal(gain);
        return new Realised("h", "n", taxClass, LocalDate.parse("2020-01-01"), LocalDate.parse(sold),
                BigDecimal.ONE, BigDecimal.ZERO, g, g, term);
    }
}
