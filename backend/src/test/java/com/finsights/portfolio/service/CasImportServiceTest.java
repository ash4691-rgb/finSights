package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.CasExtractionResponse;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
    private final ObjectMapper json = new ObjectMapper();
    private CasImportService service;

    @BeforeEach
    void setUp() {
        service = new CasImportService(rateLimiter, currentUser);
    }

    @Test
    void rejectsAnOversizedUploadWithoutCallingTheModelOrConsumingTheDailyLimit() {
        byte[] tooBig = new byte[CasImportService.MAX_PDF_BYTES + 1];
        assertThatThrownBy(() -> service.extract(tooBig))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("larger than");
        // Rejected before it ever reaches the user/rate-limit lookup — an oversized file is not
        // this user's quota to spend.
        org.mockito.Mockito.verifyNoInteractions(rateLimiter, currentUser);
    }

    @Test
    void rejectsAnEmptyUpload() {
        assertThatThrownBy(() -> service.extract(new byte[0]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No file");
    }

    @Test
    void stopsAtTheDailyRateLimitBeforeCallingTheModel() {
        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
        when(rateLimiter.tryConsume(any())).thenReturn(false);

        assertThatThrownBy(() -> service.extract(new byte[] { 1, 2, 3 }))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("limit");
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
            exchange.getResponseBody().write(body);
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
            exchange.getResponseBody().write(body);
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
