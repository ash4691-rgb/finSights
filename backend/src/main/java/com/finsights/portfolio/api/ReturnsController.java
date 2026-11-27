package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.ReturnsResponse;
import com.finsights.portfolio.service.ReturnsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** XIRR returns for the signed-in user's assets. {@code currency} defaults to their base currency. */
@RestController
@RequestMapping("/api/returns")
public class ReturnsController {
    private final ReturnsService returns;

    public ReturnsController(ReturnsService returns) {
        this.returns = returns;
    }

    @GetMapping
    ReturnsResponse returns(@RequestParam(required = false) String currency) {
        return returns.returns(currency);
    }
}
