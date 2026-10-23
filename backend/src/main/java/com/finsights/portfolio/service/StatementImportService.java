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
import java.io.ByteArrayInputStream;
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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
 * the holder's PAN). An XLSX/XLS upload is read locally with Apache POI and flattened to the same
 * kind of plain text a CSV/TSV/XML upload already is (see {@link #spreadsheetToText}) — Claude has
 * no native spreadsheet content block, so this is the only way to hand it a workbook at all.
 * Everything else is treated as a plain-text statement (CSV, TSV, XML, or similar) and sent inline
 * as text — OCR'd/scanned image statements aren't supported yet.
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
 * an applied fact. A transaction row is matched to an existing holding by ISIN tag, then by a
 * unique name match; a row that matches neither is still surfaced (with no holding preselected, so
 * the user picks one in the review form) and summarised in one {@code warnings} note.
 */
@Service
public class StatementImportService {
    private static final Logger log = LoggerFactory.getLogger(StatementImportService.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final URI MESSAGES_URI = URI.create("https://api.anthropic.com/v1/messages");
    // A multi-year order-history export can run into the thousands of rows, and each extracted
    // holding/transaction costs several dozen output tokens of JSON — 4096 was only ever enough
    // for a short CAS-style statement. Raised well past that, and large spreadsheets are additionally
    // split into row-chunked calls (see MAX_SPREADSHEET_ROWS_PER_CALL) so neither a single call's
    // output budget nor its input size has to hold an entire multi-thousand-row statement at once.
    private static final int MAX_TOKENS = 8192;
    /** Generous ceilings against an oversized or mistaken upload, not real-world limits — rejected
     *  before an API call is ever made. */
    static final int MAX_PDF_BYTES = 15 * 1024 * 1024;
    static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    // XLSX/XLS are zip/OLE2 containers, so the same byte count holds far less statement content
    // than raw text does — a size between the two others is plenty of headroom for a real broker
    // workbook while still catching an oversized/mistaken upload before it reaches POI.
    static final int MAX_SPREADSHEET_BYTES = 10 * 1024 * 1024;
    // Bounds the converted text's token cost regardless of how many sheets or rows a workbook
    // has — a statement with more real data than this isn't something a broker export produces,
    // so it's treated as a mistaken upload rather than silently truncated without a word.
    static final int MAX_SPREADSHEET_ROWS = 10_000;
    // A single extraction call's output has to list every holding/transaction it finds as JSON, so
    // a chunk this size comfortably fits MAX_TOKENS even in the worst case (every row a distinct
    // transaction with notes) while still being large enough that a typical statement needs only
    // one or two calls.
    static final int MAX_SPREADSHEET_ROWS_PER_CALL = 150;
    private static final int UNMATCHED_NAMES_IN_WARNING = 5;

    private static final String TOOL_NAME = "extract_statement";
    private static final byte[] PDF_MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);
    // ZIP local-file-header signature — every OOXML file (.xlsx among them) is a zip archive.
    private static final byte[] XLSX_MAGIC = { 0x50, 0x4B, 0x03, 0x04 };
    // OLE2/Compound File Binary signature — every legacy .xls file is one of these.
    private static final byte[] XLS_MAGIC = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 };

    // Scoped to equity/ETF lines for holdings (same pilot scope CAS shipped with) and to the
    // transaction types FinSights already tracks. CAS also lists mutual funds, bonds, and
    // insurance, which don't fit the same per-unit-quantity shape and are left for a later pass.
    // Out-of-scope or unmatched rows land in `warnings`, not silently dropped, so a short result
    // has an explanation.
    private static final String SYSTEM_PROMPT = """
            You read uploaded financial statements — a CDSL/NSDL Consolidated Account Statement (CAS), a \
            broker's own holding statement, or a broker's transaction statement, in PDF, a spreadsheet \
            (XLSX/XLS, flattened to plain text with one "Sheet: <name>" line per sheet), or plain text \
            (CSV/TSV/XML) — and extract structured data from them using the extract_statement tool. Follow \
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
            or quantity when the line doesn't state one (e.g. a SPLIT has no amount). If a line shows its \
            own order/execution status, only report it when that status is executed/completed — skip a \
            cancelled, rejected, pending, or open order entirely (no warning needed; it never happened).
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
        UploadFormat format = detectFormat(file);
        int maxBytes = switch (format) {
            case PDF -> MAX_PDF_BYTES;
            case SPREADSHEET -> MAX_SPREADSHEET_BYTES;
            case TEXT -> MAX_TEXT_BYTES;
        };
        if (file.length > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That file is larger than " + (maxBytes / (1024 * 1024)) + "MB — is it the right statement?");
        }

        // Decrypt before touching the daily quota — a wrong/missing password costs nothing and
        // shouldn't burn a user's upload allowance for a request that was never going to reach
        // the model. Only a PDF can have a password.
        byte[] decrypted = format == UploadFormat.PDF ? decryptIfNeeded(file, password) : file;

        String userId = currentUser.currentUser().getId();
        if (!rateLimiter.tryConsume(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've hit today's statement-upload limit — try again tomorrow.");
        }

        StatementExtractionResponse extracted = switch (format) {
            case PDF -> parseExtraction(callClaude(
                    buildPdfRequestBody(json, model, Base64.getEncoder().encodeToString(decrypted))));
            case SPREADSHEET -> extractFromChunks(
                    chunkFlattenedSpreadsheet(spreadsheetToText(decrypted), MAX_SPREADSHEET_ROWS_PER_CALL));
            case TEXT -> parseExtraction(callClaude(buildTextRequestBody(json, model, decodeText(decrypted))));
        };
        return matchAgainstExisting(enrichWithTickers(extracted), userId);
    }

    /** One extraction call per chunk — see {@link #chunkFlattenedSpreadsheet} for why a large
     *  spreadsheet is split at all — merged into a single result via {@link #mergeExtractions}. */
    private StatementExtractionResponse extractFromChunks(List<String> chunks) {
        List<StatementExtractionResponse> parsed = new ArrayList<>();
        for (String chunk : chunks) {
            parsed.add(parseExtraction(callClaude(buildTextRequestBody(json, model, chunk))));
        }
        return mergeExtractions(parsed);
    }

    /** What an uploaded statement turns out to be, decided from its own bytes rather than a
     *  filename or declared content type — a browser's guess at either is not to be trusted. */
    private enum UploadFormat { PDF, SPREADSHEET, TEXT }

    private static UploadFormat detectFormat(byte[] file) {
        if (hasMagic(file, PDF_MAGIC)) return UploadFormat.PDF;
        if (hasMagic(file, XLS_MAGIC) || hasMagic(file, XLSX_MAGIC)) return UploadFormat.SPREADSHEET;
        return UploadFormat.TEXT;
    }

    private static boolean hasMagic(byte[] file, byte[] magic) {
        if (file.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (file[i] != magic[i]) return false;
        return true;
    }

    private static String decodeText(byte[] file) {
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            return decoder.decode(java.nio.ByteBuffer.wrap(file)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That doesn't look like a PDF, spreadsheet (XLSX/XLS), or text (CSV/TSV/XML) statement — try a different file.");
        }
    }

    /**
     * Flattens every non-empty sheet of an uploaded XLSX/XLS workbook into plain, comma-joined
     * text — one {@code Sheet: <name>} header per sheet, one line per row — so it can go through
     * the exact same text pipeline a CSV/TSV upload already does; Claude has no native spreadsheet
     * content block. Capped at {@link #MAX_SPREADSHEET_ROWS} rows across all sheets combined: a
     * broker statement never legitimately has more, so the rest is dropped with a note appended
     * rather than silently included or blowing up the request's token cost.
     */
    static String spreadsheetToText(byte[] file) {
        DataFormatter formatter = new DataFormatter();
        StringBuilder text = new StringBuilder();
        int rowsSoFar = 0;
        boolean truncated = false;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file))) {
            for (int s = 0; s < workbook.getNumberOfSheets() && !truncated; s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet.getPhysicalNumberOfRows() == 0) continue;
                text.append("Sheet: ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    if (rowsSoFar >= MAX_SPREADSHEET_ROWS) { truncated = true; break; }
                    text.append(rowText(row, formatter)).append('\n');
                    rowsSoFar++;
                }
            }
        } catch (IOException | org.apache.poi.EncryptedDocumentException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Couldn't open that file — make sure it's an unmodified XLSX/XLS and try again.");
        }
        if (truncated) {
            text.append("[Statement truncated after ").append(MAX_SPREADSHEET_ROWS).append(" rows]\n");
        }
        return text.toString();
    }

    /**
     * Splits an already-flattened spreadsheet (see {@link #spreadsheetToText}) into one or more
     * self-contained chunks of at most {@code maxDataRowsPerChunk} data rows each, so a statement
     * with thousands of rows (a multi-year order history, say) is sent to Claude as several smaller
     * extraction calls instead of one whose output the model can't fit in a single response. Each
     * chunk keeps its own {@code Sheet: <name>} header line so the model can still see which sheet
     * it's reading; a truncation notice is kept attached to whichever chunk it falls in rather than
     * starting a near-empty chunk of its own.
     */
    static List<String> chunkFlattenedSpreadsheet(String text, int maxDataRowsPerChunk) {
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        String sheetHeader = null;
        int rowsInChunk = 0;
        for (String line : text.lines().toList()) {
            boolean isSheetHeader = line.startsWith("Sheet: ");
            boolean isMarkerLine = line.startsWith("[");
            if (isSheetHeader) {
                if (!chunk.isEmpty()) chunks.add(chunk.toString());
                chunk = new StringBuilder();
                rowsInChunk = 0;
                sheetHeader = line;
                chunk.append(line).append('\n');
                continue;
            }
            if (!isMarkerLine && rowsInChunk >= maxDataRowsPerChunk) {
                chunks.add(chunk.toString());
                chunk = new StringBuilder();
                rowsInChunk = 0;
                if (sheetHeader != null) chunk.append(sheetHeader).append('\n');
            }
            chunk.append(line).append('\n');
            if (!isMarkerLine) rowsInChunk++;
        }
        if (!chunk.isEmpty()) chunks.add(chunk.toString());
        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    /**
     * Combines the per-chunk results of {@link #extractFromChunks} into one response: holdings are
     * deduplicated by ISIN (keeping the first sighting — the same holding can legitimately appear in
     * more than one chunk of the same statement), transactions and warnings are simply concatenated,
     * the first non-null statement date wins, and the statement type is whatever every chunk agreed
     * on, or MIXED if they didn't. Package-private and pure so it's unit-testable without a network
     * call.
     */
    static StatementExtractionResponse mergeExtractions(List<StatementExtractionResponse> parts) {
        if (parts.isEmpty()) {
            return new StatementExtractionResponse(StatementType.HOLDINGS, List.of(), List.of(), List.of(), null, List.of());
        }
        if (parts.size() == 1) return parts.get(0);

        Set<StatementType> types = parts.stream().map(StatementExtractionResponse::statementType).collect(Collectors.toSet());
        StatementType mergedType = types.size() == 1 ? types.iterator().next() : StatementType.MIXED;

        Map<String, ExtractedHolding> holdingsByIsin = new LinkedHashMap<>();
        List<ExtractedTransaction> transactions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String statementDate = null;
        for (StatementExtractionResponse part : parts) {
            for (ExtractedHolding holding : part.holdings()) holdingsByIsin.putIfAbsent(holding.isin(), holding);
            transactions.addAll(part.transactions());
            warnings.addAll(part.warnings());
            if (statementDate == null) statementDate = part.statementDate();
        }
        return new StatementExtractionResponse(mergedType, List.copyOf(holdingsByIsin.values()), List.of(),
                transactions, statementDate, warnings);
    }

    private static String rowText(Row row, DataFormatter formatter) {
        StringBuilder line = new StringBuilder();
        short lastCell = row.getLastCellNum();
        for (int c = 0; c < lastCell; c++) {
            if (c > 0) line.append(',');
            Cell cell = row.getCell(c);
            if (cell != null) line.append(formatter.formatCellValue(cell).replace(",", " "));
        }
        return line.toString();
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
     * transaction row is matched by ISIN tag, falling back to a unique name match (a holding added
     * by hand has no tag); one that matches neither keeps a null {@code matchedHoldingId} so the
     * user picks the holding in the review form, and a single summary note is added to {@code
     * warnings}. Dropping those rows was the bug that made a first-time broker transaction export
     * look like "nothing extracted".
     *
     * <p>The obvious limitation: if a user edits that description's free-text Notes field after
     * import, the tag is gone and this holding can no longer be matched or offered for cleanup —
     * an acceptable trade for not adding a schema column for a pilot feature.
     *
     * <p>Package-private (not private) purely so it can be unit-tested directly against a mocked
     * {@link HoldingRepository}.
     */
    StatementExtractionResponse matchAgainstExisting(StatementExtractionResponse extracted, String userId) {
        List<Holding> userHoldings = holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId);
        Map<String, Holding> byIsin = new LinkedHashMap<>();
        for (Holding h : userHoldings) {
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
        Map<String, Integer> unmatchedCounts = new LinkedHashMap<>();
        for (ExtractedTransaction row : extracted.transactions()) {
            Holding existing = byIsin.get(row.isin());
            if (existing == null) existing = uniqueHoldingByName(userHoldings, row.instrumentName());
            if (existing == null) unmatchedCounts.merge(row.instrumentName(), 1, Integer::sum);
            String holdingId = existing == null ? null : existing.getId();
            matchedTransactions.add(new ExtractedTransaction(row.date(), row.type(), row.instrumentName(), row.isin(),
                    row.quantity(), row.amount(), row.notes(), holdingId));
        }
        unmatchedTransactionWarning(unmatchedCounts).ifPresent(warnings::add);

        return new StatementExtractionResponse(extracted.statementType(), matchedHoldings, toRemove,
                matchedTransactions, extracted.statementDate(), warnings);
    }

    /** The user's one holding whose name plausibly matches a statement instrument name, or null
     *  when none or several do — an ambiguous guess is left for the user to pick instead.
     *  Fallback for a holding that has no "ISIN" tag because it was added by hand. */
    static Holding uniqueHoldingByName(List<Holding> userHoldings, String instrumentName) {
        List<Holding> candidates = userHoldings.stream()
                .filter(h -> namesLikelyMatch(instrumentName, h.getName()))
                .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /** One summary note for transactions with no matching holding, instead of one note per row. */
    static java.util.Optional<String> unmatchedTransactionWarning(Map<String, Integer> unmatchedCounts) {
        if (unmatchedCounts.isEmpty()) return java.util.Optional.empty();
        int total = unmatchedCounts.values().stream().mapToInt(Integer::intValue).sum();
        String names = unmatchedCounts.keySet().stream().limit(UNMATCHED_NAMES_IN_WARNING).collect(Collectors.joining(", "));
        int more = unmatchedCounts.size() - UNMATCHED_NAMES_IN_WARNING;
        String suffix = more > 0 ? " and " + more + " more" : "";
        return java.util.Optional.of(total + " transaction" + (total == 1 ? "" : "s") + " across "
                + unmatchedCounts.size() + " instrument" + (unmatchedCounts.size() == 1 ? "" : "s")
                + " (" + names + suffix + ") don't match any holding in your portfolio yet — pick the holding "
                + "when you review each one, or add the holding first.");
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
                + "extract_statement tool. The statement (as uploaded, e.g. CSV/TSV, XML, or a flattened "
                + "spreadsheet) follows:\n\n" + statementText);

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
        JsonNode toolInput = findToolInput(apiResponse);
        if (toolInput == null || toolInput.isMissingNode()) {
            return new StatementExtractionResponse(StatementType.HOLDINGS, List.of(), List.of(), List.of(), null,
                    List.of("Couldn't read anything from this statement — try a different file."));
        }

        List<String> warnings = new ArrayList<>();
        toolInput.path("warnings").forEach(node -> warnings.add(node.asText()));

        List<ExtractedHolding> holdingRows = new ArrayList<>();
        for (JsonNode row : toolInput.path("holdings")) parseHoldingRow(row, holdingRows, warnings);

        List<ExtractedTransaction> txnRows = new ArrayList<>();
        for (JsonNode row : toolInput.path("transactions")) parseTransactionRow(row, txnRows, warnings);

        StatementType statementType = parseStatementType(toolInput.path(FIELD_STATEMENT_TYPE).asText(null));
        String statementDate = blankToNull(toolInput.path("statementDate").asText(null));
        return new StatementExtractionResponse(statementType, holdingRows, List.of(), txnRows, statementDate, warnings);
    }

    private static JsonNode findToolInput(JsonNode apiResponse) {
        for (JsonNode block : apiResponse.path("content")) {
            if ("tool_use".equals(block.path(SCHEMA_TYPE).asText()) && TOOL_NAME.equals(block.path("name").asText())) {
                return block.path("input");
            }
        }
        return null;
    }

    private static void parseHoldingRow(JsonNode row, List<ExtractedHolding> holdingRows, List<String> warnings) {
        String name = blankToNull(row.path(FIELD_INSTRUMENT_NAME).asText(null));
        String isin = blankToNull(row.path(FIELD_ISIN).asText(null));
        JsonNode quantityNode = row.path(FIELD_QUANTITY);
        if (name == null || isin == null || !quantityNode.isNumber() || quantityNode.decimalValue().signum() <= 0) {
            warnings.add("Skipped a holding row with an incomplete name, ISIN, or quantity" + (name != null ? " (" + name + ")" : ""));
            return;
        }
        JsonNode avgCostNode = row.path("averageCostPerUnit");
        BigDecimal avgCost = avgCostNode.isNumber() ? avgCostNode.decimalValue() : null;
        String depository = blankToNull(row.path("depository").asText(null));
        holdingRows.add(new ExtractedHolding(name, isin.trim().toUpperCase(Locale.ROOT), quantityNode.decimalValue(), avgCost, depository, null, null));
    }

    private static void parseTransactionRow(JsonNode row, List<ExtractedTransaction> txnRows, List<String> warnings) {
        String date = blankToNull(row.path("date").asText(null));
        String name = blankToNull(row.path(FIELD_INSTRUMENT_NAME).asText(null));
        String isin = blankToNull(row.path(FIELD_ISIN).asText(null));
        TransactionType type = parseTransactionType(row.path(SCHEMA_TYPE).asText(null));
        if (date == null || name == null || isin == null || type == null) {
            warnings.add("Skipped a transaction row with an incomplete date, type, name, or ISIN" + (name != null ? " (" + name + ")" : ""));
            return;
        }
        JsonNode quantityNode = row.path(FIELD_QUANTITY);
        JsonNode amountNode = row.path("amount");
        BigDecimal quantity = quantityNode.isNumber() ? quantityNode.decimalValue() : null;
        BigDecimal amount = amountNode.isNumber() ? amountNode.decimalValue() : null;
        String notes = blankToNull(row.path("notes").asText(null));
        txnRows.add(new ExtractedTransaction(date, type, name, isin.trim().toUpperCase(Locale.ROOT), quantity, amount, notes, null));
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
