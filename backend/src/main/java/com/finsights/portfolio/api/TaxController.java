package com.finsights.portfolio.api;

import com.finsights.portfolio.domain.TaxClass;
import com.finsights.portfolio.dto.TaxReportResponse;
import com.finsights.portfolio.service.TaxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** India tax view. {@code fy} like "2025-26", defaulting to the current financial year. */
@RestController
@RequestMapping("/api/tax")
public class TaxController {
    private final TaxService tax;

    public TaxController(TaxService tax) {
        this.tax = tax;
    }

    /** {@code taxClass} null clears the override, so FinSights infers it again. */
    public record TaxClassRequest(TaxClass taxClass) { }

    @GetMapping
    TaxReportResponse report(@RequestParam(required = false) String fy) {
        return tax.report(fy);
    }

    @PutMapping("/holdings/{holdingId}/class")
    TaxReportResponse setTaxClass(@PathVariable String holdingId, @RequestBody TaxClassRequest request,
                                  @RequestParam(required = false) String fy) {
        tax.setTaxClass(holdingId, request.taxClass());
        return tax.report(fy);
    }
}
