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
import com.finsights.portfolio.dto.InstrumentKind;
import com.finsights.portfolio.dto.StatementExtractionResponse;
import com.finsights.portfolio.dto.StatementExtractionResponse.StatementType;
import com.finsights.portfolio.dto.StatementReconcileRequest;
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
    private static final int MAX_TICKER_LOOKUPS = 30;

    private static final String TOOL_NAME = "extract_statement";
    private static final byte[] PDF_MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);
    // ZIP local-file-header signature — every OOXML file (.xlsx among them) is a zip archive.
    private static final byte[] XLSX_MAGIC = { 0x50, 0x4B, 0x03, 0x04 };
    // OLE2/Compound File Binary signature — every legacy .xls file is one of these.
    private static final byte[] XLS_MAGIC = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 };

    // Scoped to equities, ETFs and mutual funds for holdings (crypto and commodities are a later
    // pass) and to the transaction types FinSights already tracks. Bonds, insurance and anything
    // else land in `warnings`, not silently dropped, so a short result has an explanation.
    private static final String SYSTEM_PROMPT = """
            You read uploaded financial statements — a CDSL/NSDL Consolidated Account Statement (CAS), a \
            broker's or investing platform's own holding statement, or a transaction statement, in PDF, a \
            spreadsheet (XLSX/XLS, flattened to plain text with one "Sheet: <name>" line per sheet), or plain \
            text (CSV/TSV/XML) — and extract structured data from them using the extract_statement tool. \
            Follow these rules exactly:

            1. First decide statementType: HOLDINGS if the document lists positions currently held, \
            TRANSACTIONS if it lists dated buy/sell/other activity, or MIXED if it has both. Only fill in \
            the `holdings` and/or `transactions` arrays that actually apply. Also report `broker`: the name \
            of the broker or investing platform the statement comes from, only if the document names it — \
            never guess one.
            2. For holdings: report listed equities, ETFs, mutual fund folios, crypto assets and commodities \
            (gold, silver and the like) that have a quantity held, and set each row's `kind` to STOCK, ETF, \
            MUTUAL_FUND, CRYPTO or COMMODITY. For a mutual fund use the scheme name as the instrumentName \
            and the units held as the quantity; for a crypto asset use the coin's name and put its ticker \
            (BTC, ETH…) in `symbol` when printed; report one row per line as printed (the same scheme or \
            coin may appear on several lines). Report investedValue and currentValue as the statement's own \
            totals for that line, when it states them. Skip bonds, insurance and anything else that isn't \
            one of those five kinds — instead add one line to `warnings` naming what you skipped and why, \
            so nothing is silently lost. Report the average cost per unit only if the statement states one; \
            omit the field rather than estimating it.
            3. For transactions: report each dated buy, sell, split, interest/dividend, adjustment, or loan \
            repayment line, with its date as YYYY-MM-DD and its type as one of BUY, SELL, SPLIT, INTEREST, \
            ADJUSTMENT, REPAY — picking the closest fit when a broker uses different wording. Omit amount \
            or quantity when the line doesn't state one (e.g. a SPLIT has no amount); when a line gives units \
            and a price or NAV per unit but no total, the amount is units × price. Set each line's `kind` \
            as for holdings (a mutual fund SIP instalment or lump sum is a BUY, a redemption is a SELL). If a line shows its \
            own order/execution status, only report it when that status is executed/completed — skip a \
            cancelled, rejected, pending, or open order entirely (no warning needed; it never happened).
            4. Only ever return the fields the extract_statement tool defines. Never include PAN, bank \
            account numbers, demat/folio account numbers, the holder's name, address, phone number, email, \
            or any other personal information, even if it appears in the document — the schema has no \
            field for it on purpose.
            5. A stock or ETF needs its ISIN and quantity; a mutual fund, crypto asset or commodity needs \
            its name and quantity (an ISIN is optional for those — omit the field if the statement has none). If you cannot \
            confidently determine those, or a transaction's date, type, or instrument, leave that row out \
            of the result entirely and explain why in `warnings` instead of guessing.
            6. If the statement covers several accounts (a CAS lists every demat account and fund folio you \
            hold), set each row's `account` to the institution that line is held through: the broker or \
            depository participant's name for shares (e.g. "Zerodha Broking"), the fund house for a mutual \
            fund. Use the institution's name only, never an account, client, DP or folio number. Leave \
            `account` out when the statement is from a single broker and doesn't name one per line.
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
        // Matching against the user's holdings waits for the broker to be confirmed (see reconcile).
        return enrichWithTickers(extracted);
    }

    /**
     * Second step of an import: takes the rows a first read produced plus what the user confirmed
     * (statement type and broker) and matches them against <em>that broker's</em> holdings only —
     * the same fund can legitimately be held at two brokers, so a statement is never matched
     * against everything. Needs no model call and writes nothing.
     */
    public StatementExtractionResponse reconcile(StatementReconcileRequest request) {
        if (request.broker() == null || request.broker().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pick the broker this statement belongs to first.");
        }
        StatementType type = request.statementType() == null ? StatementType.HOLDINGS : request.statementType();
        StatementExtractionResponse rows = new StatementExtractionResponse(type, StatementRows.consolidateAcrossAccounts(request.holdings()), List.of(),
                request.transactions(), request.statementDate(), request.warnings(), request.broker().trim());
        StatementExtractionResponse matched = matchAgainstExisting(rows, currentUser.currentUser().getId(), request.broker());
        return withTickersForNewBuys(matched);
    }

    /**
     * An unmatched BUY is the opening of a holding the user doesn't have yet, so each distinct such
     * instrument gets a ticker guess (once, not per row) for the holding to be market-valued. Capped:
     * a multi-year order history can name hundreds of instruments, and each guess is a live search —
     * the rest are simply left for the user to set in the form.
     */
    StatementExtractionResponse withTickersForNewBuys(StatementExtractionResponse matched) {
        Map<String, String> tickers = new LinkedHashMap<>();
        List<ExtractedTransaction> rows = new ArrayList<>();
        for (ExtractedTransaction row : matched.transactions()) {
            boolean opensNewHolding = row.matchedHoldingId() == null && row.type() == TransactionType.BUY;
            if (!opensNewHolding) { rows.add(row); continue; }
            String key = StatementRows.transactionKey(row);
            if (!tickers.containsKey(key) && tickers.size() < MAX_TICKER_LOOKUPS) {
                InstrumentKind kind = row.kind() == null ? InstrumentKind.STOCK : row.kind();
                tickers.put(key, resolveTicker(row.instrumentName(), row.symbol(), kind));
            }
            rows.add(row.withTicker(tickers.get(key)));
        }
        return new StatementExtractionResponse(matched.statementType(), matched.holdings(), matched.toRemove(), rows,
                matched.statementDate(), matched.warnings(), matched.broker());
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
     * consolidated (see {@link StatementRows#consolidate} — the same holding can legitimately appear in
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

        List<ExtractedHolding> allHoldings = new ArrayList<>();
        String broker = null;
        List<ExtractedTransaction> transactions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String statementDate = null;
        for (StatementExtractionResponse part : parts) {
            allHoldings.addAll(part.holdings());
            if (broker == null) broker = part.broker();
            transactions.addAll(part.transactions());
            warnings.addAll(part.warnings());
            if (statementDate == null) statementDate = part.statementDate();
        }
        return new StatementExtractionResponse(mergedType, StatementRows.consolidate(allHoldings), List.of(),
                transactions, statementDate, warnings, broker);
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
     * draftFromExtractedHolding}).
     *
     * <p>Only holdings at {@code broker} are considered at all — a holding is one per (name,
     * broker), and the same fund or stock can be held at several brokers, so matching against every
     * holding would attach a statement to the wrong one. A holding row matches by ISIN tag, falling
     * back to a unique name match (a holding added by hand has no tag; a mutual fund row often has
     * no ISIN); a matched row needs no review. For a HOLDINGS (or MIXED) statement — the only kinds
     * that claim to be a full snapshot — every market-linked holding at that broker that no row
     * matched is reported as missing, so the user can log the SELL that explains it. A transaction
     * row matches the same way; one that matches nothing keeps a null {@code matchedHoldingId} so
     * the user picks the holding in the review form, and a single summary note is added to {@code
     * warnings}.
     *
     * <p>Package-private (not private) purely so it can be unit-tested directly against a mocked
     * {@link HoldingRepository}.
     */
    StatementExtractionResponse matchAgainstExisting(StatementExtractionResponse extracted, String userId, String broker) {
        List<Holding> brokerHoldings = holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId).stream()
                .filter(h -> StatementRows.sameBroker(h.getBroker(), broker))
                .toList();
        Map<String, Holding> byIsin = taggedByIsin(brokerHoldings);

        Set<String> matchedIds = new LinkedHashSet<>();
        List<ExtractedHolding> matchedHoldings = new ArrayList<>();
        for (ExtractedHolding row : extracted.holdings()) {
            Holding existing = findExisting(row.isin(), row.instrumentName(), byIsin, brokerHoldings);
            if (existing != null) matchedIds.add(existing.getId());
            matchedHoldings.add(existing == null ? row : row.withMatchedHolding(existing.getId()));
        }

        List<HoldingToRemove> missing = extracted.statementType() == StatementType.TRANSACTIONS
                ? List.of()
                : missingFromStatement(brokerHoldings, matchedIds, extracted.holdings());

        List<String> warnings = new ArrayList<>(extracted.warnings());
        List<ExtractedTransaction> matchedTransactions = new ArrayList<>();
        Map<String, Integer> unmatchedCounts = new LinkedHashMap<>();
        for (ExtractedTransaction row : extracted.transactions()) {
            Holding existing = findExisting(row.isin(), row.instrumentName(), byIsin, brokerHoldings);
            if (existing == null && row.type() != TransactionType.SELL) unmatchedCounts.merge(row.instrumentName(), 1, Integer::sum);
            String holdingId = existing == null ? null : existing.getId();
            matchedTransactions.add(row.withMatchedHolding(holdingId));
        }
        unmatchedTransactionWarning(unmatchedCounts).ifPresent(warnings::add);
        unknownSellWarning(matchedTransactions, broker).ifPresent(warnings::add);

        return new StatementExtractionResponse(extracted.statementType(), matchedHoldings, missing,
                matchedTransactions, extracted.statementDate(), warnings, extracted.broker());
    }

    private static Map<String, Holding> taggedByIsin(List<Holding> brokerHoldings) {
        Map<String, Holding> byIsin = new LinkedHashMap<>();
        for (Holding h : brokerHoldings) {
            Matcher m = ISIN_TAG.matcher(h.getDescription() == null ? "" : h.getDescription());
            if (m.find()) byIsin.put(m.group(1), h);
        }
        return byIsin;
    }

    /** ISIN tag first, then a unique name match; null when neither finds exactly one holding. */
    private static Holding findExisting(String isin, String instrumentName, Map<String, Holding> byIsin,
                                        List<Holding> brokerHoldings) {
        Holding tagged = isin == null ? null : byIsin.get(isin);
        if (tagged != null) return tagged;
        return uniqueHoldingByName(brokerHoldings, instrumentName);
    }

    private static List<HoldingToRemove> missingFromStatement(List<Holding> brokerHoldings, Set<String> matchedIds,
                                                              List<ExtractedHolding> statementRows) {
        return brokerHoldings.stream()
                .filter(StatementRows::listedOnBrokerStatements)
                .filter(h -> StatementRows.couldBeListed(h, statementRows))
                .filter(h -> !matchedIds.contains(h.getId()))
                .map(h -> new HoldingToRemove(h.getId(), h.getName(), h.getBroker(), h.getQuantity()))
                .toList();
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
                + "when you review each one, or create the holding from its first buy.");
    }

    /** One note for SELLs of an instrument the user doesn't hold at this broker and whose BUY isn't in
     *  the statement either — there is nothing to sell from, so they can't be logged as they stand. */
    static java.util.Optional<String> unknownSellWarning(List<ExtractedTransaction> rows, String broker) {
        Set<String> boughtKeys = rows.stream()
                .filter(t -> t.matchedHoldingId() == null && t.type() == TransactionType.BUY)
                .map(StatementRows::transactionKey)
                .collect(Collectors.toSet());
        Map<String, Integer> sellCounts = new LinkedHashMap<>();
        for (ExtractedTransaction row : rows) {
            boolean orphanSell = row.matchedHoldingId() == null && row.type() == TransactionType.SELL
                    && !boughtKeys.contains(StatementRows.transactionKey(row));
            if (orphanSell) sellCounts.merge(row.instrumentName(), 1, Integer::sum);
        }
        if (sellCounts.isEmpty()) return java.util.Optional.empty();
        int total = sellCounts.values().stream().mapToInt(Integer::intValue).sum();
        String names = sellCounts.keySet().stream().limit(UNMATCHED_NAMES_IN_WARNING).collect(Collectors.joining(", "));
        int more = sellCounts.size() - UNMATCHED_NAMES_IN_WARNING;
        String suffix = more > 0 ? " and " + more + " more" : "";
        return java.util.Optional.of(total + " SELL transaction" + (total == 1 ? "" : "s") + " across " + sellCounts.size()
                + " instrument" + (sellCounts.size() == 1 ? "" : "s") + " (" + names + suffix + ") are for holdings you don't have at "
                + broker.trim() + ", and their buys aren't in this statement — add the holding first, or pick a different one when you review each.");
    }

    /**
     * Best-effort ISIN-to-ticker stand-in: there's no real mapping yet (see issue #89's deferred
     * list), so this guesses an NSE/BSE ticker from each holding row's instrument name via the
     * same live-price search {@code SymbolSearchInput} already uses on the Add Holding form — the
     * one search that's already tuned to rank Indian listings first. A row's own extraction never
     * fails because of this: {@link #resolveTicker} swallows every failure and simply leaves
     * {@code tickerSymbol} null, same as if nothing had been attempted.
     */
    StatementExtractionResponse enrichWithTickers(StatementExtractionResponse extracted) {
        List<ExtractedHolding> enriched = extracted.holdings().stream()
                .map(h -> h.withTicker(resolveTicker(h.instrumentName(), h.symbol(), h.kind())))
                .toList();
        return new StatementExtractionResponse(extracted.statementType(), enriched, extracted.toRemove(),
                extracted.transactions(), extracted.statementDate(), extracted.warnings(), extracted.broker());
    }

    String resolveTicker(String instrumentName) { return resolveTicker(instrumentName, null, InstrumentKind.STOCK); }

    String resolveTicker(String instrumentName, InstrumentKind kind) { return resolveTicker(instrumentName, null, kind); }

    /** Package-private (not private) purely so it can be unit-tested directly against a mocked
     *  {@link MarketDataService} without going through the full, network-calling {@link #extract}. */
    String resolveTicker(String instrumentName, String symbol, InstrumentKind kind) {
        // A commodity statement line (digital gold, a bullion balance…) has no listing that is priced
        // in the statement's own currency, so it's left unguessed — the user picks a valuation.
        if (kind == InstrumentKind.COMMODITY) return null;
        try {
            String query = symbol != null ? symbol : instrumentName;
            List<SymbolSuggestion> suggestions = marketData.search(query);
            if (kind == InstrumentKind.MUTUAL_FUND) return resolveFundSchemeCode(instrumentName, suggestions);
            if (kind == InstrumentKind.CRYPTO) return resolveCryptoPair(instrumentName, symbol, suggestions);
            return resolveIndianListing(instrumentName, suggestions);
        } catch (RuntimeException e) {
            log.debug("Ticker guess failed for '{}': {}", instrumentName, e.toString());
            return null;
        }
    }

    private static String resolveIndianListing(String instrumentName, List<SymbolSuggestion> suggestions) {
        for (SymbolSuggestion suggestion : suggestions) {
            String symbol = suggestion.symbol();
            // Statement holdings are Indian equities/ETFs — a Yahoo hit on a foreign exchange
            // is never the right instrument here, whatever its name similarity score.
            boolean isIndianListing = symbol != null
                    && (symbol.toUpperCase(Locale.ROOT).endsWith(".NS") || symbol.toUpperCase(Locale.ROOT).endsWith(".BO"));
            if (isIndianListing && namesLikelyMatch(instrumentName, suggestion.name())) return symbol;
        }
        return null;
    }

    /** An INR pair ({@code BTC-INR}) only: a USD pair would value an INR statement's holding in the
     *  wrong currency. Matches on the printed symbol when there is one, else on the coin's name. */
    private static String resolveCryptoPair(String instrumentName, String symbol, List<SymbolSuggestion> suggestions) {
        for (SymbolSuggestion suggestion : suggestions) {
            String pair = suggestion.symbol();
            if (pair == null || !pair.toUpperCase(Locale.ROOT).endsWith("-INR")) continue;
            String base = pair.substring(0, pair.length() - "-INR".length());
            boolean symbolMatches = symbol != null && base.equalsIgnoreCase(symbol.trim());
            if (symbolMatches || namesLikelyMatch(instrumentName, suggestion.name())) return pair;
        }
        return null;
    }

    /** The AMFI scheme code ({@code MF:<code>}) only when exactly one scheme matches the fund's name —
     *  Direct/Regular and Growth/IDCW variants share most of their words, so a guess between several
     *  would be a coin flip; the user picks the right plan in the form instead. */
    private static String resolveFundSchemeCode(String instrumentName, List<SymbolSuggestion> suggestions) {
        List<String> candidates = suggestions.stream()
                .filter(sg -> sg.symbol() != null && sg.symbol().startsWith("MF:"))
                .filter(sg -> namesLikelyMatch(instrumentName, sg.name()))
                .map(SymbolSuggestion::symbol)
                .distinct()
                .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
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
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_INVESTED_VALUE = "investedValue";
    private static final String FIELD_CURRENT_VALUE = "currentValue";
    private static final String FIELD_BROKER = "broker";
    private static final String FIELD_SYMBOL = "symbol";
    private static final String FIELD_ACCOUNT = "account";
    private static final String ACCOUNT_DESCRIPTION = "Name of the broker, depository participant or fund house this line is held through, only when the statement names one per line — never an account, client or folio number";
    private static final String SYMBOL_DESCRIPTION = "The ticker/symbol printed for this line (e.g. BTC), only if the statement prints one";
    private static final String INSTRUMENT_NAME_DESCRIPTION = "Company or instrument name exactly as printed";
    private static final String ISIN_DESCRIPTION = "The 12-character ISIN for this instrument";

    private static ObjectNode toolDefinition(ObjectMapper json) {
        ObjectNode holdingSchema = json.createObjectNode();
        holdingSchema.put(SCHEMA_TYPE, SCHEMA_OBJECT);
        ObjectNode holdingProps = json.createObjectNode();
        holdingProps.set(FIELD_INSTRUMENT_NAME, textProp(json, INSTRUMENT_NAME_DESCRIPTION));
        holdingProps.set(FIELD_ISIN, textProp(json, ISIN_DESCRIPTION + " — required for a stock or ETF, omit for a mutual fund that shows none"));
        ObjectNode kind = textProp(json, "What kind of instrument this line is");
        kind.set("enum", kindEnum(json));
        holdingProps.set(FIELD_KIND, kind);
        holdingProps.set(FIELD_SYMBOL, textProp(json, SYMBOL_DESCRIPTION));
        holdingProps.set(FIELD_ACCOUNT, textProp(json, ACCOUNT_DESCRIPTION));
        holdingProps.set(FIELD_QUANTITY, numberProp(json, "Units held"));
        holdingProps.set(FIELD_INVESTED_VALUE, numberProp(json, "Total amount invested in this line, if the statement shows it"));
        holdingProps.set(FIELD_CURRENT_VALUE, numberProp(json, "Current market value of this line, if the statement shows it"));
        holdingProps.set("averageCostPerUnit", numberProp(json, "Average cost per unit — omit entirely if the statement doesn't show one"));
        ObjectNode depository = textProp(json, "Which depository this line is held under, if shown");
        depository.set("enum", json.createArrayNode().add("NSDL").add("CDSL").add("UNKNOWN"));
        holdingProps.set("depository", depository);
        holdingSchema.set(SCHEMA_PROPERTIES, holdingProps);
        holdingSchema.set(SCHEMA_REQUIRED, json.createArrayNode().add(FIELD_INSTRUMENT_NAME).add(FIELD_KIND).add(FIELD_QUANTITY));

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
        txnProps.set(FIELD_ISIN, textProp(json, ISIN_DESCRIPTION + " — omit for a mutual fund, crypto asset or commodity that shows none"));
        ObjectNode txnKind = textProp(json, "What kind of instrument this line is");
        txnKind.set("enum", kindEnum(json));
        txnProps.set(FIELD_KIND, txnKind);
        txnProps.set(FIELD_SYMBOL, textProp(json, SYMBOL_DESCRIPTION));
        txnProps.set(FIELD_ACCOUNT, textProp(json, ACCOUNT_DESCRIPTION));
        txnProps.set(FIELD_QUANTITY, numberProp(json, "Omit if this line has no quantity (e.g. INTEREST)"));
        txnProps.set("amount", numberProp(json, "Omit if this line has no amount (e.g. SPLIT)"));
        txnProps.set("notes", textProp(json, "Any short free-text note from the statement line, if present"));
        txnSchema.set(SCHEMA_PROPERTIES, txnProps);
        txnSchema.set(SCHEMA_REQUIRED, json.createArrayNode().add("date").add(SCHEMA_TYPE).add(FIELD_INSTRUMENT_NAME).add(FIELD_KIND));

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
        topProps.set(FIELD_BROKER, textProp(json, "Broker or investing platform the statement comes from, only if the document names it"));
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

    private static ArrayNode kindEnum(ObjectMapper json) {
        ArrayNode values = json.createArrayNode();
        for (InstrumentKind kind : InstrumentKind.values()) values.add(kind.name());
        return values;
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
        String broker = blankToNull(toolInput.path(FIELD_BROKER).asText(null));
        return new StatementExtractionResponse(statementType, StatementRows.consolidate(holdingRows), List.of(), txnRows,
                statementDate, warnings, broker);
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
        InstrumentKind kind = parseKind(row.path(FIELD_KIND).asText(null), isin);
        JsonNode quantityNode = row.path(FIELD_QUANTITY);
        boolean identified = name != null && (isin != null || (kind != null && kind.identifiedByName()));
        if (!identified || !quantityNode.isNumber() || quantityNode.decimalValue().signum() <= 0) {
            warnings.add("Skipped a holding row with an incomplete name, ISIN, or quantity" + (name != null ? " (" + name + ")" : ""));
            return;
        }
        BigDecimal quantity = quantityNode.decimalValue();
        BigDecimal invested = decimalOrNull(row.path(FIELD_INVESTED_VALUE));
        BigDecimal current = decimalOrNull(row.path(FIELD_CURRENT_VALUE));
        BigDecimal avgCost = decimalOrNull(row.path("averageCostPerUnit"));
        if (avgCost == null) avgCost = StatementRows.averageCost(invested, quantity);
        String depository = blankToNull(row.path("depository").asText(null));
        String normalisedIsin = isin == null ? null : isin.toUpperCase(Locale.ROOT);
        holdingRows.add(new ExtractedHolding(name, normalisedIsin, quantity, avgCost, depository, null, null,
                kind, invested, current, blankToNull(row.path(FIELD_SYMBOL).asText(null)),
                blankToNull(row.path(FIELD_ACCOUNT).asText(null))));
    }

    /** A row with no kind but an ISIN is a stock (what every row was before kinds existed); one with
     *  neither is unusable and comes back null so the caller skips it. */
    private static InstrumentKind parseKind(String value, String isin) {
        if (value != null) {
            try { return InstrumentKind.valueOf(value); } catch (IllegalArgumentException e) { /* fall through */ }
        }
        return isin == null ? null : InstrumentKind.STOCK;
    }

    private static BigDecimal decimalOrNull(JsonNode node) { return node.isNumber() ? node.decimalValue() : null; }

    private static void parseTransactionRow(JsonNode row, List<ExtractedTransaction> txnRows, List<String> warnings) {
        String date = blankToNull(row.path("date").asText(null));
        String name = blankToNull(row.path(FIELD_INSTRUMENT_NAME).asText(null));
        String isin = blankToNull(row.path(FIELD_ISIN).asText(null));
        InstrumentKind kind = parseKind(row.path(FIELD_KIND).asText(null), isin);
        TransactionType type = parseTransactionType(row.path(SCHEMA_TYPE).asText(null));
        boolean identified = name != null && (isin != null || (kind != null && kind.identifiedByName()));
        if (date == null || !identified || type == null) {
            warnings.add("Skipped a transaction row with an incomplete date, type, name, or ISIN" + (name != null ? " (" + name + ")" : ""));
            return;
        }
        BigDecimal quantity = decimalOrNull(row.path(FIELD_QUANTITY));
        BigDecimal amount = decimalOrNull(row.path("amount"));
        String notes = blankToNull(row.path("notes").asText(null));
        String normalisedIsin = isin == null ? null : isin.toUpperCase(Locale.ROOT);
        txnRows.add(new ExtractedTransaction(date, type, name, normalisedIsin, quantity, amount, notes, null,
                kind, blankToNull(row.path(FIELD_SYMBOL).asText(null)), null, blankToNull(row.path(FIELD_ACCOUNT).asText(null))));
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
