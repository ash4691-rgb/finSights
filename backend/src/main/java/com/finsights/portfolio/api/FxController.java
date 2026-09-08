package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.FxRatesResponse;
import com.finsights.portfolio.service.FxRateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fx-rates")
public class FxController {
    private final FxRateService fx;

    public FxController(FxRateService fx) { this.fx = fx; }

    @GetMapping
    FxRatesResponse rates() { return fx.rates(); }
}
