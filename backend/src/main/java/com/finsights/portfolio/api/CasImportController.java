package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.CasExtractionResponse;
import com.finsights.portfolio.service.CasImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
     */
    @PostMapping(value = "/cas", consumes = "application/pdf")
    CasExtractionResponse importCas(@RequestBody byte[] pdf) {
        return casImport.extract(pdf);
    }
}
