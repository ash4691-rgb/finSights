package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.MarketHistoryResponse;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.dto.SymbolSuggestion;
import com.finsights.portfolio.service.LivePriceService;
import com.finsights.portfolio.service.MarketDataService;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService marketData;
    private final LivePriceService livePrices;

    public MarketController(MarketDataService marketData, LivePriceService livePrices) {
        this.marketData = marketData;
        this.livePrices = livePrices;
    }

    @GetMapping("/search")
    List<SymbolSuggestion> search(@RequestParam("q") String query) {
        return marketData.search(query);
    }

    @GetMapping("/quote")
    ResponseEntity<MarketQuoteResponse> quote(@RequestParam("symbol") String symbol) {
        return marketData.quote(symbol).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    ResponseEntity<MarketHistoryResponse> history(@RequestParam("symbol") String symbol,
                                                   @RequestParam(value = "range", defaultValue = "1M") String range) {
        // Our own accumulated daily history first (free, no external call).
        Optional<MarketHistoryResponse> own = livePrices.ownHistory(symbol, range);
        if (own.isPresent()) return ResponseEntity.ok(own.get());

        // Not enough of our own daily history yet (a symbol this feature hasn't tracked long
        // enough, or at all) — fetch the fullest span available ("1Y" regardless of what range was
        // actually asked for, since MarketDataService's underlying daily series is cached per
        // symbol, not per range, so the slice below reuses this same fetch) and persist every
        // point, so the next request for this symbol — any of 1M/3M/6M/1Y — is served straight
        // from our own table instead of hitting Yahoo again.
        if (livePrices.supportsOwnHistory(range)) {
            marketData.history(symbol, "1Y").ifPresent(full -> livePrices.backfillHistory(symbol, full));
        }
        return marketData.history(symbol, range).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
