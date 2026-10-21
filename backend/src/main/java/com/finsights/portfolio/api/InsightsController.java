package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.ActionDismissalRequest;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.dto.DeferredActionResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.MovementThresholdRequest;
import com.finsights.portfolio.dto.MovementThresholdResponse;
import com.finsights.portfolio.dto.NewsItemResponse;
import com.finsights.portfolio.dto.PortfolioTimelineResponse;
import com.finsights.portfolio.dto.TopMoverResponse;
import com.finsights.portfolio.service.ActionCentreService;
import com.finsights.portfolio.service.ActionDismissalService;
import com.finsights.portfolio.service.HoldingService;
import com.finsights.portfolio.service.MovementThresholdService;
import com.finsights.portfolio.service.NewsService;
import com.finsights.portfolio.service.PortfolioSnapshotService;
import com.finsights.portfolio.service.TickerPreferenceService;
import com.finsights.portfolio.service.TopMoversService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signals' four sections (Tasks/Movers/Radar's News/Upcoming/Snoozed) are each their own GET here
 * — not one bundled response — specifically so a slow or failing one (most plausibly News, reading
 * an external-feed-backed cache) never blocks the others from rendering. Each is also independently
 * cheap: none but {@link #actions} needs the full holdings list, and nothing here recomputes the
 * byCategory/byBroker/byTag/etc. breakdowns the old bundled /api/insights used to — those were
 * computed on every Signals load but never rendered by this page (that belonged to the pre-rework
 * Insights page), each pass through HoldingService wasted doing it. See git history for the
 * removed InsightsService/InsightsResponse this replaced.
 */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {
    private final HoldingService holdings;
    private final ActionCentreService actionCentre;
    private final ActionDismissalService actionDismissals;
    private final NewsService news;
    private final TopMoversService topMovers;
    private final MovementThresholdService movementThresholds;
    private final PortfolioSnapshotService portfolioSnapshots;
    private final TickerPreferenceService tickerPreferences;

    public InsightsController(HoldingService holdings, ActionCentreService actionCentre, ActionDismissalService actionDismissals,
                             NewsService news, TopMoversService topMovers, MovementThresholdService movementThresholds,
                             PortfolioSnapshotService portfolioSnapshots, TickerPreferenceService tickerPreferences) {
        this.holdings = holdings;
        this.actionCentre = actionCentre;
        this.actionDismissals = actionDismissals;
        this.news = news;
        this.topMovers = topMovers;
        this.movementThresholds = movementThresholds;
        this.portfolioSnapshots = portfolioSnapshots;
        this.tickerPreferences = tickerPreferences;
    }

    /** Tasks (pending EMI/interest/broker-sync items) and Radar's "Worth a look" — the two filtered
     *  client-side from one list, same as before. The one section that needs amounts in the user's
     *  display currency, hence the only one that touches the full holdings list. Also where the
     *  weekly snapshot capture piggybacks (same best-effort, short-circuiting call /timeline already
     *  makes for Overview) — Signals is viewed often enough on its own that this shouldn't only ride
     *  on an Overview visit. */
    @GetMapping("/actions")
    List<ActionItemResponse> actions(@RequestParam(required = false) String currency) {
        portfolioSnapshots.captureCurrentUser(false);
        boolean converted = currency != null && !currency.isBlank();
        List<HoldingResponse> all = converted ? holdings.list(currency) : holdings.list();
        return actionCentre.actions(all, converted);
    }

    /** Radar's "Upcoming" — EMI/interest items further out than the due-soon window. */
    @GetMapping("/upcoming")
    List<ActionItemResponse> upcoming() { return actionCentre.upcoming(); }

    /** Radar's "Snoozed". */
    @GetMapping("/deferred")
    List<DeferredActionResponse> deferred() { return actionDismissals.deferredItems(); }

    /** Radar's "News". */
    @GetMapping("/news")
    List<NewsItemResponse> newsFeed() { return news.forUser(); }

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

    /** Un-snooze a deferred row (Signals' Radar) so it reappears right away. */
    @DeleteMapping("/actions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restoreAction(@RequestParam String key) {
        actionDismissals.restore(key);
    }

    /** Star a ticker: boosts its news to the top of this user's Radar. */
    @PostMapping("/news/{symbol}/star")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void starTicker(@PathVariable String symbol) {
        tickerPreferences.star(symbol);
    }

    @DeleteMapping("/news/{symbol}/star")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unstarTicker(@PathVariable String symbol) {
        tickerPreferences.unstar(symbol);
    }

    /** Cross: hides this ticker's news from this user's Radar — a personal preference, not a
     *  global unfollow (see TickerPreference's doc comment). */
    @PostMapping("/news/{symbol}/exclude")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void excludeTicker(@PathVariable String symbol) {
        tickerPreferences.exclude(symbol);
    }

    @DeleteMapping("/news/{symbol}/exclude")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restoreTicker(@PathVariable String symbol) {
        tickerPreferences.restore(symbol);
    }
}
