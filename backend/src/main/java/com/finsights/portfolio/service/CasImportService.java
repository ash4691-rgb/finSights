package com.finsights.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finsights.portfolio.dto.CasExtractionResponse;
import com.finsights.portfolio.dto.CasHoldingToRemove;
import com.finsights.portfolio.dto.ExtractedHolding;
import com.finsights.portfolio.dto.SymbolSuggestion;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.repository.HoldingRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
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
 * {@link GokuChatService}). CAS statements are almost always password-protected (typically the
 * holder's PAN), and Claude's PDF support rejects encrypted documents outright — so the upload is
 * decrypted locally with PDFBox first (see {@link #decryptIfNeeded}); that's the only thing PDFBox
 * is used for here, not parsing.
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
 *
 * <p>There's no real ISIN-to-ticker mapping yet (tracked as deferred work on #89), so each row
 * also gets a best-effort NSE/BSE ticker guess from its instrument name via {@link
 * #resolveTicker} — the same live-price search the manual Add Holding form's ticker field already
 * uses. A match pre-fills the review form as {@code MARKET_PRICE} with that ticker; no match falls
 * back to {@code MANUAL}, same as before. Either way it's still a suggestion the user reviews, not
 * an applied fact.
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
    private final MarketDataService marketData;
    private final HoldingRepository holdings;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CasImportService(CasImportRateLimiter rateLimiter, CurrentUserService currentUser,
                             MarketDataService marketData, HoldingRepository holdings) {
        this.rateLimiter = rateLimiter;
        this.currentUser = currentUser;
        this.marketData = marketData;
        this.holdings = holdings;
    }

    public CasExtractionResponse extract(byte[] pdf, String password) {
        if (pdf == null || pdf.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was uploaded");
        }
        if (pdf.length > MAX_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That file is larger than " + (MAX_PDF_BYTES / (1024 * 1024)) + "MB — is it the right statement?");
        }
        // Decrypt before touching the daily quota — a wrong/missing password costs nothing and
        // shouldn't burn a user's upload allowance for a request that was never going to reach
        // the model.
        byte[] decrypted = decryptIfNeeded(pdf, password);

        String userId = currentUser.currentUser().getId();
        if (!rateLimiter.tryConsume(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've hit today's statement-upload limit — try again tomorrow.");
        }

        ObjectNode body = buildRequestBody(json, model, Base64.getEncoder().encodeToString(decrypted));
        JsonNode response = callClaude(body);
        return matchAgainstExisting(enrichWithTickers(parseExtraction(json, response)), userId);
    }

    /** Tags each row with (12-char) ISINs, same shape {@code ExtractedHolding.isin()} is already
     *  normalized to. */
    private static final Pattern ISIN_TAG = Pattern.compile("ISIN ([A-Z0-9]{12})");

    /**
     * There's no {@code isin} column on {@code Holding} yet (see issue #89's deferred list), so
     * this reuses the one place an ISIN is already on record: the "ISIN &lt;isin&gt;" note the
     * Holdings page writes into a CAS-imported holding's description (see {@code
     * draftFromExtractedHolding} on the frontend). A row whose ISIN matches one of those tagged
     * holdings needs no review — it's already in the portfolio — so it's reported as matched
     * rather than asked for again; a tagged holding whose ISIN doesn't turn up anywhere in this
     * statement is reported as a candidate to remove. Nothing is written here — same "extraction
     * only" rule as the rest of this class; the Holdings page still asks the user before every
     * delete, same as it does for a holding deleted by hand.
     *
     * <p>The obvious limitation: if a user edits that description's free-text Notes field after
     * import, the tag is gone and this holding can no longer be matched or offered for cleanup —
     * an acceptable trade for not adding a schema column for a pilot feature.
     */
    /** Package-private (not private) purely so it can be unit-tested directly against a mocked
     *  {@link HoldingRepository}. */
    CasExtractionResponse matchAgainstExisting(CasExtractionResponse extracted, String userId) {
        Map<String, Holding> byIsin = new LinkedHashMap<>();
        for (Holding h : holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId)) {
            Matcher m = ISIN_TAG.matcher(h.getDescription() == null ? "" : h.getDescription());
            if (m.find()) byIsin.put(m.group(1), h);
        }

        List<ExtractedHolding> matched = new ArrayList<>();
        for (ExtractedHolding row : extracted.holdings()) {
            Holding existing = byIsin.remove(row.isin());
            matched.add(existing == null ? row
                    : new ExtractedHolding(row.instrumentName(), row.isin(), row.quantity(), row.averageCostPerUnit(),
                            row.depository(), row.tickerSymbol(), existing.getId()));
        }
        // Whatever's left in byIsin was tagged from a past import but never matched above — not
        // seen in this statement.
        List<CasHoldingToRemove> toRemove = byIsin.values().stream()
                .map(h -> new CasHoldingToRemove(h.getId(), h.getName(), h.getBroker()))
                .toList();
        return new CasExtractionResponse(matched, toRemove, extracted.statementDate(), extracted.warnings());
    }

    /**
     * Best-effort ISIN-to-ticker stand-in: there's no real mapping yet (see issue #89's deferred
     * list), so this guesses an NSE/BSE ticker from each row's instrument name via the same
     * live-price search {@code SymbolSearchInput} already uses on the Add Holding form — the one
     * search that's already tuned to rank Indian listings first. A row's own extraction never
     * fails because of this: {@link #resolveTicker} swallows every failure and simply leaves
     * {@code tickerSymbol} null, same as if nothing had been attempted.
     */
    private CasExtractionResponse enrichWithTickers(CasExtractionResponse extracted) {
        List<ExtractedHolding> enriched = extracted.holdings().stream()
                .map(h -> new ExtractedHolding(h.instrumentName(), h.isin(), h.quantity(), h.averageCostPerUnit(),
                        h.depository(), resolveTicker(h.instrumentName()), h.matchedHoldingId()))
                .toList();
        return new CasExtractionResponse(enriched, extracted.toRemove(), extracted.statementDate(), extracted.warnings());
    }

    /** Package-private (not private) purely so it can be unit-tested directly against a mocked
     *  {@link MarketDataService} without going through the full, network-calling {@link #extract}. */
    String resolveTicker(String instrumentName) {
        try {
            for (SymbolSuggestion suggestion : marketData.search(instrumentName)) {
                String symbol = suggestion.symbol();
                if (symbol == null) continue;
                String upper = symbol.toUpperCase();
                // CAS holdings are Indian equities/ETFs — a Yahoo hit on a foreign exchange is
                // never the right instrument here, whatever its name similarity score.
                if (!upper.endsWith(".NS") && !upper.endsWith(".BO")) continue;
                if (namesLikelyMatch(instrumentName, suggestion.name())) return symbol;
            }
        } catch (RuntimeException e) {
            log.debug("Ticker guess failed for '{}': {}", instrumentName, e.toString());
        }
        return null;
    }

    // Deliberately excludes words like "INDUSTRIES" or "POWER" that happen to follow a company
    // name — those are load-bearing for telling apart names that share a group prefix (Reliance
    // Industries vs. Reliance Power vs. Reliance Capital), not generic legal-entity boilerplate.
    private static final Set<String> CORPORATE_SUFFIXES = Set.of(
            "LIMITED", "LTD", "PVT", "PRIVATE", "CO", "INC", "PLC");

    /** True when two company names share enough significant words to be the same instrument —
     *  conservative enough to skip a coincidental one-word overlap, lenient enough to absorb word
     *  order, punctuation, and "Limited" vs "Ltd" differences between a CAS statement and Yahoo's
     *  own naming. Package-private and pure so it's unit-testable without a search call. */
    static boolean namesLikelyMatch(String a, String b) {
        Set<String> wordsA = significantWords(a);
        Set<String> wordsB = significantWords(b);
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false;
        // Full containment of the shorter name's words, not just a majority overlap — conglomerate
        // families (Reliance Industries vs. Reliance Power, Tata Motors vs. Tata Steel) share their
        // first word constantly, so "most words overlap" is not a safe bar on its own.
        Set<String> smaller = wordsA.size() <= wordsB.size() ? wordsA : wordsB;
        Set<String> larger = smaller == wordsA ? wordsB : wordsA;
        return larger.containsAll(smaller);
    }

    private static Set<String> significantWords(String name) {
        if (name == null || name.isBlank()) return Set.of();
        Set<String> words = new LinkedHashSet<>();
        for (String word : name.toUpperCase().replaceAll("[^A-Z0-9 ]", " ").split("\\s+")) {
            if (!word.isBlank() && !CORPORATE_SUFFIXES.contains(word)) words.add(word);
        }
        return words;
    }

    /**
     * Claude's PDF support rejects any encrypted document outright, and a CDSL/NSDL CAS is
     * essentially always password-protected (usually the holder's PAN) — so this strips that
     * protection locally first. A PDF with no password at all passes through unchanged. Package-
     * private and side-effect-free (beyond reading the input) so it's unit-testable without a
     * network call.
     */
    static byte[] decryptIfNeeded(byte[] pdf, String password) {
        try (PDDocument document = Loader.loadPDF(pdf, password == null ? "" : password)) {
            if (!document.isEncrypted()) return pdf;
            document.setAllSecurityToBeRemoved(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (InvalidPasswordException e) {
            if (password == null || password.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This statement is password-protected — enter the password (usually your PAN in capital letters) and try again.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That password didn't work — check it and try again.");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Couldn't open that file — make sure it's an unmodified PDF and try again.");
        }
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
            return new CasExtractionResponse(List.of(), List.of(), null,
                    List.of("Couldn't read any holdings from this statement — try a different file."));
        }

        List<ExtractedHolding> rows = new ArrayList<>();
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
            rows.add(new ExtractedHolding(name, isin.trim().toUpperCase(), quantityNode.decimalValue(), avgCost, depository, null, null));
        }

        String statementDate = blankToNull(toolInput.path("statementDate").asText(null));
        return new CasExtractionResponse(rows, List.of(), statementDate, warnings);
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
                // Anthropic's error responses are pure request-validation metadata (an
                // invalid_request_error's type/message describe what was wrong with the request
                // we sent — model, schema, size/page limits — never an echo of the document's
                // content), so logging the body here is safe and, unlike a bare status code, is
                // actually debuggable.
                log.warn("Anthropic API returned {} for a CAS extraction request: {}", response.statusCode(), response.body());
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
