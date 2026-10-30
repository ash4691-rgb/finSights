package com.finsights.portfolio.api;

import com.finsights.portfolio.service.ProvidentFundContributionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/provident-fund")
public class ProvidentFundController {

    private final ProvidentFundContributionService contributions;

    public ProvidentFundController(ProvidentFundContributionService contributions) {
        this.contributions = contributions;
    }

    /** Confirms one month's contribution ({@code month=2026-02}): logs the transaction and adds it to the balance. */
    @PostMapping("/{holdingId}/contributions/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirm(@PathVariable String holdingId, @RequestParam String month) {
        contributions.confirm(holdingId, month);
    }
}
