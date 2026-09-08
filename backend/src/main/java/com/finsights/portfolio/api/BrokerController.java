package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.BrokersResponse;
import com.finsights.portfolio.service.BrokerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/brokers")
public class BrokerController {
    private final BrokerService brokers;

    public BrokerController(BrokerService brokers) { this.brokers = brokers; }

    @GetMapping
    BrokersResponse overview(@RequestParam(required = false) String currency) { return brokers.overview(currency); }
}
