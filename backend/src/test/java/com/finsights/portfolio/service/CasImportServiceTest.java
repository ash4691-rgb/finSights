package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.CasExtractionResponse;
import com.finsights.portfolio.dto.ExtractedHolding;
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
class CasImportServiceTest {

    @Mock CasImportRateLimiter rateLimiter;
    @Mock CurrentUserService currentUser;
    @Mock MarketDataService marketData;
    @Mock com.finsights.portfolio.repository.HoldingRepository holdings;
    private final ObjectMapper json = new ObjectMapper();
    private CasImportService service;

    @BeforeEach
    void setUp() {
        service = new CasImportService(rateLimiter, currentUser, marketData, holdings);
    }

    @Test
    void rejectsAnOversizedUploadWithoutCallingTheModelOrConsumingTheDailyLimit() {
        byte[] tooBig = new byte[CasImportService.MAX_PDF_BYTES + 1];
        assertThatThrownBy(() -> service.extract(tooBig, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("larger than");
        // Rejected before it ever reaches the user/rate-limit lookup — an oversized file is not
        // this user's quota to spend.
        org.mockito.Mockito.verifyNoInteractions(rateLimiter, currentUser);
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
        byte[] decrypted = CasImportService.decryptIfNeeded(unencryptedPdf(), null);
        assertThat(Loader.loadPDF(decrypted).isEncrypted()).isFalse();
    }

    @Test
    void asksForAPasswordWhenTheStatementIsLockedAndNoneWasGiven() throws Exception {
        byte[] locked = encryptedPdf("ABCDE1234F");
        assertThatThrownBy(() -> CasImportService.decryptIfNeeded(locked, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("password-protected");
        assertThatThrownBy(() -> CasImportService.decryptIfNeeded(locked, ""))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("password-protected");
    }

    @Test
    void rejectsTheWrongPasswordWithADistinctMessage() throws Exception {
        byte[] locked = encryptedPdf("ABCDE1234F");
        assertThatThrownBy(() -> CasImportService.decryptIfNeeded(locked, "WRONGPAN1X"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("didn't work");
    }

    @Test
    void decryptsWithTheCorrectPassword() throws Exception {
        byte[] locked = encryptedPdf("ABCDE1234F");
        byte[] decrypted = CasImportService.decryptIfNeeded(locked, "ABCDE1234F");
        assertThat(Loader.loadPDF(decrypted).isEncrypted()).isFalse();
    }

    @Test
    void rejectsSomethingThatIsNotAPdfAtAll() {
        assertThatThrownBy(() -> CasImportService.decryptIfNeeded(new byte[] { 1, 2, 3 }, null))
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
        assertThat(CasImportService.namesLikelyMatch("Reliance Industries Limited", "Reliance Industries Ltd")).isTrue();
        assertThat(CasImportService.namesLikelyMatch("TATA CONSULTANCY SERVICES LTD", "Tata Consultancy Services Limited")).isTrue();
        assertThat(CasImportService.namesLikelyMatch("HDFC Bank Limited", "HDFC Bank Ltd.")).isTrue();
    }

    @Test
    void namesLikelyMatchRejectsUnrelatedOrBarelyOverlappingNames() {
        assertThat(CasImportService.namesLikelyMatch("Reliance Industries Limited", "Reliance Power Limited")).isFalse();
        assertThat(CasImportService.namesLikelyMatch("Infosys Limited", "Wipro Limited")).isFalse();
        assertThat(CasImportService.namesLikelyMatch("", "Reliance Industries")).isFalse();
        assertThat(CasImportService.namesLikelyMatch(null, "Reliance Industries")).isFalse();
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
    void resolveTickerSkipsAnIndianHitWhoseNameDoesNotActuallyMatch() {
        when(marketData.search("Totally Different Co")).thenReturn(List.of(
                new SymbolSuggestion("RELIANCE.NS", "Reliance Industries Limited", "NSE", "Equity")));

        assertThat(service.resolveTicker("Totally Different Co")).isNull();
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
    void matchAgainstExistingTagsARowWhoseIsinIsAlreadyInAPreviousCasImport() {
        Holding existing = new Holding();
        existing.setName("Reliance Industries");
        existing.setBroker("Zerodha");
        existing.setDescription("ISIN INE002A01018");
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", "h-existing-1");
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(existing));

        ExtractedHolding row = new ExtractedHolding("Reliance Industries", "INE002A01018",
                new BigDecimal("10"), new BigDecimal("2450.50"), "NSDL", "RELIANCE.NS", null);
        CasExtractionResponse result = service.matchAgainstExisting(
                new CasExtractionResponse(List.of(row), List.of(), null, List.of()), "u1");

        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().get(0).matchedHoldingId()).isEqualTo("h-existing-1");
        assertThat(result.toRemove()).isEmpty();
    }

    @Test
    void matchAgainstExistingLeavesANewIsinUnmatched() {
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of());

        ExtractedHolding row = new ExtractedHolding("Infosys", "INE009A01021",
                BigDecimal.TEN, null, "NSDL", "INFY.NS", null);
        CasExtractionResponse result = service.matchAgainstExisting(
                new CasExtractionResponse(List.of(row), List.of(), null, List.of()), "u1");

        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().get(0).matchedHoldingId()).isNull();
        assertThat(result.toRemove()).isEmpty();
    }

    @Test
    void matchAgainstExistingFlagsATaggedHoldingNotSeenInThisStatementForRemoval() {
        Holding stale = new Holding();
        stale.setName("Old Fund House Units");
        stale.setBroker("Groww");
        stale.setDescription("ISIN INE999Z99999");
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(stale));

        CasExtractionResponse result = service.matchAgainstExisting(
                new CasExtractionResponse(List.of(), List.of(), null, List.of()), "u1");

        assertThat(result.toRemove()).hasSize(1);
        assertThat(result.toRemove().get(0).name()).isEqualTo("Old Fund House Units");
        assertThat(result.toRemove().get(0).broker()).isEqualTo("Groww");
    }

    @Test
    void matchAgainstExistingIgnoresHoldingsWithNoIsinTagAtAll() {
        Holding manual = new Holding();
        manual.setName("Manually added FD");
        manual.setBroker("HDFC Bank");
        manual.setDescription("Matures next year");
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(manual));

        CasExtractionResponse result = service.matchAgainstExisting(
                new CasExtractionResponse(List.of(), List.of(), null, List.of()), "u1");

        // Never tagged as a CAS import, so it's never a removal candidate just for being absent
        // from this statement — only holdings this feature itself created are eligible for that.
        assertThat(result.toRemove()).isEmpty();
    }

    @Test
    void requestBodyCarriesThePdfAsABase64DocumentBlockAndForcesTheExtractionTool() {
        ObjectNode body = CasImportService.buildRequestBody(json, "claude-sonnet-5", "QkFTRTY0");

        assertThat(body.path("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(body.path("tool_choice").path("name").asText()).isEqualTo("extract_holdings");
        JsonNode content = body.path("messages").path(0).path("content");
        assertThat(content.path(0).path("type").asText()).isEqualTo("document");
        assertThat(content.path(0).path("source").path("data").asText()).isEqualTo("QkFTRTY0");
        assertThat(content.path(0).path("source").path("media_type").asText()).isEqualTo("application/pdf");
    }

    @Test
    void parsesAWellFormedToolResponseIntoExtractedHoldings() throws Exception {
        JsonNode response = json.readTree("""
                {
                  "content": [
                    {
                      "type": "tool_use",
                      "name": "extract_holdings",
                      "input": {
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

        CasExtractionResponse result = CasImportService.parseExtraction(json, response);

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
    void missingToolUseBlockReturnsNoHoldingsWithAnExplanationInsteadOfThrowing() throws Exception {
        JsonNode response = json.readTree("{\"content\": [{\"type\": \"text\", \"text\": \"I couldn't read this.\"}]}");

        CasExtractionResponse result = CasImportService.parseExtraction(json, response);

        assertThat(result.holdings()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
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

        JsonNode result = CasImportService.callClaude(HttpClient.newHttpClient(),
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

        assertThatThrownBy(() -> CasImportService.callClaude(HttpClient.newHttpClient(),
                URI.create("http://localhost:" + server.getAddress().getPort() + "/"), "key", json, json.createObjectNode()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Couldn't read that statement");
    }

    @Test
    void callClaudeTurnsATransportFailureIntoABadGateway() {
        // Nothing listening on this port — connection refused, exercising the catch-all branch.
        assertThatThrownBy(() -> CasImportService.callClaude(HttpClient.newHttpClient(),
                URI.create("http://localhost:1/"), "key", json, json.createObjectNode()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Couldn't read that statement");
    }
}
