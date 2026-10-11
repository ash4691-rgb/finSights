package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.CasExtractionResponse;
import com.finsights.portfolio.service.CasImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/holdings/import")
public class CasImportController {
    private final CasImportService casImport;

    public CasImportController(CasImportService casImport) { this.casImport = casImport; }

    /**
     * Uploads a CDSL/NSDL Consolidated Account Statement PDF and extracts candidate equity
     * holdings from it. This endpoint never writes a holding — see {@link CasImportService}'s
     * class comment. The caller takes each row back through the ordinary {@code POST /api/holdings}
     * once the user has reviewed and (optionally) edited it.
     *
     * <p>{@code X-Document-Password} carries the CAS's own PDF password (usually the holder's
     * PAN) — almost every CAS is password-protected, and Claude's PDF support rejects an
     * encrypted document outright, so this is decrypted locally before ever reaching the model.
     */
    @PostMapping(value = "/cas", consumes = "application/pdf")
    CasExtractionResponse importCas(@RequestBody byte[] pdf,
            @RequestHeader(value = "X-Document-Password", required = false) String password) {
        return casImport.extract(pdf, password);
    }
}
