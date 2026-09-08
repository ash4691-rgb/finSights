package com.finsights.portfolio.api;

import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.ImportResultResponse;
import com.finsights.portfolio.dto.TransactionRequest;
import com.finsights.portfolio.dto.TransactionResponse;
import com.finsights.portfolio.service.CsvService;
import com.finsights.portfolio.service.TransactionService;
import com.finsights.portfolio.service.XmlService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private final TransactionService transactions;
    private final CsvService csv;
    private final XmlService xml;

    public TransactionController(TransactionService transactions, CsvService csv, XmlService xml) {
        this.transactions = transactions;
        this.csv = csv;
        this.xml = xml;
    }

    @GetMapping
    List<TransactionResponse> list(
            @RequestParam(required = false) String holdingId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) String broker,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String currency) {
        return transactions.list(holdingId, type, broker, from, to, currency);
    }

    /** {@code ?format=csv} (default) or {@code ?format=xml}. See CsvService / XmlService for the column list. */
    @GetMapping("/export")
    ResponseEntity<String> export(@RequestParam(required = false, defaultValue = "csv") String format) {
        boolean isXml = "xml".equalsIgnoreCase(format);
        String body = isXml ? xml.export() : csv.export();
        String filename = "finsights-transactions." + (isXml ? "xml" : "csv");
        MediaType type = isXml ? MediaType.APPLICATION_XML : MediaType.parseMediaType("text/csv");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(type)
                .body(body);
    }

    /** Detects CSV vs XML from the request's Content-Type (falls back to CSV). Bad rows are reported, not fatal. */
    @PostMapping(value = "/import", consumes = {
            MediaType.TEXT_PLAIN_VALUE, "text/csv", MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    ImportResultResponse importFile(@RequestBody String body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        boolean isXml = contentType != null
                && (contentType.contains("xml") || body.stripLeading().startsWith("<"));
        return isXml ? xml.importXml(body) : csv.importCsv(body);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TransactionResponse create(@Valid @RequestBody TransactionRequest request) { return transactions.create(request); }

    @PutMapping("/{id}")
    TransactionResponse update(@PathVariable String id, @Valid @RequestBody TransactionRequest request) {
        return transactions.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) { transactions.delete(id); }
}
