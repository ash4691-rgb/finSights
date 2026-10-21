package com.finsights.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.ExtractedHolding;
import com.finsights.portfolio.dto.ExtractedTransaction;
import com.finsights.portfolio.dto.HoldingToRemove;
import com.finsights.portfolio.dto.StatementExtractionResponse;
import com.finsights.portfolio.dto.StatementExtractionResponse.StatementType;
import com.finsights.portfolio.dto.SymbolSuggestion;
import com.finsights.portfolio.repository.HoldingRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * Turns one uploaded statement — a CDSL/NSDL CAS, a broker's own holding statement, or a broker's
 * transaction statement, in whatever format the broker happens to export (PDF, or a machine-
 * readable CSV/XML/plain-text file) — into candidate holdings and/or transactions, via Claude's
 * tool-use ({@link #extract}). One upload box, one endpoint, one model call that reports back
 * {@code statementType} itself rather than the caller declaring up front what kind of file this is.
 *
 * <p>A PDF is sent as a native document content block, same as the CAS pilot this generalizes —
 * decrypted locally first with PDFBox (see {@link #decryptIfNeeded}) since Claude's PDF support
 * rejects an encrypted document outright, and a CAS is almost always password-protected (typically
 * the holder's PAN). Anything else is treated as a plain-text statement (CSV, XML, or similar) and
 * sent inline as text — OCR'd/scanned image statements aren't supported yet.
 *
 * <p><b>Nothing here writes to the database.</b> This is deliberate, not a missing feature: the
 * whole point of the product being "manual-first" is that an auto-populated row is never trusted
 * more than one the user typed in themselves. The frontend takes each {@link ExtractedHolding} and
 * {@link ExtractedTransaction} and pre-fills the ordinary Add Holding / Log Transaction form with
 * it — the user reviews, edits if needed, and saves (or discards) one at a time through the exact
 * same path and validation as manual entry. This service's only job is the extraction step.
 *
 * <p><b>PII posture:</b> the source file is held in memory for the one synchronous call and never
 * persisted or logged — only the structured fields below ever leave this method. The extraction
 * schema itself has no field for PAN, account/folio/demat numbers, address, phone, email, or any
 * other personal detail, and the system prompt tells the model explicitly never to return one even
 * if it's visible in the document — the model can only hand back what the schema has room for.
 *
 * <p>There's no real ISIN-to-ticker mapping yet (tracked as deferred work on #89), so each holding
 * row also gets a best-effort NSE/BSE ticker guess from its instrument name via {@link
 * #resolveTicker} — the same live-price search the manual Add Holding form's ticker field already
 * uses. A match pre-fills the review form as {@code MARKET_PRICE} with that ticker; no match falls
 * back to {@code MANUAL}, same as before. Either way it's still a suggestion the user reviews, not
 * an applied fact. A transaction row is only ever surfaced once matched to an existing holding by
 * the same ISIN lookup — logging a transaction against a holding that doesn't exist isn't something
 * the ordinary Log Transaction form can do, so an unmatched row goes into {@code warnings} instead.
 */
@Service
public class StatementImportService {
    private static final Logger log = LoggerFactory.getLogger(StatementImportService.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final URI MESSAGES_URI = URI.create("https://api.anthropic.com/v1/messages");
    private static final int MAX_TOKENS = 4096;
    /** Generous ceilings against an oversized or mistaken upload, not real-world limits — rejected
     *  before an API call is ever made. */
    static final int MAX_PDF_BYTES = 15 * 1024 * 1024;
    static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;

    private static final String TOOL_NAME = "extract_statement";
    private static final byte[] PDF_MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);

    // Scoped to equity/ETF lines for holdings (same pilot scope CAS shipped with) and to the
    // transaction types FinSights already tracks. CAS also lists mutual funds, bonds, and
    // insurance, which don't fit the same per-unit-quantity shape and are left for a later pass.
    // Out-of-scope or unmatched rows land in `warnings`, not silently dropped, so a short result
    // has an explanation.
    private static final String SYSTEM_PROMPT = """
            You read uploaded financial statements — a CDSL/NSDL Consolidated Account Statement (CAS), a \
            broker's own holding statement, or a broker's transaction statement, in PDF or plain text \
            (CSV/XML) — and extract structured data from them using the extract_statement tool. Follow \
            these rules exactly:

            1. First decide statementType: HOLDINGS if the document lists positions currently held, \
            TRANSACTIONS if it lists dated buy/sell/other activity, or MIXED if it has both. Only fill in \
            the `holdings` and/or `transactions` arrays that actually apply.
            2. For holdings: only report lines that are listed equities or ETFs with a quantity held. Skip \
            mutual fund folios, bonds, insurance, and anything else — instead add one line to `warnings` \
            naming what you skipped and why, so nothing is silently lost. Report the average cost per unit \
            only if the statement states one; omit the field rather than estimating it.
            3. For transactions: report each dated buy, sell, split, interest/dividend, adjustment, or loan \
            repayment line, with its date as YYYY-MM-DD and its type as one of BUY, SELL, SPLIT, INTEREST, \
            ADJUSTMENT, REPAY — picking the closest fit when a broker uses different wording. Omit amount \
            or quantity when the line doesn't state one (e.g. a SPLIT has no amount).
            4. Only ever return the fields the extract_statement tool defines. Never include PAN, bank \
            account numbers, demat/folio account numbers, address, phone number, email, or any other \
            personal information, even if it appears in the document — the schema has no field for it on \
            purpose.
            5. If you cannot confidently determine a holding's ISIN or quantity, or a transaction's date, \
            type, or instrument, leave that row out of the result entirely and explain why in `warnings` \
            instead of guessing.
            """;

    @Value("${app.statement-import.anthropic-api-key:}") private String apiKey;
    @Value("${app.statement-import.model:claude-sonnet-5}") private String model;

    private final StatementImportRateLimiter rateLimiter;
    private final CurrentUserService currentUser;
    private final MarketDataService marketData;
    private final HoldingRepository holdings;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public StatementImportService(StatementImportRateLimiter rateLimiter, CurrentUserService currentUser,
                                   MarketDataService marketData, HoldingRepository holdings) {
        this.rateLimiter = rateLimiter;
        this.currentUser = currentUser;
        this.marketData = marketData;
        this.holdings = holdings;
    }

    public StatementExtractionResponse extract(byte[] file, String password) {
        if (file == null || file.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was uploaded");
        }
        boolean isPdf = isPdf(file);
        int maxBytes = isPdf ? MAX_PDF_BYTES : MAX_TEXT_BYTES;
        if (file.length > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That file is larger than " + (maxBytes / (1024 * 1024)) + "MB — is it the right statement?");
        }

        // Decrypt before touching the daily quota — a wrong/missing password costs nothing and
        // shouldn't burn a user's upload allowance for a request that was never going to reach
        // the model. Non-PDF files have no password to check.
        byte[] decrypted = isPdf ? decryptIfNeeded(file, password) : file;

        String userId = currentUser.currentUser().getId();
        if (!rateLimiter.tryConsume(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've hit today's statement-upload limit — try again tomorrow.");
        }

        ObjectNode body = isPdf
                ? buildPdfRequestBody(json, model, Base64.getEncoder().encodeToString(decrypted))
                : buildTextRequestBody(json, model, decodeText(decrypted));
        JsonNode response = callClaude(body);
        StatementExtractionResponse extracted = parseExtraction(response);
        return matchAgainstExisting(enrichWithTickers(extracted), userId);
    }

    private static boolean isPdf(byte[] file) {
        if (file.length < PDF_MAGIC.length) return false;
        for (int i = 0; i < PDF_MAGIC.length; i++) if (file[i] != PDF_MAGIC[i]) return false;
        return true;
    }

    private static String decodeText(byte[] file) {
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            return decoder.decode(java.nio.ByteBuffer.wrap(file)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That doesn't look like a PDF or a text (CSV/XML) statement — try a different file.");
        }
    }

    /** Tags each row with (12-char) ISINs, same shape {@code ExtractedHolding.isin()} is already
     *  normalized to. */
    private static final Pattern ISIN_TAG = Pattern.compile("ISIN ([A-Z0-9]{12})");

    /**
     * There's no {@code isin} column on {@code Holding} yet (see issue #89's deferred list), so
     * this reuses the one place an ISIN is already on record: the "ISIN &lt;isin&gt;" note a
     * statement-imported holding's description carries (see the frontend's {@code
     * draftFromExtractedHolding}). A holding row whose ISIN matches one of those tagged holdings
     * needs no review — it's already in the portfolio — so it's reported as matched rather than
     * asked for again; a tagged holding whose ISIN doesn't turn up anywhere in a HOLDINGS (or
     * MIXED) statement is reported as a candidate to remove — never computed for a
     * transactions-only statement, which doesn't claim to be a full snapshot of what's held. A
     * transaction row is only ever surfaced once its ISIN matches an existing holding; an
     * unmatched one is dropped into {@code warnings} instead, since there's no form to log a
     * transaction against a holding that doesn't exist yet.
     *
     * <p>The obvious limitation: if a user edits that description's free-text Notes field after
     * import, the tag is gone and this holding can no longer be matched or offered for cleanup —
     * an acceptable trade for not adding a schema column for a pilot feature.
     *
     * <p>Package-private (not private) purely so it can be unit-tested directly against a mocked
     * {@link HoldingRepository}.
     */
    StatementExtractionResponse matchAgainstExisting(StatementExtractionResponse extracted, String userId) {
        Map<String, Holding> byIsin = new LinkedHashMap<>();
        for (Holding h : holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId)) {
            Matcher m = ISIN_TAG.matcher(h.getDescription() == null ? "" : h.getDescription());
            if (m.find()) byIsin.put(m.group(1), h);
        }

        Set<String> matchedIsins = new LinkedHashSet<>();
        List<ExtractedHolding> matchedHoldings = new ArrayList<>();
        for (ExtractedHolding row : extracted.holdings()) {
            Holding existing = byIsin.get(row.isin());
            if (existing != null) matchedIsins.add(row.isin());
            matchedHoldings.add(existing == null ? row
                    : new ExtractedHolding(row.instrumentName(), row.isin(), row.quantity(), row.averageCostPerUnit(),
                            row.depository(), row.tickerSymbol(), existing.getId()));
        }

        // Only a HOLDINGS/MIXED statement claims to be a full snapshot of what's currently held —
        // a transactions-only statement says nothing about what else is or isn't still held, so it
        // never generates a removal candidate.
        List<HoldingToRemove> toRemove = extracted.statementType() == StatementType.TRANSACTIONS
                ? List.of()
                : byIsin.entrySet().stream()
                        .filter(e -> !matchedIsins.contains(e.getKey()))
                        .map(e -> new HoldingToRemove(e.getValue().getId(), e.getValue().getName(), e.getValue().getBroker()))
                        .toList();

        List<String> warnings = new ArrayList<>(extracted.warnings());
        List<ExtractedTransaction> matchedTransactions = new ArrayList<>();
        for (ExtractedTransaction row : extracted.transactions()) {
            Holding existing = byIsin.get(row.isin());
            if (existing == null) {
                warnings.add("Skipped a " + row.type() + " transaction for " + row.instrumentName()
                        + " — no existing holding matches its ISIN. Add the holding first, then re-upload.");
                continue;
            }
            matchedTransactions.add(new ExtractedTransaction(row.date(), row.type(), row.instrumentName(), row.isin(),
                    row.quantity(), row.amount(), row.notes(), existing.getId()));
        }

        return new StatementExtractionResponse(extracted.statementType(), matchedHoldings, toRemove,
                matchedTransactions, extracted.statementDate(), warnings);
    }

    /**
     * Best-effort ISIN-to-ticker stand-in: there's no real mapping yet (see issue #89's deferred
     * list), so this guesses an NSE/BSE ticker from each holding row's instrument name via the
     * same live-price search {@code SymbolSearchInput} already uses on the Add Holding form — the
     * one search that's already tuned to rank Indian listings first. A row's own extraction never
     * fails because of this: {@link #resolveTicker} swallows every failure and simply leaves
     * {@code tickerSymbol} null, same as if nothing had been attempted.
     */
    private StatementExtractionResponse enrichWithTickers(StatementExtractionResponse extracted) {
        List<ExtractedHolding> enriched = extracted.holdings().stream()
                .map(h -> new ExtractedHolding(h.instrumentName(), h.isin(), h.quantity(), h.averageCostPerUnit(),
                        h.depository(), resolveTicker(h.instrumentName()), h.matchedHoldingId()))
                .toList();
        return new StatementExtractionResponse(extracted.statementType(), enriched, extracted.toRemove(),
                extracted.transactions(), extracted.statementDate(), extracted.warnings());
    }

    /** Package-private (not private) purely so it can be unit-tested directly against a mocked
     *  {@link MarketDataService} without going through the full, network-calling {@link #extract}. */
    String resolveTicker(String instrumentName) {
        try {
            for (SymbolSuggestion suggestion : marketData.search(instrumentName)) {
                String symbol = suggestion.symbol();
                // Statement holdings are Indian equities/ETFs — a Yahoo hit on a foreign exchange
                // is never the right instrument here, whatever its name similarity score.
                boolean isIndianListing = symbol != null
                        && (symbol.toUpperCase(Locale.ROOT).endsWith(".NS") || symbol.toUpperCase(Locale.ROOT).endsWith(".BO"));
                if (isIndianListing && namesLikelyMatch(instrumentName, suggestion.name())) return symbol;
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
     *  order, punctuation, and "Limited" vs "Ltd" differences between a statement and Yahoo's own
     *  naming. Package-private and pure so it's unit-testable without a search call. */
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
        return Arrays.stream(name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").split("\\s+"))
                .filter(word -> !word.isBlank() && !CORPORATE_SUFFIXES.contains(word))
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
    static ObjectNode buildPdfRequestBody(ObjectMapper json, String model, String pdfBase64) {
        ObjectNode document = json.createObjectNode();
        document.put("type", "document");
        ObjectNode source = json.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "application/pdf");
        source.put("data", pdfBase64);
        document.set("source", source);

        ObjectNode instruction = json.createObjectNode();
        instruction.put("type", "text");
        instruction.put("text", "Extract the holdings and/or transactions from this statement using the extract_statement tool.");

        ArrayNode content = json.createArrayNode();
        content.add(document);
        content.add(instruction);
        return buildRequestBody(json, model, content);
    }

    /** Package-private and pure (no network) so it can be unit-tested directly. */
    static ObjectNode buildTextRequestBody(ObjectMapper json, String model, String statementText) {
        ObjectNode instruction = json.createObjectNode();
        instruction.put("type", "text");
        instruction.put("text", "Extract the holdings and/or transactions from this statement using the "
                + "extract_statement tool. The statement (as uploaded, e.g. CSV or XML) follows:\n\n" + statementText);

        ArrayNode content = json.createArrayNode();
        content.add(instruction);
        return buildRequestBody(json, model, content);
    }

    private static ObjectNode buildRequestBody(ObjectMapper json, String model, ArrayNode content) {
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

    // Keys repeated across the hand-built JSON schema below — one spelling each, not one per use.
    private static final String SCHEMA_TYPE = "type";
    private static final String SCHEMA_OBJECT = "object";
    private static final String SCHEMA_ARRAY = "array";
    private static final String SCHEMA_PROPERTIES = "properties";
    private static final String SCHEMA_REQUIRED = "required";
    private static final String SCHEMA_ITEMS = "items";
    private static final String SCHEMA_DESCRIPTION = "description";
    private static final String FIELD_INSTRUMENT_NAME = "instrumentName";
    private static final String FIELD_ISIN = "isin";
    private static final String FIELD_QUANTITY = "quantity";
    private static final String FIELD_STATEMENT_TYPE = "statementType";
    private static final String INSTRUMENT_NAME_DESCRIPTION = "Company or instrument name exactly as printed";
    private static final String ISIN_DESCRIPTION = "The 12-character ISIN for this instrument";

    private static ObjectNode toolDefinition(ObjectMapper json) {
        ObjectNode holdingSchema = json.createObjectNode();
        holdingSchema.put(SCHEMA_TYPE, SCHEMA_OBJECT);
        ObjectNode holdingProps = json.createObjectNode();
        holdingProps.set(FIELD_INSTRUMENT_NAME, textProp(json, INSTRUMENT_NAME_DESCRIPTION));
        holdingProps.set(FIELD_ISIN, textProp(json, ISIN_DESCRIPTION));
        holdingProps.set(FIELD_QUANTITY, numberProp(json, "Units held"));
        holdingProps.set("averageCostPerUnit", numberProp(json, "Average cost per unit — omit entirely if the statement doesn't show one"));
        ObjectNode depository = textProp(json, "Which depository this line is held under, if shown");
        depository.set("enum", json.createArrayNode().add("NSDL").add("CDSL").add("UNKNOWN"));
        holdingProps.set("depository", depository);
        holdingSchema.set(SCHEMA_PROPERTIES, holdingProps);
        holdingSchema.set(SCHEMA_REQUIRED, json.createArrayNode().add(FIELD_INSTRUMENT_NAME).add(FIELD_ISIN).add(FIELD_QUANTITY));

        ObjectNode holdingsArray = json.createObjectNode();
        holdingsArray.put(SCHEMA_TYPE, SCHEMA_ARRAY);
        holdingsArray.set(SCHEMA_ITEMS, holdingSchema);

        ObjectNode txnSchema = json.createObjectNode();
        txnSchema.put(SCHEMA_TYPE, SCHEMA_OBJECT);
        ObjectNode txnProps = json.createObjectNode();
        txnProps.set("date", textProp(json, "YYYY-MM-DD"));
        ObjectNode txnType = textProp(json, "Closest fit to the broker's own wording");
        txnType.set("enum", json.createArrayNode().add("BUY").add("SELL").add("SPLIT").add("INTEREST").add("ADJUSTMENT").add("REPAY"));
        txnProps.set(SCHEMA_TYPE, txnType);
        txnProps.set(FIELD_INSTRUMENT_NAME, textProp(json, INSTRUMENT_NAME_DESCRIPTION));
        txnProps.set(FIELD_ISIN, textProp(json, ISIN_DESCRIPTION));
        txnProps.set(FIELD_QUANTITY, numberProp(json, "Omit if this line has no quantity (e.g. INTEREST)"));
        txnProps.set("amount", numberProp(json, "Omit if this line has no amount (e.g. SPLIT)"));
        txnProps.set("notes", textProp(json, "Any short free-text note from the statement line, if present"));
        txnSchema.set(SCHEMA_PROPERTIES, txnProps);
        txnSchema.set(SCHEMA_REQUIRED, json.createArrayNode().add("date").add(SCHEMA_TYPE).add(FIELD_INSTRUMENT_NAME).add(FIELD_ISIN));

        ObjectNode transactionsArray = json.createObjectNode();
        transactionsArray.put(SCHEMA_TYPE, SCHEMA_ARRAY);
        transactionsArray.set(SCHEMA_ITEMS, txnSchema);

        ObjectNode warningsArray = json.createObjectNode();
        warningsArray.put(SCHEMA_TYPE, SCHEMA_ARRAY);
        warningsArray.set(SCHEMA_ITEMS, textProp(json, null));
        warningsArray.put(SCHEMA_DESCRIPTION, "Anything skipped, unclear, or out of scope — see the rules above.");

        ObjectNode statementType = textProp(json, "What this statement contains");
        statementType.set("enum", json.createArrayNode().add("HOLDINGS").add("TRANSACTIONS").add("MIXED"));

        ObjectNode topProps = json.createObjectNode();
        topProps.set(FIELD_STATEMENT_TYPE, statementType);
        topProps.set("statementDate", textProp(json, "As-of date of the statement, YYYY-MM-DD, if determinable"));
        topProps.set("holdings", holdingsArray);
        topProps.set("transactions", transactionsArray);
        topProps.set("warnings", warningsArray);

        ObjectNode inputSchema = json.createObjectNode();
        inputSchema.put(SCHEMA_TYPE, SCHEMA_OBJECT);
        inputSchema.set(SCHEMA_PROPERTIES, topProps);
        inputSchema.set(SCHEMA_REQUIRED, json.createArrayNode().add(FIELD_STATEMENT_TYPE));

        ObjectNode tool = json.createObjectNode();
        tool.put("name", TOOL_NAME);
        tool.put(SCHEMA_DESCRIPTION, "Report the holdings and/or transactions found in this statement.");
        tool.set("input_schema", inputSchema);
        return tool;
    }

    private static ObjectNode textProp(ObjectMapper json, String description) {
        ObjectNode node = json.createObjectNode();
        node.put(SCHEMA_TYPE, "string");
        if (description != null) node.put(SCHEMA_DESCRIPTION, description);
        return node;
    }

    private static ObjectNode numberProp(ObjectMapper json, String description) {
        ObjectNode node = json.createObjectNode();
        node.put(SCHEMA_TYPE, "number");
        if (description != null) node.put(SCHEMA_DESCRIPTION, description);
        return node;
    }

    /** Package-private and pure so it can be unit-tested against a canned API response. Fault
     *  tolerant per row, same philosophy as TransactionImportRunner: a malformed row is dropped
     *  into {@code warnings} rather than failing the whole statement. */
    static StatementExtractionResponse parseExtraction(JsonNode apiResponse) {
        JsonNode toolInput = null;
        for (JsonNode block : apiResponse.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && TOOL_NAME.equals(block.path("name").asText())) {
                toolInput = block.path("input");
                break;
            }
        }
        if (toolInput == null || toolInput.isMissingNode()) {
            return new StatementExtractionResponse(StatementType.HOLDINGS, List.of(), List.of(), List.of(), null,
                    List.of("Couldn't read anything from this statement — try a different file."));
        }

        List<String> warnings = new ArrayList<>();
        for (JsonNode node : toolInput.path("warnings")) warnings.add(node.asText());

        List<ExtractedHolding> holdingRows = new ArrayList<>();
        for (JsonNode row : toolInput.path("holdings")) {
            String name = blankToNull(row.path("instrumentName").asText(null));
            String isin = blankToNull(row.path("isin").asText(null));
            JsonNode quantityNode = row.path("quantity");
            if (name == null || isin == null || !quantityNode.isNumber() || quantityNode.decimalValue().signum() <= 0) {
                warnings.add("Skipped a holding row with an incomplete name, ISIN, or quantity" + (name != null ? " (" + name + ")" : ""));
                continue;
            }
            JsonNode avgCostNode = row.path("averageCostPerUnit");
            BigDecimal avgCost = avgCostNode.isNumber() ? avgCostNode.decimalValue() : null;
            String depository = blankToNull(row.path("depository").asText(null));
            holdingRows.add(new ExtractedHolding(name, isin.trim().toUpperCase(), quantityNode.decimalValue(), avgCost, depository, null, null));
        }

        List<ExtractedTransaction> txnRows = new ArrayList<>();
        for (JsonNode row : toolInput.path("transactions")) {
            String date = blankToNull(row.path("date").asText(null));
            String name = blankToNull(row.path("instrumentName").asText(null));
            String isin = blankToNull(row.path("isin").asText(null));
            TransactionType type = parseTransactionType(row.path("type").asText(null));
            if (date == null || name == null || isin == null || type == null) {
                warnings.add("Skipped a transaction row with an incomplete date, type, name, or ISIN" + (name != null ? " (" + name + ")" : ""));
                continue;
            }
            JsonNode quantityNode = row.path("quantity");
            JsonNode amountNode = row.path("amount");
            BigDecimal quantity = quantityNode.isNumber() ? quantityNode.decimalValue() : null;
            BigDecimal amount = amountNode.isNumber() ? amountNode.decimalValue() : null;
            String notes = blankToNull(row.path("notes").asText(null));
            txnRows.add(new ExtractedTransaction(date, type, name, isin.trim().toUpperCase(), quantity, amount, notes, null));
        }

        StatementType statementType = parseStatementType(toolInput.path("statementType").asText(null));
        String statementDate = blankToNull(toolInput.path("statementDate").asText(null));
        return new StatementExtractionResponse(statementType, holdingRows, List.of(), txnRows, statementDate, warnings);
    }

    private static StatementType parseStatementType(String value) {
        if (value == null) return StatementType.HOLDINGS;
        try { return StatementType.valueOf(value); } catch (IllegalArgumentException e) { return StatementType.HOLDINGS; }
    }

    private static TransactionType parseTransactionType(String value) {
        if (value == null) return null;
        try { return TransactionType.valueOf(value); } catch (IllegalArgumentException e) { return null; }
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
                log.warn("Anthropic API returned {} for a statement extraction request: {}", response.statusCode(), response.body());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't read that statement right now — try again shortly.");
            }
            return json.readTree(response.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for the Anthropic API", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't read that statement right now — try again shortly.");
        } catch (IOException e) {
            log.warn("Failed to reach the Anthropic API for a statement extraction request", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't read that statement right now — try again shortly.");
        }
    }
}
