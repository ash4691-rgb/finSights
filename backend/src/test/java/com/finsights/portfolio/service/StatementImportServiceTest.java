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
    void rejectsAnOversizedXlsxUploadWithTheSpreadsheetLimitNotTheTextOne() {
        byte[] tooBig = new byte[StatementImportService.MAX_SPREADSHEET_BYTES + 1];
        System.arraycopy(new byte[] { 0x50, 0x4B, 0x03, 0x04 }, 0, tooBig, 0, 4);
        assertThatThrownBy(() -> service.extract(tooBig, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("larger than");
        // Rejected before it ever reaches the user/rate-limit lookup, same as an oversized PDF.
        org.mockito.Mockito.verifyNoInteractions(rateLimiter, currentUser);
    }

    @Test
    void rejectsAnOversizedXlsUploadWithTheSpreadsheetLimitNotTheTextOne() {
        byte[] tooBig = new byte[StatementImportService.MAX_SPREADSHEET_BYTES + 1];
        System.arraycopy(new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 }, 0, tooBig, 0, 8);
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
                new StatementExtractionResponse(StatementType.HOLDINGS, List.of(row), List.of(), List.of(), null, List.of()), "u1", "Zerodha");

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
                new StatementExtractionResponse(StatementType.HOLDINGS, List.of(row), List.of(), List.of(), null, List.of()), "u1", "Zerodha");

        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().get(0).matchedHoldingId()).isNull();
        assertThat(result.toRemove()).isEmpty();
    }

    @Test
    void matchAgainstExistingFlagsABrokerHoldingNotSeenInAHoldingsStatementAsMissing() {
        Holding stale = new Holding();
        stale.setName("Old Fund House Units");
        stale.setBroker("Groww");
        stale.setDescription("ISIN INE999Z99999");
        stale.setQuantity(new BigDecimal("12.5"));
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(stale));

        StatementExtractionResponse result = service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.HOLDINGS, List.of(), List.of(), List.of(), null, List.of()), "u1", "Groww");

        assertThat(result.toRemove()).hasSize(1);
        assertThat(result.toRemove().get(0).name()).isEqualTo("Old Fund House Units");
        assertThat(result.toRemove().get(0).broker()).isEqualTo("Groww");
        assertThat(result.toRemove().get(0).quantity()).isEqualByComparingTo("12.5");
    }

    @Test
    void matchAgainstExistingNeverFlagsRemovalsFromATransactionsOnlyStatement() {
        Holding stale = new Holding();
        stale.setName("Old Fund House Units");
        stale.setBroker("Groww");
        stale.setDescription("ISIN INE999Z99999");
        stale.setQuantity(new BigDecimal("12.5"));
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(stale));

        // A transactions-only statement says nothing about what else is or isn't still held — it
        // must never generate a removal candidate just because it didn't mention this holding.
        StatementExtractionResponse result = service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.TRANSACTIONS, List.of(), List.of(), List.of(), null, List.of()), "u1", "Groww");

        assertThat(result.toRemove()).isEmpty();
    }

    @Test
    void matchAgainstExistingNeverExpectsAHoldingWithNoUnitsOnAStatement() {
        Holding manual = new Holding();
        manual.setName("Manually added FD");
        manual.setBroker("HDFC Bank");
        manual.setDescription("Matures next year");
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(List.of(manual));

        StatementExtractionResponse result = service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.HOLDINGS, List.of(), List.of(), List.of(), null, List.of()), "u1", "HDFC Bank");

        // A fixed deposit has no units and never appears on a broker statement, so its absence from
        // this one says nothing about whether it's still held.
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
                new StatementExtractionResponse(StatementType.TRANSACTIONS, List.of(), List.of(), List.of(txn), null, List.of()), "u1", "Zerodha");

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().get(0).matchedHoldingId()).isEqualTo("h-existing-1");
        assertThat(result.warnings()).isEmpty();
    }

    private static ExtractedTransaction txn(String name, String isin) {
        return new ExtractedTransaction("2025-04-03", TransactionType.BUY, name, isin, BigDecimal.TEN,
                new BigDecimal("1500"), null, null);
    }

    private static Holding holdingNamed(String id, String name) {
        Holding h = new Holding();
        h.setName(name);
        h.setBroker("Zerodha");
        org.springframework.test.util.ReflectionTestUtils.setField(h, "id", id);
        return h;
    }

    private StatementExtractionResponse matchTransactions(List<Holding> existing, List<ExtractedTransaction> rows) {
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u1")).thenReturn(existing);
        return service.matchAgainstExisting(
                new StatementExtractionResponse(StatementType.TRANSACTIONS, List.of(), List.of(), rows, null, List.of()), "u1", "Zerodha");
    }

    @Test
    void matchAgainstExistingKeepsAnUnmatchedTransactionForManualHoldingPickAndSummarisesItOnce() {
        StatementExtractionResponse result = matchTransactions(List.of(), List.of(
                txn("Infosys", "INE009A01021"), txn("Infosys", "INE009A01021"), txn("Tata Steel", "INE081A01020")));

        assertThat(result.transactions()).hasSize(3);
        assertThat(result.transactions()).allSatisfy(t -> assertThat(t.matchedHoldingId()).isNull());
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("3 transactions across 2 instruments")
                .contains("Infosys").contains("Tata Steel").doesNotContain("more");
    }

    @Test
    void matchAgainstExistingMatchesAnUntaggedHoldingByUniqueName() {
        StatementExtractionResponse result = matchTransactions(
                List.of(holdingNamed("h-1", "Life Insurance Corporation"), holdingNamed("h-2", "Tata Steel")),
                List.of(txn("TATA STEEL LTD", "INE081A01020")));

        assertThat(result.transactions().get(0).matchedHoldingId()).isEqualTo("h-2");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void matchAgainstExistingLeavesAnAmbiguousNameMatchForTheUserToPick() {
        StatementExtractionResponse result = matchTransactions(
                List.of(holdingNamed("h-1", "Tata Steel"), holdingNamed("h-2", "Tata Steel (second broker)")),
                List.of(txn("Tata Steel", "INE081A01020")));

        assertThat(result.transactions().get(0).matchedHoldingId()).isNull();
        assertThat(result.warnings()).hasSize(1);
    }

    @Test
    void unmatchedWarningUsesSingularWordsAndTruncatesLongInstrumentLists() {
        assertThat(StatementImportService.unmatchedTransactionWarning(java.util.Map.of())).isEmpty();
        assertThat(StatementImportService.unmatchedTransactionWarning(java.util.Map.of("Infosys", 1)).orElseThrow())
                .startsWith("1 transaction across 1 instrument (Infosys)");

        java.util.Map<String, Integer> many = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 7; i++) many.put("Co" + i, 2);
        assertThat(StatementImportService.unmatchedTransactionWarning(many).orElseThrow())
                .startsWith("14 transactions across 7 instruments (Co1, Co2, Co3, Co4, Co5 and 2 more)");
    }

    /** Synthetic, anonymised stand-in for a Groww stock order-history CSV: a preamble with a
     *  client name/code and a blank-line gap, then the header and rows (Value is the order total). */
    static final String GROWW_STYLE_CSV = String.join("\n",
            "Name,Test User,,,,,,,,",
            "Unique Client Code,0000000000,,,,,,,,",
            ",,,,,,,,,",
            "Order history for stocks from 01-07-2020 to 21-10-2026,,,,,,,,,",
            ",,,,,,,,,",
            "Stock name,Symbol,ISIN,Type,Quantity,Value,Exchange,Exchange Order Id,Execution date and time,Order status",
            "EXAMPLE CORP LTD,EXMPL,INE000A01010,BUY,6,4882.32,NSE,1000000000000001,03-04-2025 03:13 PM,Executed",
            "EXAMPLE ETF,EXETF,INF000A01010,SELL,144,23254.56,NSE,1000000000000002,03-04-2025 03:14 PM,Executed");

    @Test
    void growwStyleCsvIsSentToTheModelWholeAndItsRowsSurviveParsingAndMatching() {
        ObjectNode body = StatementImportService.buildTextRequestBody(json, "claude-sonnet-5", GROWW_STYLE_CSV);
        String prompt = body.path("messages").path(0).path("content").path(0).path("text").asText();
        assertThat(prompt).contains("Stock name,Symbol,ISIN,Type,Quantity,Value").contains("INF000A01010");

        // What the model would hand back for that file: both rows, no price per unit anywhere.
        ObjectNode toolInput = json.createObjectNode();
        toolInput.put("statementType", "TRANSACTIONS");
        var rows = toolInput.putArray("transactions");
        rows.addObject().put("date", "2025-04-03").put("type", "BUY").put("instrumentName", "EXAMPLE CORP LTD")
                .put("isin", "INE000A01010").put("quantity", 6).put("amount", 4882.32);
        rows.addObject().put("date", "2025-04-03").put("type", "SELL").put("instrumentName", "EXAMPLE ETF")
                .put("isin", "INF000A01010").put("quantity", 144).put("amount", 23254.56);
        ObjectNode response = json.createObjectNode();
        response.putArray("content").addObject().put("type", "tool_use").put("name", "extract_statement").set("input", toolInput);

        StatementExtractionResponse parsed = StatementImportService.parseExtraction(response);
        assertThat(parsed.transactions()).hasSize(2);

        // A first-time import: no holdings exist yet. Rows must still reach the user.
        StatementExtractionResponse matched = matchTransactions(List.of(), parsed.transactions());
        assertThat(matched.transactions()).hasSize(2);
        assertThat(matched.warnings()).hasSize(1);
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
    void spreadsheetToTextFlattensASingleSheetToCommaJoinedRows() throws Exception {
        byte[] xlsx = xlsxWorkbook(sheets -> {
            var sheet = sheets.createSheet("Holdings");
            writeRow(sheet, 0, "Name", "ISIN", "Quantity");
            writeRow(sheet, 1, "Infosys", "INE009A01021", "10");
        });

        String text = StatementImportService.spreadsheetToText(xlsx);

        assertThat(text).isEqualTo("Sheet: Holdings\nName,ISIN,Quantity\nInfosys,INE009A01021,10\n");
    }

    @Test
    void spreadsheetToTextLabelsEachNonEmptySheetAndSkipsEmptyOnes() throws Exception {
        byte[] xlsx = xlsxWorkbook(wb -> {
            writeRow(wb.createSheet("Holdings"), 0, "Infosys");
            wb.createSheet("Empty");
            writeRow(wb.createSheet("Transactions"), 0, "BUY");
        });

        String text = StatementImportService.spreadsheetToText(xlsx);

        assertThat(text).contains("Sheet: Holdings\nInfosys\n");
        assertThat(text).contains("Sheet: Transactions\nBUY\n");
        assertThat(text).doesNotContain("Sheet: Empty");
    }

    @Test
    void spreadsheetToTextStripsCommasFromCellValuesSoRowsStayAligned() throws Exception {
        byte[] xlsx = xlsxWorkbook(wb -> writeRow(wb.createSheet("Holdings"), 0, "Reliance, Industries", "10"));

        String text = StatementImportService.spreadsheetToText(xlsx);

        assertThat(text).isEqualTo("Sheet: Holdings\nReliance  Industries,10\n");
    }

    @Test
    void spreadsheetToTextTruncatesAfterTheRowCapAndNotesIt() throws Exception {
        byte[] xlsx = xlsxWorkbook(wb -> {
            var sheet = wb.createSheet("Holdings");
            for (int i = 0; i < StatementImportService.MAX_SPREADSHEET_ROWS + 5; i++) {
                writeRow(sheet, i, "Row " + i);
            }
        });

        String text = StatementImportService.spreadsheetToText(xlsx);

        long rowLines = text.lines().filter(l -> l.startsWith("Row ")).count();
        assertThat(rowLines).isEqualTo(StatementImportService.MAX_SPREADSHEET_ROWS);
        assertThat(text).contains("[Statement truncated after " + StatementImportService.MAX_SPREADSHEET_ROWS + " rows]");
    }

    @Test
    void spreadsheetToTextReadsALegacyXlsWorkbookToo() throws Exception {
        byte[] xls;
        try (org.apache.poi.hssf.usermodel.HSSFWorkbook wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook()) {
            writeRow(wb.createSheet("Holdings"), 0, "Infosys", "10");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            xls = out.toByteArray();
        }

        String text = StatementImportService.spreadsheetToText(xls);

        assertThat(text).isEqualTo("Sheet: Holdings\nInfosys,10\n");
    }

    @Test
    void aGrowwStyleOrderHistoryWithThousandsOfRowsIsSplitIntoManyExtractableChunks() throws Exception {
        // Mirrors the real-world shape that used to produce "Nothing we could extract": a few
        // preamble rows (name/account), a blank separator, a header row, then thousands of
        // one-line-per-order rows — all synthetic/anonymized here, never real statement data.
        byte[] xlsx = xlsxWorkbook(wb -> {
            var sheet = wb.createSheet("Sheet1");
            writeRow(sheet, 0, "Name", "Demo User");
            writeRow(sheet, 1, "Unique Client Code", "0000000000");
            writeRow(sheet, 2);
            writeRow(sheet, 3, "Order history for stocks from 01-01-2020 to 01-01-2026");
            writeRow(sheet, 4);
            writeRow(sheet, 5, "Stock name", "Symbol", "ISIN", "Type", "Quantity", "Value", "Exchange",
                    "Exchange Order Id", "Execution date and time", "Order status");
            for (int i = 0; i < 2199; i++) {
                writeRow(sheet, 6 + i, "Demo Company " + i, "SYM" + i, "INE000A0" + String.format("%04d", i),
                        i % 2 == 0 ? "BUY" : "SELL", "10", "1000", "NSE", "ORD" + i,
                        "13-09-2021 02:13 PM", "Executed");
            }
        });

        String flattened = StatementImportService.spreadsheetToText(xlsx);
        List<String> chunks = StatementImportService.chunkFlattenedSpreadsheet(
                flattened, StatementImportService.MAX_SPREADSHEET_ROWS_PER_CALL);

        // 2205 total rows (6 preamble/header + 2199 data rows) split at 150 rows per chunk, so no
        // single extraction call is ever asked to list thousands of rows in one response.
        assertThat(chunks.size()).isGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk).startsWith("Sheet: Sheet1\n");
            long dataRows = chunk.lines().filter(l -> l.startsWith("Demo Company")).count();
            assertThat(dataRows).isLessThanOrEqualTo(StatementImportService.MAX_SPREADSHEET_ROWS_PER_CALL);
        }
        // Rejoining every chunk's data rows accounts for every order exactly once — chunking never
        // drops or duplicates a row.
        long totalDataRows = chunks.stream().flatMap(String::lines).filter(l -> l.startsWith("Demo Company")).count();
        assertThat(totalDataRows).isEqualTo(2199);
    }

    @Test
    void spreadsheetToTextRejectsBytesThatLookLikeAZipButArentAWorkbook() {
        byte[] notReallyXlsx = { 0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4 };
        assertThatThrownBy(() -> StatementImportService.spreadsheetToText(notReallyXlsx))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Couldn't open");
    }

    @Test
    void chunkFlattenedSpreadsheetLeavesAShortStatementAsOneChunk() {
        String text = "Sheet: Holdings\nInfosys,INE009A01021,10\nTCS,INE467B01029,5\n";

        List<String> chunks = StatementImportService.chunkFlattenedSpreadsheet(text, 150);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(text);
    }

    @Test
    void chunkFlattenedSpreadsheetSplitsALongSheetAtTheRowCapAndRepeatsTheSheetHeader() {
        StringBuilder text = new StringBuilder("Sheet: Orders\n");
        for (int i = 0; i < 5; i++) text.append("Row ").append(i).append('\n');

        List<String> chunks = StatementImportService.chunkFlattenedSpreadsheet(text.toString(), 2);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).isEqualTo("Sheet: Orders\nRow 0\nRow 1\n");
        assertThat(chunks.get(1)).isEqualTo("Sheet: Orders\nRow 2\nRow 3\n");
        assertThat(chunks.get(2)).isEqualTo("Sheet: Orders\nRow 4\n");
    }

    @Test
    void chunkFlattenedSpreadsheetKeepsEachSheetsOwnRowCountIndependent() {
        String text = "Sheet: Holdings\nRow 0\nRow 1\nRow 2\nSheet: Transactions\nRow A\nRow B\n";

        List<String> chunks = StatementImportService.chunkFlattenedSpreadsheet(text, 2);

        assertThat(chunks).containsExactly(
                "Sheet: Holdings\nRow 0\nRow 1\n",
                "Sheet: Holdings\nRow 2\n",
                "Sheet: Transactions\nRow A\nRow B\n");
    }

    @Test
    void chunkFlattenedSpreadsheetKeepsATruncationNoticeAttachedToTheLastChunkRatherThanStartingANewOne() {
        String text = "Sheet: Orders\nRow 0\nRow 1\n[Statement truncated after 2 rows]\n";

        List<String> chunks = StatementImportService.chunkFlattenedSpreadsheet(text, 2);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(text);
    }

    @Test
    void mergeExtractionsReturnsTheSingleResponseUnchangedWhenThereIsOnlyOneChunk() {
        ExtractedHolding row = new ExtractedHolding("Infosys", "INE009A01021", BigDecimal.TEN, null, null, null, null);
        StatementExtractionResponse only = new StatementExtractionResponse(
                StatementType.HOLDINGS, List.of(row), List.of(), List.of(), "2026-09-30", List.of("a warning"));

        StatementExtractionResponse merged = StatementImportService.mergeExtractions(List.of(only));

        assertThat(merged).isSameAs(only);
    }

    @Test
    void mergeExtractionsCombinesTransactionsAndWarningsAcrossChunksAndDedupesHoldingsByIsin() {
        ExtractedHolding infosysFirstSighting = new ExtractedHolding("Infosys", "INE009A01021", BigDecimal.TEN, null, null, null, null);
        ExtractedHolding infosysSecondSighting = new ExtractedHolding("Infosys", "INE009A01021", BigDecimal.ONE, null, null, null, null);
        ExtractedTransaction txn1 = new ExtractedTransaction("2026-01-01", TransactionType.BUY, "Infosys", "INE009A01021", BigDecimal.TEN, BigDecimal.TEN, null, null);
        ExtractedTransaction txn2 = new ExtractedTransaction("2026-01-02", TransactionType.SELL, "TCS", "INE467B01029", BigDecimal.ONE, BigDecimal.ONE, null, null);

        StatementExtractionResponse chunk1 = new StatementExtractionResponse(
                StatementType.TRANSACTIONS, List.of(infosysFirstSighting), List.of(), List.of(txn1), null, List.of("warning 1"));
        StatementExtractionResponse chunk2 = new StatementExtractionResponse(
                StatementType.TRANSACTIONS, List.of(infosysSecondSighting), List.of(), List.of(txn2), "2026-02-01", List.of("warning 2"));

        StatementExtractionResponse merged = StatementImportService.mergeExtractions(List.of(chunk1, chunk2));

        assertThat(merged.statementType()).isEqualTo(StatementType.TRANSACTIONS);
        // Same ISIN seen in two chunks — kept once, from whichever chunk reported it first.
        assertThat(merged.holdings()).hasSize(1);
        assertThat(merged.holdings().get(0).quantity()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(merged.transactions()).containsExactly(txn1, txn2);
        assertThat(merged.warnings()).containsExactly("warning 1", "warning 2");
        // The first chunk had no statement date — the second chunk's isn't lost because of that.
        assertThat(merged.statementDate()).isEqualTo("2026-02-01");
    }

    @Test
    void mergeExtractionsFallsBackToMixedWhenChunksDisagreeOnStatementType() {
        StatementExtractionResponse holdingsChunk = new StatementExtractionResponse(
                StatementType.HOLDINGS, List.of(), List.of(), List.of(), null, List.of());
        StatementExtractionResponse txnChunk = new StatementExtractionResponse(
                StatementType.TRANSACTIONS, List.of(), List.of(), List.of(), null, List.of());

        StatementExtractionResponse merged = StatementImportService.mergeExtractions(List.of(holdingsChunk, txnChunk));

        assertThat(merged.statementType()).isEqualTo(StatementType.MIXED);
    }

    private static byte[] xlsxWorkbook(java.util.function.Consumer<org.apache.poi.ss.usermodel.Workbook> build) throws IOException {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            build.accept(wb);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void writeRow(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, String... cells) {
        org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex);
        for (int c = 0; c < cells.length; c++) row.createCell(c).setCellValue(cells[c]);
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

        StatementExtractionResponse result = StatementImportService.parseExtraction(response);

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

        StatementExtractionResponse result = StatementImportService.parseExtraction(response);

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

        StatementExtractionResponse result = StatementImportService.parseExtraction(response);

        assertThat(result.holdings()).isEmpty();
        assertThat(result.transactions()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void unrecognizedStatementTypeFallsBackToHoldingsRatherThanThrowing() throws Exception {
        JsonNode response = json.readTree("""
                {"content": [{"type": "tool_use", "name": "extract_statement", "input": {"statementType": "SOMETHING_ELSE"}}]}
                """);

        StatementExtractionResponse result = StatementImportService.parseExtraction(response);

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
