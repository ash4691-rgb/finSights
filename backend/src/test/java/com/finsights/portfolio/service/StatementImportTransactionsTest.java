package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.ExtractedHolding;
import com.finsights.portfolio.dto.ExtractedTransaction;
import com.finsights.portfolio.dto.InstrumentKind;
import com.finsights.portfolio.dto.StatementExtractionResponse;
import com.finsights.portfolio.dto.StatementExtractionResponse.StatementType;
import com.finsights.portfolio.dto.SymbolSuggestion;
import com.finsights.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Transaction statements (new holdings from BUYs, orphan SELLs, name-identified kinds) and crypto/commodity rows. */
@ExtendWith(MockitoExtension.class)
class StatementImportTransactionsTest {
    @Mock StatementImportRateLimiter rateLimiter;
    @Mock CurrentUserService currentUser;
    @Mock MarketDataService marketData;
    @Mock HoldingRepository holdings;
    private final ObjectMapper json = new ObjectMapper();
    private StatementImportService service;

    @BeforeEach
    void setUp() { service = new StatementImportService(rateLimiter, currentUser, marketData, holdings); }

    private StatementExtractionResponse parse(ObjectNode toolInput) {
        ObjectNode response = json.createObjectNode();
        response.putArray("content").addObject().put("type", "tool_use").put("name", "extract_statement").set("input", toolInput);
        return StatementImportService.parseExtraction(response);
    }

    private static ExtractedTransaction txn(TransactionType type, String name, String isin, InstrumentKind kind) {
        return new ExtractedTransaction("2026-01-02", type, name, isin, BigDecimal.ONE, BigDecimal.TEN, null, null, kind, null, null);
    }

    // ---- parsing ----

    @Test
    void aCryptoHoldingKeepsItsSymbolAndNeedsNoIsin() {
        ObjectNode input = json.createObjectNode();
        input.put("statementType", "HOLDINGS");
        var rows = input.putArray("holdings");
        rows.addObject().put("instrumentName", "Bitcoin").put("symbol", "BTC").put("kind", "CRYPTO").put("quantity", 0.25).put("investedValue", 500000);
        rows.addObject().put("instrumentName", "Digital Gold").put("kind", "COMMODITY").put("quantity", 3.5);

        StatementExtractionResponse parsed = parse(input);

        assertThat(parsed.holdings()).hasSize(2);
        assertThat(parsed.holdings().get(0).symbol()).isEqualTo("BTC");
        assertThat(parsed.holdings().get(0).kind()).isEqualTo(InstrumentKind.CRYPTO);
        assertThat(parsed.holdings().get(1).kind()).isEqualTo(InstrumentKind.COMMODITY);
    }

    @Test
    void aMutualFundOrCryptoTransactionNeedsNoIsinButAKindlessOneWithoutIsinIsSkipped() {
        ObjectNode input = json.createObjectNode();
        input.put("statementType", "TRANSACTIONS");
        var rows = input.putArray("transactions");
        rows.addObject().put("date", "2026-01-02").put("type", "BUY").put("instrumentName", "Alpha Fund").put("kind", "MUTUAL_FUND").put("quantity", 10).put("amount", 1000);
        rows.addObject().put("date", "2026-01-03").put("type", "BUY").put("instrumentName", "Bitcoin").put("symbol", "BTC").put("kind", "CRYPTO").put("amount", 5000);
        rows.addObject().put("date", "2026-01-04").put("type", "BUY").put("instrumentName", "Mystery").put("quantity", 1).put("amount", 5);
        rows.addObject().put("date", "2026-01-05").put("type", "BUY").put("instrumentName", "Infosys").put("isin", "ine009a01021").put("quantity", 1).put("amount", 5);

        StatementExtractionResponse parsed = parse(input);

        assertThat(parsed.transactions()).hasSize(3);
        assertThat(parsed.transactions().get(0).isin()).isNull();
        assertThat(parsed.transactions().get(0).kind()).isEqualTo(InstrumentKind.MUTUAL_FUND);
        assertThat(parsed.transactions().get(1).symbol()).isEqualTo("BTC");
        assertThat(parsed.transactions().get(2).isin()).isEqualTo("INE009A01021");
        assertThat(parsed.transactions().get(2).kind()).isEqualTo(InstrumentKind.STOCK);
        assertThat(parsed.warnings()).hasSize(1);
    }

    @Test
    void theSchemaAndPromptCoverEveryKindAndTheUnitsTimesPriceRule() {
        ObjectNode body = StatementImportService.buildTextRequestBody(json, "m", "x");
        var props = body.path("tools").path(0).path("input_schema").path("properties");
        var holdingProps = props.path("holdings").path("items").path("properties");
        var txnItems = props.path("transactions").path("items");

        assertThat(holdingProps.path("kind").path("enum").toString()).contains("CRYPTO").contains("COMMODITY");
        assertThat(holdingProps.has("symbol")).isTrue();
        assertThat(txnItems.path("properties").path("kind").path("enum").toString()).contains("MUTUAL_FUND");
        assertThat(txnItems.path("properties").has("symbol")).isTrue();
        assertThat(txnItems.path("required").toString()).contains("kind").doesNotContain("isin");
        assertThat(body.path("system").asText()).contains("crypto").contains("units × price").contains("redemption");
    }

    // ---- name-identified kinds ----

    @Test
    void onlyNameIdentifiedKindsAreMergedAndKeyed() {
        assertThat(InstrumentKind.STOCK.identifiedByName()).isFalse();
        assertThat(InstrumentKind.ETF.identifiedByName()).isFalse();
        assertThat(InstrumentKind.MUTUAL_FUND.identifiedByName()).isTrue();
        assertThat(InstrumentKind.CRYPTO.identifiedByName()).isTrue();
        assertThat(InstrumentKind.COMMODITY.identifiedByName()).isTrue();

        ExtractedHolding wallet1 = new ExtractedHolding("Bitcoin", null, new BigDecimal("0.1"), null, null, null, null, InstrumentKind.CRYPTO, null, null, "BTC");
        ExtractedHolding wallet2 = new ExtractedHolding("bitcoin", null, new BigDecimal("0.2"), null, null, null, null, InstrumentKind.CRYPTO, null, null, "BTC");
        List<ExtractedHolding> merged = StatementRows.consolidate(List.of(wallet1, wallet2));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).quantity()).isEqualByComparingTo("0.3");
        assertThat(merged.get(0).symbol()).isEqualTo("BTC");
    }

    @Test
    void aKindlessRowIsNeverMerged() {
        ExtractedHolding a = new ExtractedHolding("X", null, BigDecimal.ONE, null, null, null, null, null, null, null);
        assertThat(StatementRows.consolidate(List.of(a, a))).hasSize(1);
        assertThat(StatementRows.consolidate(List.of(a, a)).get(0).quantity()).isEqualByComparingTo("1");
    }

    @Test
    void transactionsAreKeyedByIsinOtherwiseNormalisedName() {
        assertThat(StatementRows.transactionKey(txn(TransactionType.BUY, "Infosys", "INE009A01021", InstrumentKind.STOCK))).isEqualTo("INE009A01021");
        assertThat(StatementRows.transactionKey(txn(TransactionType.BUY, " Alpha   Fund ", null, InstrumentKind.MUTUAL_FUND))).isEqualTo("ALPHA FUND");
    }

    // ---- tickers by kind ----

    @Test
    void aCoinGetsItsInrPairMatchedBySymbolOrNameButNeverAUsdPair() {
        when(marketData.search("BTC")).thenReturn(List.of(
                new SymbolSuggestion("BTC-USD", "Bitcoin USD", "CCC", "Cryptocurrency"),
                new SymbolSuggestion("BTC-INR", "Bitcoin INR", "CCC", "Cryptocurrency")));
        when(marketData.search("Ethereum")).thenReturn(List.of(new SymbolSuggestion("ETH-INR", "Ethereum INR", "CCC", "Cryptocurrency")));
        when(marketData.search("Dogecoin")).thenReturn(List.of(new SymbolSuggestion("DOGE-USD", "Dogecoin USD", "CCC", "Cryptocurrency")));
        when(marketData.search("Mystery")).thenReturn(List.of(new SymbolSuggestion("ZZZ-INR", "Something Else INR", "CCC", "Cryptocurrency"),
                new SymbolSuggestion(null, "No symbol", "CCC", "Cryptocurrency")));

        assertThat(service.resolveTicker("Bitcoin", "BTC", InstrumentKind.CRYPTO)).isEqualTo("BTC-INR");
        assertThat(service.resolveTicker("Ethereum", null, InstrumentKind.CRYPTO)).isEqualTo("ETH-INR");
        assertThat(service.resolveTicker("Dogecoin", null, InstrumentKind.CRYPTO)).isNull();
        assertThat(service.resolveTicker("Mystery", null, InstrumentKind.CRYPTO)).isNull();
    }

    @Test
    void aCommodityIsNeverGuessedAndNeverSearched() {
        assertThat(service.resolveTicker("Digital Gold", null, InstrumentKind.COMMODITY)).isNull();
        verifyNoInteractions(marketData);
    }

    @Test
    void theTwoArgumentOverloadsDefaultTheSymbolAndKind() {
        when(marketData.search("Infosys Ltd")).thenReturn(List.of(new SymbolSuggestion("INFY.NS", "Infosys Ltd", "NSE", "Equity")));
        assertThat(service.resolveTicker("Infosys Ltd")).isEqualTo("INFY.NS");
        assertThat(service.resolveTicker("Infosys Ltd", InstrumentKind.STOCK)).isEqualTo("INFY.NS");
    }

    // ---- new holdings from BUYs ----

    private static StatementExtractionResponse withTransactions(List<ExtractedTransaction> rows) {
        return new StatementExtractionResponse(StatementType.TRANSACTIONS, List.of(), List.of(), rows, null, List.of(), "Groww");
    }

    @Test
    void eachNewInstrumentsBuysShareOneTickerLookupAndMatchedOrSellRowsAreLeftAlone() {
        when(marketData.search("Infosys Ltd")).thenReturn(List.of(new SymbolSuggestion("INFY.NS", "Infosys Ltd", "NSE", "Equity")));
        ExtractedTransaction buy1 = txn(TransactionType.BUY, "Infosys Ltd", "INE009A01021", InstrumentKind.STOCK);
        ExtractedTransaction buy2 = txn(TransactionType.BUY, "Infosys Ltd", "INE009A01021", InstrumentKind.STOCK);
        ExtractedTransaction sell = txn(TransactionType.SELL, "Infosys Ltd", "INE009A01021", InstrumentKind.STOCK);
        ExtractedTransaction matched = txn(TransactionType.BUY, "Held Co", "INE000A01010", InstrumentKind.STOCK).withMatchedHolding("h-1");
        ExtractedTransaction kindless = new ExtractedTransaction("2026-01-02", TransactionType.BUY, "Infosys Ltd", "INE009A01021",
                BigDecimal.ONE, BigDecimal.TEN, null, null);

        StatementExtractionResponse result = service.withTickersForNewBuys(withTransactions(List.of(buy1, buy2, sell, matched, kindless)));

        assertThat(result.transactions().get(0).tickerSymbol()).isEqualTo("INFY.NS");
        assertThat(result.transactions().get(1).tickerSymbol()).isEqualTo("INFY.NS");
        assertThat(result.transactions().get(2).tickerSymbol()).isNull();
        assertThat(result.transactions().get(3).tickerSymbol()).isNull();
        assertThat(result.transactions().get(4).tickerSymbol()).isEqualTo("INFY.NS");
        verify(marketData).search("Infosys Ltd");
        verify(marketData, never()).search("Held Co");
        assertThat(result.broker()).isEqualTo("Groww");
    }

    @Test
    void tickerLookupsForNewInstrumentsAreCapped() {
        when(marketData.search(anyString())).thenReturn(List.of());
        List<ExtractedTransaction> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(txn(TransactionType.BUY, "Fund " + i, null, InstrumentKind.MUTUAL_FUND));

        StatementExtractionResponse result = service.withTickersForNewBuys(withTransactions(rows));

        assertThat(result.transactions()).hasSize(40);
        verify(marketData, org.mockito.Mockito.times(30)).search(anyString());
    }

    // ---- orphan SELLs ----

    @Test
    void aSellWhoseInstrumentIsNeitherHeldNorBoughtInTheStatementIsFlaggedOnce() {
        List<ExtractedTransaction> rows = List.of(
                txn(TransactionType.SELL, "Orphan One", "INE111A01011", InstrumentKind.STOCK),
                txn(TransactionType.SELL, "Orphan One", "INE111A01011", InstrumentKind.STOCK),
                txn(TransactionType.SELL, "Orphan Two", null, InstrumentKind.MUTUAL_FUND),
                txn(TransactionType.SELL, "Bought Here", "INE222A01022", InstrumentKind.STOCK),
                txn(TransactionType.BUY, "Bought Here", "INE222A01022", InstrumentKind.STOCK),
                txn(TransactionType.SELL, "Held Already", "INE333A01033", InstrumentKind.STOCK).withMatchedHolding("h-1"));

        String warning = StatementImportService.unknownSellWarning(rows, " Groww ").orElseThrow();

        assertThat(warning).contains("3 SELL transactions across 2 instruments").contains("Orphan One").contains("Orphan Two")
                .contains("at Groww").doesNotContain("Bought Here").doesNotContain("Held Already");
    }

    @Test
    void noWarningWhenEverySellHasSomethingToSellFromAndLongListsAreTruncated() {
        assertThat(StatementImportService.unknownSellWarning(List.of(txn(TransactionType.BUY, "A", "I1", InstrumentKind.STOCK)), "Groww")).isEmpty();

        List<ExtractedTransaction> many = new ArrayList<>();
        for (int i = 0; i < 7; i++) many.add(txn(TransactionType.SELL, "Orphan" + i, "INE00" + i + "A01011", InstrumentKind.STOCK));
        assertThat(StatementImportService.unknownSellWarning(many, "Groww").orElseThrow()).contains("7 SELL transactions").contains("and 2 more");

        List<ExtractedTransaction> one = List.of(txn(TransactionType.SELL, "Solo", "I9", InstrumentKind.STOCK));
        assertThat(StatementImportService.unknownSellWarning(one, "Groww").orElseThrow()).contains("1 SELL transaction across 1 instrument (Solo)");
    }

    @Test
    void reconcilingKeepsKindSymbolAndTickerOnEveryRow() {
        ExtractedTransaction row = new ExtractedTransaction("2026-01-02", TransactionType.BUY, "Bitcoin", null, BigDecimal.ONE,
                BigDecimal.TEN, "n", null, InstrumentKind.CRYPTO, "BTC", "BTC-INR");
        ExtractedTransaction matched = row.withMatchedHolding("h-9");

        assertThat(matched.matchedHoldingId()).isEqualTo("h-9");
        assertThat(matched.kind()).isEqualTo(InstrumentKind.CRYPTO);
        assertThat(matched.symbol()).isEqualTo("BTC");
        assertThat(matched.tickerSymbol()).isEqualTo("BTC-INR");
        assertThat(row.withTicker("X").tickerSymbol()).isEqualTo("X");
    }

    // ---- CSV / aggregator layouts: the file reaches the model whole, and what it reports reconciles ----

    private static final String MF_ORDER_CSV = """
            Scheme Name,Transaction Type,Date,Units,NAV,Amount,Status
            Alpha Small Cap Fund Direct Growth,SIP,02-01-2026,10.5,95.2381,1000,Success
            Alpha Small Cap Fund Direct Growth,Redemption,02-03-2026,4,110.00,440,Success
            Beta Flexi Cap Fund Direct Growth,Purchase,05-01-2026,20,50.00,1000,Failed
            """;

    private void signedInAs(String userId, List<com.finsights.portfolio.domain.Holding> existing) {
        com.finsights.portfolio.domain.UserAccount user = org.mockito.Mockito.mock(com.finsights.portfolio.domain.UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(currentUser.currentUser()).thenReturn(user);
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId)).thenReturn(existing);
    }

    @Test
    void aMutualFundOrderCsvReachesTheModelWholeAndItsRowsReconcileByNameAtTheBroker() {
        ObjectNode body = StatementImportService.buildTextRequestBody(json, "m", MF_ORDER_CSV);
        String prompt = body.path("messages").path(0).path("content").path(0).path("text").asText();
        assertThat(prompt).contains("Scheme Name,Transaction Type,Date,Units,NAV,Amount,Status").contains("Redemption");

        // What the model hands back: the SIP is a BUY, the redemption a SELL, the failed order is dropped.
        ObjectNode input = json.createObjectNode();
        input.put("statementType", "TRANSACTIONS").put("broker", "Groww");
        var rows = input.putArray("transactions");
        rows.addObject().put("date", "2026-01-02").put("type", "BUY").put("instrumentName", "Alpha Small Cap Fund Direct Growth")
                .put("kind", "MUTUAL_FUND").put("quantity", 10.5).put("amount", 1000);
        rows.addObject().put("date", "2026-03-02").put("type", "SELL").put("instrumentName", "Alpha Small Cap Fund Direct Growth")
                .put("kind", "MUTUAL_FUND").put("quantity", 4).put("amount", 440);
        StatementExtractionResponse parsed = parse(input);
        assertThat(parsed.transactions()).hasSize(2);
        assertThat(parsed.broker()).isEqualTo("Groww");

        com.finsights.portfolio.domain.Holding held = new com.finsights.portfolio.domain.Holding();
        org.springframework.test.util.ReflectionTestUtils.setField(held, "id", "h-alpha");
        held.setName("Alpha Small Cap Fund Direct Growth");
        held.setBroker("Groww");
        signedInAs("u1", List.of(held));

        StatementExtractionResponse result = service.reconcile(new com.finsights.portfolio.dto.StatementReconcileRequest(
                StatementType.TRANSACTIONS, "Groww", null, List.of(), parsed.transactions(), parsed.warnings()));

        assertThat(result.transactions()).extracting(ExtractedTransaction::matchedHoldingId).containsOnly("h-alpha");
        assertThat(result.warnings()).isEmpty();
    }

    private static final String CRYPTO_CSV = """
            Date,Pair,Side,Quantity,Price (INR),Total (INR)
            2026-01-05,BTC/INR,BUY,0.01,5000000,50000
            2026-02-05,BTC/INR,BUY,0.02,5200000,104000
            2026-03-05,ETH/INR,SELL,1,250000,250000
            """;

    @Test
    void aCryptoExchangeCsvBecomesANewHoldingFromItsFirstBuyWithAnInrTickerAndFlagsTheOrphanSell() {
        ObjectNode body = StatementImportService.buildTextRequestBody(json, "m", CRYPTO_CSV);
        assertThat(body.path("messages").path(0).path("content").path(0).path("text").asText()).contains("BTC/INR");

        ObjectNode input = json.createObjectNode();
        input.put("statementType", "TRANSACTIONS");
        var rows = input.putArray("transactions");
        rows.addObject().put("date", "2026-01-05").put("type", "BUY").put("instrumentName", "Bitcoin").put("symbol", "BTC").put("kind", "CRYPTO").put("quantity", 0.01).put("amount", 50000);
        rows.addObject().put("date", "2026-02-05").put("type", "BUY").put("instrumentName", "Bitcoin").put("symbol", "BTC").put("kind", "CRYPTO").put("quantity", 0.02).put("amount", 104000);
        rows.addObject().put("date", "2026-03-05").put("type", "SELL").put("instrumentName", "Ethereum").put("symbol", "ETH").put("kind", "CRYPTO").put("quantity", 1).put("amount", 250000);
        StatementExtractionResponse parsed = parse(input);
        assertThat(parsed.transactions()).hasSize(3);

        when(marketData.search("BTC")).thenReturn(List.of(new SymbolSuggestion("BTC-INR", "Bitcoin INR", "CCC", "Cryptocurrency")));
        signedInAs("u1", List.of());

        StatementExtractionResponse result = service.reconcile(new com.finsights.portfolio.dto.StatementReconcileRequest(
                StatementType.TRANSACTIONS, "CoinDCX", null, List.of(), parsed.transactions(), parsed.warnings()));

        assertThat(result.transactions().get(0).tickerSymbol()).isEqualTo("BTC-INR");
        assertThat(result.transactions().get(1).tickerSymbol()).isEqualTo("BTC-INR");
        assertThat(result.transactions().get(2).tickerSymbol()).isNull();
        assertThat(result.warnings()).anyMatch(w -> w.contains("1 SELL transaction") && w.contains("Ethereum"));
        verify(marketData).search("BTC");
    }

    @Test
    void anAggregatorHoldingsCsvMixingStocksFundsAndCoinsKeepsEachRowsKind() {
        ObjectNode input = json.createObjectNode();
        input.put("statementType", "HOLDINGS").put("broker", "Groww");
        var rows = input.putArray("holdings");
        rows.addObject().put("instrumentName", "Infosys Ltd").put("isin", "INE009A01021").put("kind", "STOCK").put("quantity", 3);
        rows.addObject().put("instrumentName", "Gold BeES").put("isin", "INF204KB17I5").put("kind", "ETF").put("quantity", 40);
        rows.addObject().put("instrumentName", "Alpha Fund").put("kind", "MUTUAL_FUND").put("quantity", 10).put("investedValue", 1000);
        rows.addObject().put("instrumentName", "Bitcoin").put("symbol", "BTC").put("kind", "CRYPTO").put("quantity", 0.03);
        rows.addObject().put("instrumentName", "Digital Gold").put("kind", "COMMODITY").put("quantity", 2.5);

        StatementExtractionResponse parsed = parse(input);

        assertThat(parsed.holdings()).extracting(ExtractedHolding::kind).containsExactly(
                InstrumentKind.STOCK, InstrumentKind.ETF, InstrumentKind.MUTUAL_FUND, InstrumentKind.CRYPTO, InstrumentKind.COMMODITY);
        assertThat(parsed.warnings()).isEmpty();
    }
}
