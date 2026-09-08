package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.dto.SymbolSuggestion;
import com.finsights.portfolio.service.MarketDataService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService marketData;

    public MarketController(MarketDataService marketData) {
        this.marketData = marketData;
    }

    @GetMapping("/search")
    List<SymbolSuggestion> search(@RequestParam("q") String query) {
        return marketData.search(query);
    }

    @GetMapping("/quote")
    ResponseEntity<MarketQuoteResponse> quote(@RequestParam("symbol") String symbol) {
        return marketData.quote(symbol).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
