package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.ActionDismissalRequest;
import com.finsights.portfolio.dto.InsightsResponse;
import com.finsights.portfolio.dto.MovementThresholdRequest;
import com.finsights.portfolio.dto.MovementThresholdResponse;
import com.finsights.portfolio.dto.PortfolioTimelineResponse;
import com.finsights.portfolio.dto.TopMoverResponse;
import com.finsights.portfolio.service.ActionDismissalService;
import com.finsights.portfolio.service.InsightsService;
import com.finsights.portfolio.service.MovementThresholdService;
import com.finsights.portfolio.service.PortfolioSnapshotService;
import com.finsights.portfolio.service.TopMoversService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/insights")
public class InsightsController {
    private final InsightsService insights;
    private final TopMoversService topMovers;
    private final MovementThresholdService movementThresholds;
    private final PortfolioSnapshotService portfolioSnapshots;
    private final ActionDismissalService actionDismissals;

    public InsightsController(InsightsService insights, TopMoversService topMovers, MovementThresholdService movementThresholds,
                             PortfolioSnapshotService portfolioSnapshots, ActionDismissalService actionDismissals) {
        this.insights = insights;
        this.topMovers = topMovers;
        this.movementThresholds = movementThresholds;
        this.portfolioSnapshots = portfolioSnapshots;
        this.actionDismissals = actionDismissals;
    }

    @GetMapping
    InsightsResponse insights(@RequestParam(required = false) String currency) {
        portfolioSnapshots.captureCurrentUser(false);
        return insights.insights(currency);
    }

    @GetMapping("/top-movers")
    List<TopMoverResponse> topMovers(@RequestParam(required = false) String currency) { return topMovers.topMovers(currency); }

    @GetMapping("/thresholds")
    MovementThresholdResponse thresholds() { return movementThresholds.get(); }

    @PutMapping("/thresholds")
    MovementThresholdResponse saveThresholds(@RequestBody MovementThresholdRequest request) { return movementThresholds.save(request); }

    @GetMapping("/timeline")
    PortfolioTimelineResponse timeline(@RequestParam(required = false) String currency) {
        portfolioSnapshots.captureCurrentUser(false);
        return portfolioSnapshots.timeline(currency);
    }

    /** Force a fresh capture for the current ISO week (the "Capture snapshot now" button). */
    @PostMapping("/timeline/capture")
    PortfolioTimelineResponse capture(@RequestParam(required = false) String currency) {
        portfolioSnapshots.captureCurrentUser(true);
        return portfolioSnapshots.timeline(currency);
    }

    /** Mark one Action Centre row done / deferred / deleted. */
    @PostMapping("/actions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void dismissAction(@Valid @RequestBody ActionDismissalRequest request) {
        actionDismissals.apply(request.key(), request.status(), request.deferDays());
    }
}
