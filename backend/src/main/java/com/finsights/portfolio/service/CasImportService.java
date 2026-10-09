package com.finsights.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finsights.portfolio.dto.CasExtractionResponse;
import com.finsights.portfolio.dto.ExtractedHolding;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns an uploaded CDSL/NSDL Consolidated Account Statement (CAS) PDF into a list of candidate
 * equity/ETF holdings, via Claude's native PDF support (no local text-extraction library needed —
 * the file is sent to the Messages API as a document content block, same HTTP-calling style as
 * {@link GokuChatService}).
 *
 * <p><b>Nothing here writes to the database.</b> This is deliberate, not a missing feature: the
 * whole point of the product being "manual-first" is that an auto-populated holding is never
 * trusted more than one the user typed in themselves. The frontend takes each {@link
 * ExtractedHolding} and pre-fills the ordinary Add Holding form with it — the user reviews, edits
 * if needed, and saves (or discards) one at a time through {@code POST /api/holdings}, the exact
 * same path and validation as manual entry. This service's only job is the extraction step.
 *
 * <p><b>PII posture:</b> the source PDF is held in memory for the one synchronous call and never
 * persisted or logged — only the structured fields below ever leave this method. The extraction
 * schema itself has no field for PAN, account/folio/demat numbers, address, or any other personal
 * detail, and the system prompt tells the model explicitly never to return one even if it's
 * visible in the document — the model can only hand back what the schema has room for.
 */
@Service
public class CasImportService {
    private static final Logger log = LoggerFactory.getLogger(CasImportService.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final URI MESSAGES_URI = URI.create("https://api.anthropic.com/v1/messages");
    private static final int MAX_TOKENS = 4096;
    /** CAS statements run a handful of pages — this is a generous ceiling against an oversized or
     *  mistaken upload, not a real-world limit. Rejected before an API call is ever made. */
    static final int MAX_PDF_BYTES = 15 * 1024 * 1024;

    private static final String TOOL_NAME = "extract_holdings";

    // Scoped to equity/ETF lines for this pilot — CAS also lists mutual funds, bonds, and
    // insurance, which don't fit the same per-unit-quantity shape and are left for a later pass.
    // Out-of-scope rows and anything the model isn't confident about land in `warnings`, not
    // silently dropped, so a short result has an explanation.
    private static final String SYSTEM_PROMPT = """
            You read Indian CDSL/NSDL Consolidated Account Statements (CAS) and extract equity and \
            ETF holdings from them using the extract_holdings tool. Follow these rules exactly:

            1. Only report lines that are listed equities or ETFs with a quantity held. Skip mutual \
            fund folios, bonds, insurance, and anything else — instead add one line to `warnings` \
            naming what you skipped and why, so nothing is silently lost.
            2. Only ever return the fields the extract_holdings tool defines. Never include PAN, \
            bank account numbers, demat/folio account numbers, address, phone number, email, or any \
            other personal information, even if it appears in the document — the schema has no field \
            for it on purpose.
            3. If you cannot confidently determine a row's ISIN or quantity, leave that holding out of \
            the result entirely and explain why in `warnings` instead of guessing.
            4. Report the average cost per unit only if the statement states one; omit the field \
            rather than estimating it.
            """;

    @Value("${app.cas-import.anthropic-api-key:}") private String apiKey;
    @Value("${app.cas-import.model:claude-sonnet-5}") private String model;

    private final CasImportRateLimiter rateLimiter;
    private final CurrentUserService currentUser;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CasImportService(CasImportRateLimiter rateLimiter, CurrentUserService currentUser) {
        this.rateLimiter = rateLimiter;
        this.currentUser = currentUser;
    }

    public CasExtractionResponse extract(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was uploaded");
        }
        if (pdf.length > MAX_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That file is larger than " + (MAX_PDF_BYTES / (1024 * 1024)) + "MB — is it the right statement?");
        }
        String userId = currentUser.currentUser().getId();
        if (!rateLimiter.tryConsume(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've hit today's statement-upload limit — try again tomorrow.");
        }

        ObjectNode body = buildRequestBody(json, model, Base64.getEncoder().encodeToString(pdf));
        JsonNode response = callClaude(body);
        return parseExtraction(json, response);
    }

    /** Package-private and pure (no network) so it can be unit-tested directly. */
    static ObjectNode buildRequestBody(ObjectMapper json, String model, String pdfBase64) {
        ObjectNode document = json.createObjectNode();
        document.put("type", "document");
        ObjectNode source = json.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "application/pdf");
        source.put("data", pdfBase64);
        document.set("source", source);

        ObjectNode instruction = json.createObjectNode();
        instruction.put("type", "text");
        instruction.put("text", "Extract the equity/ETF holdings from this statement using the extract_holdings tool.");

        ArrayNode content = json.createArrayNode();
        content.add(document);
        content.add(instruction);

        ObjectNode userMessage = json.createObjectNode();
        userMessage.put("role", "user");
        userMessage.set("content", content);

        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", SYSTEM_PROMPT);
        body.set("messages", json.createArrayNode().add(userMessage));
        body.set("tools", json.createArrayNode().add(toolDefinition(json)));
        ObjectNode toolChoice = json.createObjectNode();
        toolChoice.put("type", "tool");
        toolChoice.put("name", TOOL_NAME);
        body.set("tool_choice", toolChoice);
        return body;
    }

    private static ObjectNode toolDefinition(ObjectMapper json) {
        ObjectNode holdingSchema = json.createObjectNode();
        holdingSchema.put("type", "object");
        ObjectNode holdingProps = json.createObjectNode();
        holdingProps.set("instrumentName", textProp(json, "Company or instrument name exactly as printed"));
        holdingProps.set("isin", textProp(json, "The 12-character ISIN for this instrument"));
        holdingProps.set("quantity", numberProp(json, "Units held"));
        holdingProps.set("averageCostPerUnit", numberProp(json, "Average cost per unit — omit entirely if the statement doesn't show one"));
        ObjectNode depository = textProp(json, "Which depository this line is held under, if shown");
        depository.set("enum", json.createArrayNode().add("NSDL").add("CDSL").add("UNKNOWN"));
        holdingProps.set("depository", depository);
        holdingSchema.set("properties", holdingProps);
        holdingSchema.set("required", json.createArrayNode().add("instrumentName").add("isin").add("quantity"));

        ObjectNode holdingsArray = json.createObjectNode();
        holdingsArray.put("type", "array");
        holdingsArray.set("items", holdingSchema);

        ObjectNode warningsArray = json.createObjectNode();
        warningsArray.put("type", "array");
        warningsArray.set("items", textProp(json, null));
        warningsArray.put("description", "Anything skipped, unclear, or out of scope — see the rules above.");

        ObjectNode topProps = json.createObjectNode();
        topProps.set("statementDate", textProp(json, "As-of date of the statement, YYYY-MM-DD, if determinable"));
        topProps.set("holdings", holdingsArray);
        topProps.set("warnings", warningsArray);

        ObjectNode inputSchema = json.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", topProps);
        inputSchema.set("required", json.createArrayNode().add("holdings"));

        ObjectNode tool = json.createObjectNode();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Report the equity/ETF holdings found in this CAS statement.");
        tool.set("input_schema", inputSchema);
        return tool;
    }

    private static ObjectNode textProp(ObjectMapper json, String description) {
        ObjectNode node = json.createObjectNode();
        node.put("type", "string");
        if (description != null) node.put("description", description);
        return node;
    }

    private static ObjectNode numberProp(ObjectMapper json, String description) {
        ObjectNode node = json.createObjectNode();
        node.put("type", "number");
        if (description != null) node.put("description", description);
        return node;
    }

    /** Package-private and pure so it can be unit-tested against a canned API response. Fault
     *  tolerant per row, same philosophy as TransactionImportRunner: a malformed row is dropped
     *  into {@code warnings} rather than failing the whole statement. */
    static CasExtractionResponse parseExtraction(ObjectMapper json, JsonNode apiResponse) {
        JsonNode toolInput = null;
        for (JsonNode block : apiResponse.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && TOOL_NAME.equals(block.path("name").asText())) {
                toolInput = block.path("input");
                break;
            }
        }
        if (toolInput == null || toolInput.isMissingNode()) {
            return new CasExtractionResponse(List.of(), null,
                    List.of("Couldn't read any holdings from this statement — try a different file."));
        }

        List<ExtractedHolding> holdings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (JsonNode node : toolInput.path("warnings")) warnings.add(node.asText());

        for (JsonNode row : toolInput.path("holdings")) {
            String name = blankToNull(row.path("instrumentName").asText(null));
            String isin = blankToNull(row.path("isin").asText(null));
            JsonNode quantityNode = row.path("quantity");
            if (name == null || isin == null || !quantityNode.isNumber() || quantityNode.decimalValue().signum() <= 0) {
                warnings.add("Skipped a row with an incomplete name, ISIN, or quantity" + (name != null ? " (" + name + ")" : ""));
                continue;
            }
            JsonNode avgCostNode = row.path("averageCostPerUnit");
            BigDecimal avgCost = avgCostNode.isNumber() ? avgCostNode.decimalValue() : null;
            String depository = blankToNull(row.path("depository").asText(null));
            holdings.add(new ExtractedHolding(name, isin.trim().toUpperCase(), quantityNode.decimalValue(), avgCost, depository));
        }

        String statementDate = blankToNull(toolInput.path("statementDate").asText(null));
        return new CasExtractionResponse(holdings, statementDate, warnings);
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private JsonNode callClaude(ObjectNode body) {
        return callClaude(http, MESSAGES_URI, apiKey, json, body);
    }

    /** Package-private and static — takes the HTTP client and target URI as parameters so the
     *  exchange itself (success, non-200, and transport-failure handling) can be exercised in a
     *  test against a local server, not just mocked away. */
    static JsonNode callClaude(HttpClient http, URI uri, String apiKey, ObjectMapper json, ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60)) // a multi-page PDF takes longer than a chat turn
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // Deliberately not logging the response body here (unlike GokuChatService) — this
                // endpoint handles financial statements, and an error payload could echo back
                // document content we don't want sitting in application logs.
                log.warn("Anthropic API returned {} for a CAS extraction request", response.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't read that statement right now — try again shortly.");
            }
            return json.readTree(response.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to reach the Anthropic API for a CAS extraction request", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't read that statement right now — try again shortly.");
        }
    }
}
