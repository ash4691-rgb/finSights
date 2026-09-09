package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.HotPickResponse;
import com.finsights.portfolio.dto.InsightsResponse;
import com.finsights.portfolio.dto.PortfolioTimelineResponse;
import com.finsights.portfolio.service.HotPicksService;
import com.finsights.portfolio.service.InsightsService;
import com.finsights.portfolio.service.PortfolioSnapshotService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/insights")
public class InsightsController {
    private final InsightsService insights;
    private final HotPicksService hotPicks;
    private final PortfolioSnapshotService portfolioSnapshots;

    public InsightsController(InsightsService insights, HotPicksService hotPicks,
                             PortfolioSnapshotService portfolioSnapshots) {
        this.insights = insights;
        this.hotPicks = hotPicks;
        this.portfolioSnapshots = portfolioSnapshots;
    }

    @GetMapping
    InsightsResponse insights(@RequestParam(required = false) String currency) {
        portfolioSnapshots.captureCurrentWeek();
        return insights.insights(currency);
    }

    @GetMapping("/hot-picks")
    List<HotPickResponse> hotPicks(@RequestParam(required = false) String currency) { return hotPicks.hotPicks(currency); }

    @GetMapping("/timeline")
    PortfolioTimelineResponse timeline(@RequestParam(required = false) String currency) {
        portfolioSnapshots.captureCurrentWeek();
        return portfolioSnapshots.timeline(currency);
    }
}
