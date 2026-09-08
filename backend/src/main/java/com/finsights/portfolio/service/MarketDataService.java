package com.finsights.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.dto.SymbolSuggestion;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Live prices and ticker search backed by Yahoo Finance's public (undocumented)
 * endpoints. There is no API key; calls can fail or rate-limit at any time, so
 * every method degrades to "no data" rather than throwing. Quotes are cached
 * briefly so opening the Holdings page repeatedly does not hammer the feed.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final String SEARCH_URL = "https://query1.finance.yahoo.com/v1/finance/search";
    private static final String CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String LANDING_URL = "https://finance.yahoo.com/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Duration QUOTE_TTL = Duration.ofSeconds(60);
    private static final Duration SEARCH_TTL = Duration.ofMinutes(10);
    private static final Duration COOKIE_TTL = Duration.ofMinutes(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached<MarketQuoteResponse>> quoteCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<SymbolSuggestion>>> searchCache = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> cookiesPrimedAt = new AtomicReference<>();

    public List<SymbolSuggestion> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String key = query.trim().toLowerCase();
        Cached<List<SymbolSuggestion>> hit = searchCache.get(key);
        if (hit != null && hit.fresh(SEARCH_TTL)) return hit.value;

        List<SymbolSuggestion> results = new ArrayList<>();
        try {
            String url = SEARCH_URL + "?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
                    + "&quotesCount=10&newsCount=0&listsCount=0";
            JsonNode root = get(url);
            for (JsonNode q : root.path("quotes")) {
                String symbol = q.path("symbol").asText(null);
                if (symbol == null || symbol.isBlank()) continue;
                if (!q.path("isYahooFinance").asBoolean(true)) continue;
                String name = firstNonBlank(q.path("shortname").asText(null), q.path("longname").asText(null), symbol);
                String exchange = firstNonBlank(q.path("exchDisp").asText(null), q.path("exchange").asText(null), "");
                String type = firstNonBlank(q.path("typeDisp").asText(null), q.path("quoteType").asText(null), "");
                results.add(new SymbolSuggestion(symbol, name, exchange, type));
            }
        } catch (Exception e) {
            log.debug("Ticker search failed for '{}': {}", query, e.toString());
        }
        // India-first: surface NSE/BSE listings before the foreign lines, keeping Yahoo's order within each group.
        results.sort((a, b) -> Integer.compare(indianRank(a.symbol()), indianRank(b.symbol())));
        searchCache.put(key, new Cached<>(results));
        return results;
    }

    public Optional<MarketQuoteResponse> quote(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        String key = symbol.trim().toUpperCase();
        Cached<MarketQuoteResponse> hit = quoteCache.get(key);
        if (hit != null && hit.fresh(QUOTE_TTL)) return Optional.ofNullable(hit.value);

        MarketQuoteResponse quote = null;
        try {
            JsonNode meta = get(CHART_URL + URLEncoder.encode(key, StandardCharsets.UTF_8) + "?range=1d&interval=1d")
                    .path("chart").path("result").path(0).path("meta");
            JsonNode price = meta.path("regularMarketPrice");
            if (price.isNumber()) {
                quote = new MarketQuoteResponse(
                        firstNonBlank(meta.path("symbol").asText(null), key),
                        firstNonBlank(meta.path("longName").asText(null), meta.path("shortName").asText(null), key),
                        price.decimalValue(),
                        firstNonBlank(meta.path("currency").asText(null), "USD"),
                        Instant.now());
            }
        } catch (Exception e) {
            log.debug("Quote lookup failed for '{}': {}", symbol, e.toString());
        }
        quoteCache.put(key, new Cached<>(quote));
        return Optional.ofNullable(quote);
    }

    /** Best-effort batch: never throws, missing symbols are simply absent from the map. */
    public Map<String, MarketQuoteResponse> quotes(Collection<String> symbols) {
        Map<String, MarketQuoteResponse> out = new HashMap<>();
        if (symbols == null) return out;
        for (String symbol : symbols) {
            quote(symbol).ifPresent(q -> out.put(symbol.trim().toUpperCase(), q));
        }
        return out;
    }

    private JsonNode get(String url) throws Exception {
        primeCookies(false);
        HttpResponse<String> response = send(url);
        if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 429) {
            primeCookies(true); // stale/rejected cookie — refresh the Yahoo session and retry once
            response = send(url);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }

    private HttpResponse<String> send(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(4))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Yahoo's data hosts 429 requests that arrive without a session cookie from its site. */
    private void primeCookies(boolean force) {
        Instant last = cookiesPrimedAt.get();
        if (!force && last != null && last.plus(COOKIE_TTL).isAfter(Instant.now())) return;
        try {
            http.send(HttpRequest.newBuilder(URI.create(LANDING_URL))
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", USER_AGENT)
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            cookiesPrimedAt.set(Instant.now());
        } catch (Exception e) {
            log.debug("Priming Yahoo cookies failed: {}", e.toString());
        }
    }

    private static int indianRank(String symbol) {
        String s = symbol.toUpperCase();
        if (s.endsWith(".NS")) return 0;
        if (s.endsWith(".BO")) return 1;
        return 2;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private record Cached<T>(T value, Instant at) {
        Cached(T value) { this(value, Instant.now()); }
        boolean fresh(Duration ttl) { return at.plus(ttl).isAfter(Instant.now()); }
    }
}
