package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.ExtractedHolding;
import com.finsights.portfolio.dto.ExtractedTransaction;
import com.finsights.portfolio.dto.StatementExtractionResponse;
import com.finsights.portfolio.dto.StatementExtractionResponse.StatementType;
import com.finsights.portfolio.dto.SymbolSuggestion;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class StatementImportServiceTest {

    @Mock StatementImportRateLimiter rateLimiter;
    @Mock CurrentUserService currentUser;
    @Mock MarketDataService marketData;
    @Mock com.finsights.portfolio.repository.HoldingRepository holdings;
    private final ObjectMapper json = new ObjectMapper();
    private StatementImportService service;

    @BeforeEach
    void setUp() {
        service = new StatementImportService(rateLimiter, currentUser, marketData, holdings);
    }

    @Test
    void rejectsAnOversizedPdfUploadWithoutCallingTheModelOrConsumingTheDailyLimit() {
        byte[] tooBig = new byte[StatementImportService.MAX_PDF_BYTES + 1];
        System.arraycopy("%PDF-1.4".getBytes(StandardCharsets.US_ASCII), 0, tooBig, 0, 8);
        assertThatThrownBy(() -> service.extract(tooBig, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("larger than");
        // Rejected before it ever reaches the user/rate-limit lookup — an oversized file is not
        // this user's quota to spend.
        org.mockito.Mockito.verifyNoInteractions(rateLimiter, currentUser);
    }

    @Test
    void rejectsAnOversizedTextUploadWithTheTextLimitNotThePdfOne() {
        byte[] tooBig = new byte[StatementImportService.MAX_TEXT_BYTES + 1];
        java.util.Arrays.fill(tooBig, (byte) 'a');
        assertThatThrownBy(() -> service.extract(tooBig, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("larger than");
    }

    @Test
    void rejectsAnEmptyUpload() {
        assertThatThrownBy(() -> service.extract(new byte[0], null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No file");
    }

    @Test
    void stopsAtTheDailyRateLimitBeforeCallingTheModel() throws Exception {
        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
        when(rateLimiter.tryConsume(any())).thenReturn(false);

        // Must be a PDF that actually decrypts cleanly, so the rate-limit check — not a decrypt
        // failure — is what this test is actually exercising.
        assertThatThrownBy(() -> service.extract(unencryptedPdf(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void passesThroughAPdfThatNeedsNoPassword() throws Exception {
        byte[] decrypted = StatementImportService.decryptIfNeeded(unencryptedPdf(), null);
        assertThat(Loader.loadPDF(decrypted).isEncrypted()).isFalse();
    }

    @Test
    void asksForAPasswordWhenTheStatementIsLockedAndNoneWasGiven() throws Exception {
        byte[] locked = encryptedPdf("ABCDE1234F");
        assertThatThrownBy(() -> StatementImportService.decryptIfNeeded(locked, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("password-protected");
        assertThatThrownBy(() -> StatementImportService.decryptIfNeeded(locked, ""))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("password-protected");
    }

    @Test
    void rejectsTheWrongPasswordWithADistinctMessage() throws Exception {
        byte[] locked = encryptedPdf("ABCDE1234F");
        assertThatThrownBy(() -> StatementImportService.decryptIfNeeded(locked, "WRONGPAN1X"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("didn't work");
    }

    @Test
    void decryptsWithTheCorrectPassword() throws Exception {
        byte[] locked = encryptedPdf("ABCDE1234F");
        byte[] decrypted = StatementImportService.decryptIfNeeded(locked, "ABCDE1234F");
        assertThat(Loader.loadPDF(decrypted).isEncrypted()).isFalse();
    }

    @Test
    void rejectsSomethingThatClaimsToBeAPdfButIsNot() {
        assertThatThrownBy(() -> StatementImportService.decryptIfNeeded(new byte[] { '%', 'P', 'D', 'F', 1, 2, 3 }, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Couldn't open");
    }

    private static byte[] unencryptedPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] encryptedPdf(String password) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy(password, password, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void namesLikelyMatchToleratesSuffixAndOrderingDifferences() {
        assertThat(StatementImportService.namesLikelyMatch("Reliance Industries Limited", "Reliance Industries Ltd")).isTrue();
        assertThat(StatementImportService.namesLikelyMatch("TATA CONSULTANCY SERVICES LTD", "Tata Consultancy Services Limited")).isTrue();
        assertThat(StatementImportService.namesLikelyMatch("HDFC Bank Limited", "HDFC Bank Ltd.")).isTrue();
    }

    @Test
    void namesLikelyMatchRejectsUnrelatedOrBarelyOverlappingNames() {
        assertThat(StatementImportService.namesLikelyMatch("Reliance Industries Limited", "Reliance Power Limited")).isFalse();
        assertThat(StatementImportService.namesLikelyMatch("Infosys Limited", "Wipro Limited")).isFalse();
        assertThat(StatementImportService.namesLikelyMatch("", "Reliance Industries")).isFalse();
        assertThat(StatementImportService.namesLikelyMatch(null, "Reliance Industries")).isFalse();
    }

    @Test
    void resolveTickerPicksTheFirstIndianExchangeHitWithAMatchingName() {
        when(marketData.search("Reliance Industries")).thenReturn(List.of(
                new SymbolSuggestion("RELI.L", "Reliance Industries Limited", "LSE", "Equity"),
                new SymbolSuggestion("RELIANCE.NS", "Reliance Industries Limited", "NSE", "Equity"),
                new SymbolSuggestion("RELIANCE.BO", "Reliance Industries Limited", "BSE", "Equity")));

        assertThat(service.resolveTicker("Reliance Industries")).isEqualTo("RELIANCE.NS");
    }

    @Test
    void resolveTickerReturnsNullWhenSearchFailsOrFindsNothingIndian() {
        when(marketData.search("Foreign Only Co")).thenReturn(List.of(
                new SymbolSuggestion("FOCO", "Foreign Only Co", "NASDAQ", "Equity")));
        assertThat(service.resolveTicker("Foreign Only Co")).isNull();

        when(marketData.search("Boom")).thenThrow(new RuntimeException("feed unavailable"));
        assertThat(service.resolveTicker("Boom")).isNull();
    }

    @Test
    void matchAgainstExistingTagsAHoldingRowWhoseIsinIsAlreadyInAPreviousStatementImport() {
        Holding existing = new Holding();
        existing.setName("Reliance Industries");
        existing.setBroker("Zerodha");
        existing.setDescription("ISIN INE002A01018");
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", "h-existing-1");
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(existing));

        ExtractedHolding row = new ExtractedHolding("Reliance Industries", "INE002A01018",
                new BigDecimal("10"), new BigDecimal("2450.50"), "NSDL", "RELIANCE.NS", null);
        StatementExtractionResponse result = service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.HOLDINGS, List.of(row), List.of(), List.of(), null, List.of()), "u1");

        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().get(0).matchedHoldingId()).isEqualTo("h-existing-1");
        assertThat(result.toRemove()).isEmpty();
    }

    @Test
    void matchAgainstExistingLeavesANewIsinUnmatched() {
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of());

        ExtractedHolding row = new ExtractedHolding("Infosys", "INE009A01021",
                BigDecimal.TEN, null, "NSDL", "INFY.NS", null);
        StatementExtractionResponse result = service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.HOLDINGS, List.of(row), List.of(), List.of(), null, List.of()), "u1");

        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().get(0).matchedHoldingId()).isNull();
        assertThat(result.toRemove()).isEmpty();
    }

    @Test
    void matchAgainstExistingFlagsATaggedHoldingNotSeenInAHoldingsStatementForRemoval() {
        Holding stale = new Holding();
        stale.setName("Old Fund House Units");
        stale.setBroker("Groww");
        stale.setDescription("ISIN INE999Z99999");
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(stale));

        StatementExtractionResponse result = service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.HOLDINGS, List.of(), List.of(), List.of(), null, List.of()), "u1");

        assertThat(result.toRemove()).hasSize(1);
        assertThat(result.toRemove().get(0).name()).isEqualTo("Old Fund House Units");
        assertThat(result.toRemove().get(0).broker()).isEqualTo("Groww");
    }

    @Test
    void matchAgainstExistingNeverFlagsRemovalsFromATransactionsOnlyStatement() {
        Holding stale = new Holding();
        stale.setName("Old Fund House Units");
        stale.setBroker("Groww");
        stale.setDescription("ISIN INE999Z99999");
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(stale));

        // A transactions-only statement says nothing about what else is or isn't still held — it
        // must never generate a removal candidate just because it didn't mention this holding.
        StatementExtractionResponse result = service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.TRANSACTIONS, List.of(), List.of(), List.of(), null, List.of()), "u1");

        assertThat(result.toRemove()).isEmpty();
    }

    @Test
    void matchAgainstExistingIgnoresHoldingsWithNoIsinTagAtAll() {
        Holding manual = new Holding();
        manual.setName("Manually added FD");
        manual.setBroker("HDFC Bank");
        manual.setDescription("Matures next year");
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(manual));

        StatementExtractionResponse result = service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.HOLDINGS, List.of(), List.of(), List.of(), null, List.of()), "u1");

        // Never tagged from a statement import, so it's never a removal candidate just for being
        // absent from this statement — only holdings this feature itself created are eligible.
        assertThat(result.toRemove()).isEmpty();
    }

    @Test
    void matchAgainstExistingMatchesATransactionRowToItsExistingHoldingByIsin() {
        Holding existing = new Holding();
        existing.setName("Reliance Industries");
        existing.setBroker("Zerodha");
        existing.setDescription("ISIN INE002A01018");
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", "h-existing-1");
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(existing));

        ExtractedTransaction txn = new ExtractedTransaction("2026-09-15", TransactionType.BUY,
                "Reliance Industries", "INE002A01018", BigDecimal.TEN, new BigDecimal("24505"), null, null);
        StatementExtractionResponse result = service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.TRANSACTIONS, List.of(), List.of(), List.of(txn), null, List.of()), "u1");

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).matchedHoldingId()).isEqualTo("h-existing-1");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void matchAgainstExistingDropsAnUnmatchedTransactionIntoWarningsInsteadOfSurfacingIt() {
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of());

        ExtractedTransaction txn = new ExtractedTransaction("2026-09-15", TransactionType.BUY,
                "Infosys", "INE009A01021", BigDecimal.TEN, new BigDecimal("1500"), null, null);
        StatementExtractionResponse result = service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.TRANSACTIONS, List.of(), List.of(), List.of(txn), null, List.of()), "u1");

        assertThat(result.transactions()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("Infosys").contains("no existing holding");
    }

    @Test
    void pdfRequestBodyCarriesTheDocumentAsABase64BlockAndForcesTheExtractionTool() {
        ObjectNode body = StatementImportService.buildPdfRequestBody(json, "claude-sonnet-5", "QkFTRTY0");

        assertThat(body.path("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(body.path("tool_choice").path("name").asText()).isEqualTo("extract_statement");
        JsonNode content = body.path("messages").path(0).path("content");
        assertThat(content.path(0).path("type").asText()).isEqualTo("document");
        assertThat(content.path(0).path("source").path("data").asText()).isEqualTo("QkFTRTY0");
        assertThat(content.path(0).path("source").path("media_type").asText()).isEqualTo("application/pdf");
    }

    @Test
    void textRequestBodyInlinesTheStatementAsPlainTextRatherThanADocumentBlock() {
        ObjectNode body = StatementImportService.buildTextRequestBody(json, "claude-sonnet-5", "date,type\n2026-01-01,BUY");

        assertThat(body.path("tool_choice").path("name").asText()).isEqualTo("extract_statement");
        JsonNode content = body.path("messages").path(0).path("content");
        assertThat(content).hasSize(1);
        assertThat(content.path(0).path("type").asText()).isEqualTo("text");
        assertThat(content.path(0).path("text").asText()).contains("date,type\n2026-01-01,BUY");
    }

    @Test
    void parsesAWellFormedHoldingsToolResponse() throws Exception {
        JsonNode response = json.readTree("""
                {
                  "content": [
                    {
                      "type": "tool_use",
                      "name": "extract_statement",
                      "input": {
                        "statementType": "HOLDINGS",
                        "statementDate": "2026-09-30",
                        "holdings": [
                          {"instrumentName": "Reliance Industries", "isin": "ine002a01018", "quantity": 10, "averageCostPerUnit": 2450.50, "depository": "NSDL"},
                          {"instrumentName": "No Quantity Co", "isin": "INE999Z99999"}
                        ],
                        "warnings": ["Skipped a mutual fund folio — out of scope for this pilot"]
                      }
                    }
                  ]
                }
                """);

        StatementExtractionResponse result = StatementImportService.parseExtraction(json, response);

        assertThat(result.statementType()).isEqualTo(StatementType.HOLDINGS);
        assertThat(result.statementDate()).isEqualTo("2026-09-30");
        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().get(0).instrumentName()).isEqualTo("Reliance Industries");
        // ISIN is normalized to uppercase regardless of how the model cased it.
        assertThat(result.holdings().get(0).isin()).isEqualTo("INE002A01018");
        assertThat(result.holdings().get(0).averageCostPerUnit()).isEqualByComparingTo(new BigDecimal("2450.50"));
        // The row with no quantity is dropped, not guessed, and explained in warnings alongside
        // whatever the model itself reported.
        assertThat(result.warnings()).hasSize(2);
        assertThat(result.warnings()).anyMatch(w -> w.contains("mutual fund"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("No Quantity Co"));
    }

    @Test
    void parsesAWellFormedTransactionsToolResponse() throws Exception {
        JsonNode response = json.readTree("""
                {
                  "content": [
                    {
                      "type": "tool_use",
                      "name": "extract_statement",
                      "input": {
                        "statementType": "TRANSACTIONS",
                        "transactions": [
                          {"date": "2026-09-15", "type": "BUY", "instrumentName": "Reliance Industries", "isin": "ine002a01018", "quantity": 10, "amount": 24505},
                          {"date": "2026-09-16", "type": "NOT_A_REAL_TYPE", "instrumentName": "Bad Row", "isin": "INE999Z99999"}
                        ],
                        "warnings": []
                      }
                    }
                  ]
                }
                """);

        StatementExtractionResponse result = StatementImportService.parseExtraction(json, response);

        assertThat(result.statementType()).isEqualTo(StatementType.TRANSACTIONS);
        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).type()).isEqualTo(TransactionType.BUY);
        assertThat(result.transactions().get(0).isin()).isEqualTo("INE002A01018");
        assertThat(result.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("24505"));
        // An unparseable transaction type is dropped with an explanation, not guessed.
        assertThat(result.warnings()).anyMatch(w -> w.contains("Bad Row"));
    }

    @Test
    void missingToolUseBlockReturnsNothingWithAnExplanationInsteadOfThrowing() throws Exception {
        JsonNode response = json.readTree("{\"content\": [{\"type\": \"text\", \"text\": \"I couldn't read this.\"}]}");

        StatementExtractionResponse result = StatementImportService.parseExtraction(json, response);

        assertThat(result.holdings()).isEmpty();
        assertThat(result.transactions()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void unrecognizedStatementTypeFallsBackToHoldingsRatherThanThrowing() throws Exception {
        JsonNode response = json.readTree("""
                {"content": [{"type": "tool_use", "name": "extract_statement", "input": {"statementType": "SOMETHING_ELSE"}}]}
                """);

        StatementExtractionResponse result = StatementImportService.parseExtraction(json, response);

        assertThat(result.statementType()).isEqualTo(StatementType.HOLDINGS);
    }

    // --- callClaude: the actual HTTP exchange, against a real local server rather than a mock,
    // so the success/failure/transport-exception handling is exercised, not just assumed. ---

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void callClaudeParsesA200ResponseBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"content\": []}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();

        JsonNode result = StatementImportService.callClaude(HttpClient.newHttpClient(),
                URI.create("http://localhost:" + server.getAddress().getPort() + "/"), "key", json, json.createObjectNode());

        assertThat(result.path("content").isArray()).isTrue();
    }

    @Test
    void callClaudeTurnsANon200ResponseIntoABadGateway() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"error\": \"nope\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> StatementImportService.callClaude(HttpClient.newHttpClient(),
                URI.create("http://localhost:" + server.getAddress().getPort() + "/"), "key", json, json.createObjectNode()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Couldn't read that statement");
    }

    @Test
    void callClaudeTurnsATransportFailureIntoABadGateway() {
        // Nothing listening on this port — connection refused, exercising the catch-all branch.
        assertThatThrownBy(() -> StatementImportService.callClaude(HttpClient.newHttpClient(),
                URI.create("http://localhost:1/"), "key", json, json.createObjectNode()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Couldn't read that statement");
    }
}
