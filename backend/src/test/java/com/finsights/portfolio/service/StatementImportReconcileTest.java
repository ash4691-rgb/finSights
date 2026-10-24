package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.ExtractedHolding;
import com.finsights.portfolio.dto.InstrumentKind;
import com.finsights.portfolio.dto.StatementExtractionResponse;
import com.finsights.portfolio.dto.StatementExtractionResponse.StatementType;
import com.finsights.portfolio.dto.StatementReconcileRequest;
import com.finsights.portfolio.dto.SymbolSuggestion;
import com.finsights.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** Mutual-fund rows, broker-scoped matching, and the confirm-then-reconcile step of statement import. */
@ExtendWith(MockitoExtension.class)
class StatementImportReconcileTest {
    @Mock StatementImportRateLimiter rateLimiter;
    @Mock CurrentUserService currentUser;
    @Mock MarketDataService marketData;
    @Mock HoldingRepository holdings;
    private final ObjectMapper json = new ObjectMapper();
    private StatementImportService service;

    @BeforeEach
    void setUp() { service = new StatementImportService(rateLimiter, currentUser, marketData, holdings); }

    private static Holding held(String id, String name, String broker, String units) {
        Holding h = new Holding();
        ReflectionTestUtils.setField(h, "id", id);
        h.setName(name);
        h.setBroker(broker);
        h.setQuantity(new BigDecimal(units));
        return h;
    }

    private static ExtractedHolding fundRow(String name) {
        return new ExtractedHolding(name, null, BigDecimal.TEN, null, null, null, null,
                InstrumentKind.MUTUAL_FUND, new BigDecimal("1000"), new BigDecimal("1200"));
    }

    private StatementExtractionResponse holdingsStatement(List<ExtractedHolding> rows) {
        return new StatementExtractionResponse(StatementType.HOLDINGS, rows, List.of(), List.of(), null, List.of(), "Groww");
    }

    // ---- parsing ----

    private StatementExtractionResponse parse(ObjectNode toolInput) {
        ObjectNode response = json.createObjectNode();
        response.putArray("content").addObject().put("type", "tool_use").put("name", "extract_statement").set("input", toolInput);
        return StatementImportService.parseExtraction(response);
    }

    @Test
    void aMutualFundRowNeedsNoIsinAndGetsItsAverageCostFromTheInvestedTotal() {
        ObjectNode input = json.createObjectNode();
        input.put("statementType", "HOLDINGS").put("broker", "Groww");
        var rows = input.putArray("holdings");
        rows.addObject().put("instrumentName", "Alpha Small Cap Fund").put("kind", "MUTUAL_FUND")
                .put("quantity", 40).put("investedValue", 6000).put("currentValue", 7500);

        StatementExtractionResponse parsed = parse(input);

        assertThat(parsed.broker()).isEqualTo("Groww");
        assertThat(parsed.holdings()).hasSize(1);
        ExtractedHolding fund = parsed.holdings().get(0);
        assertThat(fund.isin()).isNull();
        assertThat(fund.kind()).isEqualTo(InstrumentKind.MUTUAL_FUND);
        assertThat(fund.averageCostPerUnit()).isEqualByComparingTo("150");
        assertThat(fund.currentValue()).isEqualByComparingTo("7500");
    }

    @Test
    void foliosOfOneSchemeInOneReadBecomeOneHolding() {
        ObjectNode input = json.createObjectNode();
        input.put("statementType", "HOLDINGS");
        var rows = input.putArray("holdings");
        rows.addObject().put("instrumentName", "Alpha Fund").put("kind", "MUTUAL_FUND").put("quantity", 10).put("investedValue", 1000);
        rows.addObject().put("instrumentName", "Alpha Fund").put("kind", "MUTUAL_FUND").put("quantity", 5).put("investedValue", 600);

        StatementExtractionResponse parsed = parse(input);

        assertThat(parsed.holdings()).hasSize(1);
        assertThat(parsed.holdings().get(0).quantity()).isEqualByComparingTo("15");
        assertThat(parsed.holdings().get(0).averageCostPerUnit()).isEqualByComparingTo("106.6667");
        assertThat(parsed.broker()).isNull();
    }

    @Test
    void aStatedAverageCostWinsOverOneDerivedFromTheInvestedTotal() {
        ObjectNode input = json.createObjectNode();
        input.put("statementType", "HOLDINGS");
        input.putArray("holdings").addObject().put("instrumentName", "Alpha Fund").put("kind", "MUTUAL_FUND")
                .put("quantity", 10).put("investedValue", 1000).put("averageCostPerUnit", 99);

        assertThat(parse(input).holdings().get(0).averageCostPerUnit()).isEqualByComparingTo("99");
    }

    @Test
    void aStockStillNeedsAnIsinAndAKindlessRowWithOneIsTreatedAsAStock() {
        ObjectNode input = json.createObjectNode();
        input.put("statementType", "HOLDINGS");
        var rows = input.putArray("holdings");
        rows.addObject().put("instrumentName", "Infosys").put("kind", "STOCK").put("quantity", 3);
        rows.addObject().put("instrumentName", "Tata Steel").put("isin", "ine081a01020").put("quantity", 4);
        rows.addObject().put("instrumentName", "Mystery").put("kind", "NOT_A_KIND").put("quantity", 4);

        StatementExtractionResponse parsed = parse(input);

        assertThat(parsed.holdings()).hasSize(1);
        assertThat(parsed.holdings().get(0).kind()).isEqualTo(InstrumentKind.STOCK);
        assertThat(parsed.holdings().get(0).isin()).isEqualTo("INE081A01020");
        assertThat(parsed.warnings()).hasSize(2);
    }

    @Test
    void mergingChunksSumsAFundSplitAcrossThemAndKeepsTheFirstBroker() {
        StatementExtractionResponse one = new StatementExtractionResponse(StatementType.HOLDINGS,
                List.of(fundRow("Alpha Fund")), List.of(), List.of(), null, List.of(), "Groww");
        StatementExtractionResponse two = new StatementExtractionResponse(StatementType.HOLDINGS,
                List.of(fundRow("Alpha Fund")), List.of(), List.of(), null, List.of(), "Other");

        StatementExtractionResponse merged = StatementImportService.mergeExtractions(List.of(one, two));

        assertThat(merged.holdings()).hasSize(1);
        assertThat(merged.holdings().get(0).quantity()).isEqualByComparingTo("20");
        assertThat(merged.broker()).isEqualTo("Groww");
    }

    @Test
    void theToolSchemaAsksForKindAmountsAndBrokerAndNoLongerRequiresAnIsin() {
        ObjectNode body = StatementImportService.buildTextRequestBody(json, "m", "x");
        var schema = body.path("tools").path(0).path("input_schema");
        var holdingSchema = schema.path("properties").path("holdings").path("items");

        assertThat(holdingSchema.path("properties").has("kind")).isTrue();
        assertThat(holdingSchema.path("properties").has("investedValue")).isTrue();
        assertThat(holdingSchema.path("properties").has("currentValue")).isTrue();
        assertThat(schema.path("properties").has("broker")).isTrue();
        assertThat(holdingSchema.path("required").toString()).doesNotContain("isin");
        assertThat(body.path("system").asText()).contains("MUTUAL_FUND").contains("never guess one");
    }

    // ---- ticker resolution ----

    @Test
    void aFundGetsItsAmfiSchemeCodeOnlyWhenExactlyOneSchemeMatchesItsName() {
        when(marketData.search("Alpha Small Cap Fund Direct Growth")).thenReturn(List.of(
                new SymbolSuggestion("ALPHA.NS", "Alpha Small Cap Fund", "NSE", "Equity"),
                new SymbolSuggestion("MF:100", "Alpha Small Cap Fund - Direct Plan - Growth", "AMFI", "Mutual Fund"),
                new SymbolSuggestion("MF:101", "Unrelated Bond Fund", "AMFI", "Mutual Fund")));

        assertThat(service.resolveTicker("Alpha Small Cap Fund Direct Growth", InstrumentKind.MUTUAL_FUND)).isEqualTo("MF:100");
    }

    @Test
    void aFundWithSeveralMatchingSchemesOrNoneIsLeftForTheUserToPick() {
        when(marketData.search("Alpha Fund")).thenReturn(List.of(
                new SymbolSuggestion("MF:1", "Alpha Fund Direct Growth", "AMFI", "Mutual Fund"),
                new SymbolSuggestion("MF:2", "Alpha Fund Regular Growth", "AMFI", "Mutual Fund")));
        when(marketData.search("Zed Fund")).thenReturn(List.of());

        assertThat(service.resolveTicker("Alpha Fund", InstrumentKind.MUTUAL_FUND)).isNull();
        assertThat(service.resolveTicker("Zed Fund", InstrumentKind.MUTUAL_FUND)).isNull();
    }

    @Test
    void enrichingRowsGivesEachKindItsOwnKindOfTickerAndKeepsTheBroker() {
        when(marketData.search("Alpha Fund")).thenReturn(List.of(new SymbolSuggestion("MF:7", "Alpha Fund", "AMFI", "Mutual Fund")));
        when(marketData.search("Infosys Ltd")).thenReturn(List.of(new SymbolSuggestion("INFY.NS", "Infosys Ltd", "NSE", "Equity")));
        ExtractedHolding stock = new ExtractedHolding("Infosys Ltd", "INE009A01021", BigDecimal.ONE, null, null, null, null);

        StatementExtractionResponse enriched = service.enrichWithTickers(holdingsStatement(List.of(fundRow("Alpha Fund"), stock)));

        assertThat(enriched.holdings().get(0).tickerSymbol()).isEqualTo("MF:7");
        assertThat(enriched.holdings().get(1).tickerSymbol()).isEqualTo("INFY.NS");
        assertThat(enriched.broker()).isEqualTo("Groww");
    }

    // ---- reconcile ----

    @Test
    void reconcileRefusesToRunWithoutAConfirmedBroker() {
        StatementReconcileRequest none = new StatementReconcileRequest(StatementType.HOLDINGS, null, null, List.of(), List.of(), List.of());
        StatementReconcileRequest blank = new StatementReconcileRequest(StatementType.HOLDINGS, "  ", null, List.of(), List.of(), List.of());

        assertThatThrownBy(() -> service.reconcile(none)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("broker");
        assertThatThrownBy(() -> service.reconcile(blank)).isInstanceOf(ResponseStatusException.class);
    }

    private void signedInAs(String userId, List<Holding> existing) {
        UserAccount user = org.mockito.Mockito.mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(currentUser.currentUser()).thenReturn(user);
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId)).thenReturn(existing);
    }

    @Test
    void reconcileMatchesOnlyTheConfirmedBrokersHoldingsEvenWhenAnotherBrokerHoldsTheSameName() {
        Holding atGroww = held("h-groww", "Alpha Small Cap Fund", "Groww", "10");
        Holding atZerodha = held("h-kite", "Alpha Small Cap Fund", "Zerodha", "10");
        signedInAs("u1", List.of(atZerodha, atGroww));

        StatementExtractionResponse result = service.reconcile(new StatementReconcileRequest(StatementType.HOLDINGS, " groww ", "2026-10-23",
                List.of(fundRow("Alpha Small Cap Fund")), List.of(), List.of("note")));

        assertThat(result.broker()).isEqualTo("groww");
        assertThat(result.holdings().get(0).matchedHoldingId()).isEqualTo("h-groww");
        assertThat(result.toRemove()).isEmpty();
        assertThat(result.warnings()).containsExactly("note");
        assertThat(result.statementDate()).isEqualTo("2026-10-23");
    }

    @Test
    void aBrokerHoldingTheStatementNoLongerListsIsOfferedAsMissingButAnotherBrokersIsNot() {
        signedInAs("u1", List.of(held("h-gone", "Old Fund", "Groww", "7"), held("h-kite", "Kite Only Fund", "Zerodha", "3")));

        StatementExtractionResponse result = service.reconcile(new StatementReconcileRequest(null, "Groww", null,
                List.of(fundRow("Alpha Small Cap Fund")), List.of(), List.of()));

        assertThat(result.statementType()).isEqualTo(StatementType.HOLDINGS);
        assertThat(result.holdings().get(0).matchedHoldingId()).isNull();
        assertThat(result.toRemove()).hasSize(1);
        assertThat(result.toRemove().get(0).holdingId()).isEqualTo("h-gone");
        assertThat(result.toRemove().get(0).quantity()).isEqualByComparingTo("7");
    }

    @Test
    void aTransactionsStatementNeverReportsMissingHoldingsAndNeverMatchesAcrossBrokers() {
        signedInAs("u1", List.of(held("h-kite", "Alpha Small Cap Fund", "Zerodha", "3")));
        var txn = new com.finsights.portfolio.dto.ExtractedTransaction("2026-01-02",
                com.finsights.portfolio.domain.TransactionType.BUY, "Alpha Small Cap Fund", "INF000A01010",
                BigDecimal.ONE, BigDecimal.TEN, null, null);

        StatementExtractionResponse result = service.reconcile(new StatementReconcileRequest(StatementType.TRANSACTIONS, "Groww", null,
                List.of(), List.of(txn), List.of()));

        assertThat(result.toRemove()).isEmpty();
        assertThat(result.transactions().get(0).matchedHoldingId()).isNull();
        assertThat(result.warnings()).hasSize(1);
    }
}
