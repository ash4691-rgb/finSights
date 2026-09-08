package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.DashboardResponse;
import com.finsights.portfolio.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    public DashboardController(DashboardService dashboard) { this.dashboard = dashboard; }
    @GetMapping
    DashboardResponse summary(@RequestParam(required = false) String currency) { return dashboard.summary(currency); }
}

