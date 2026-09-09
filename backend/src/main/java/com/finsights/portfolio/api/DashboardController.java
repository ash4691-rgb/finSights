package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.DashboardResponse;
import com.finsights.portfolio.service.DashboardService;
import com.finsights.portfolio.service.PortfolioSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    private final PortfolioSnapshotService portfolioSnapshots;

    public DashboardController(DashboardService dashboard, PortfolioSnapshotService portfolioSnapshots) {
        this.dashboard = dashboard;
        this.portfolioSnapshots = portfolioSnapshots;
    }

    @GetMapping
    DashboardResponse summary(@RequestParam(required = false) String currency) {
        portfolioSnapshots.captureCurrentWeek();
        return dashboard.summary(currency);
    }
}
