package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.service.PendingBrokerHoldingService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Resolving a broker-sync instrument the daily sync couldn't auto-apply — see {@code
 *  PendingBrokerHoldingService} and {@code BrokerHoldingSyncConsumer}. Surfaced to the user via
 *  the Action Centre ({@code GET /api/insights} already includes these as action items), not its
 *  own listing endpoint. */
@RestController
@RequestMapping("/api/pending-broker-holdings")
public class PendingBrokerHoldingController {
    private final PendingBrokerHoldingService pending;

    public PendingBrokerHoldingController(PendingBrokerHoldingService pending) {
        this.pending = pending;
    }

    @PostMapping("/{id}/create")
    HoldingResponse create(@PathVariable String id, @RequestBody CreateRequest body) {
        return pending.createFrom(id, body.categoryId());
    }

    @PostMapping("/{id}/link")
    HoldingResponse link(@PathVariable String id) {
        return pending.linkToExisting(id);
    }

    public record CreateRequest(String categoryId) { }
}
