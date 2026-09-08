package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.WatchlistCreateRequest;
import com.finsights.portfolio.dto.WatchlistPriceRequest;
import com.finsights.portfolio.dto.WatchlistResponse;
import com.finsights.portfolio.dto.WatchlistUpdateRequest;
import com.finsights.portfolio.service.WatchlistService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {
    private final WatchlistService watchlist;

    public WatchlistController(WatchlistService watchlist) { this.watchlist = watchlist; }

    @GetMapping
    List<WatchlistResponse> list() { return watchlist.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    WatchlistResponse create(@Valid @RequestBody WatchlistCreateRequest request) { return watchlist.create(request); }

    @PutMapping("/{id}")
    WatchlistResponse update(@PathVariable String id, @Valid @RequestBody WatchlistUpdateRequest request) {
        return watchlist.update(id, request);
    }

    @PostMapping("/{id}/price")
    WatchlistResponse recordPrice(@PathVariable String id, @Valid @RequestBody WatchlistPriceRequest request) {
        return watchlist.recordPrice(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) { watchlist.delete(id); }
}
