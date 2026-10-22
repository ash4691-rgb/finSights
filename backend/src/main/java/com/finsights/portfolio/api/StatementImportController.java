package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.StatementExtractionResponse;
import com.finsights.portfolio.service.StatementImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statements")
public class StatementImportController {
    private final StatementImportService statementImport;

    public StatementImportController(StatementImportService statementImport) { this.statementImport = statementImport; }

    /**
     * Uploads one statement — a CAS PDF, a broker holding statement, or a broker transaction
     * statement, as a PDF, a spreadsheet (XLSX/XLS), or a machine-readable text file (CSV/TSV/XML)
     * — and extracts candidate holdings and/or transactions from it. This endpoint never writes
     * anything — see {@link
     * StatementImportService}'s class comment. The caller takes each row back through the
     * ordinary {@code POST /api/holdings} or {@code POST /api/transactions} once the user has
     * reviewed and (optionally) edited it.
     *
     * <p>{@code X-Document-Password} carries a PDF's own password (usually the holder's PAN for a
     * CAS) — almost every CAS is password-protected, and Claude's PDF support rejects an encrypted
     * document outright, so this is decrypted locally before ever reaching the model. Ignored for
     * a non-PDF upload.
     */
    @PostMapping(value = "/import", consumes = "application/octet-stream")
    StatementExtractionResponse importStatement(@RequestBody byte[] file,
            @RequestHeader(value = "X-Document-Password", required = false) String password) {
        return statementImport.extract(file, password);
    }
}
