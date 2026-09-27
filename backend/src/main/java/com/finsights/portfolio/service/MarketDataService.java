package com.finsights.portfolio.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.MarketHistoryCache;
import com.finsights.portfolio.dto.MarketHistoryResponse;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.dto.SymbolSuggestion;
import com.finsights.portfolio.repository.MarketHistoryCacheRepository;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Live prices and ticker search across two public, unauthenticated feeds: Yahoo Finance's
 * (undocumented) endpoints for exchange-traded instruments, and mfapi.in (a JSON wrapper over
 * AMFI's daily NAV file) for Indian mutual fund schemes, which have no ticker and are keyed by
 * an {@code MF:}-prefixed scheme code instead (see {@link #isMutualFund(String)}). Either source
 * can fail or rate-limit at any time, so every method degrades to "no data" rather than throwing,
 * and a failure in one source never suppresses the other's results. Quotes are cached briefly so
 * opening the Holdings page repeatedly does not hammer either feed.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final String SEARCH_URL = "https://query1.finance.yahoo.com/v1/finance/search";
    private static final String CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String LANDING_URL = "https://finance.yahoo.com/";
    private static final String MFAPI_SEARCH_URL = "https://api.mfapi.in/mf/search";
    private static final String MFAPI_SCHEME_URL = "https://api.mfapi.in/mf/";
    /** Namespaces mutual-fund scheme codes against equity tickers sharing the same string space
     * (Holding.tickerSymbol, watchlist entries, etc.) — e.g. {@code MF:120503}. */
    private static final String MF_PREFIX = "MF:";
    private static final DateTimeFormatter MFAPI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Duration QUOTE_TTL = Duration.ofSeconds(60);
    private static final Duration SEARCH_TTL = Duration.ofMinutes(10);
    private static final Duration HISTORY_TTL = Duration.ofMinutes(5);
    private static final Duration COOKIE_TTL = Duration.ofMinutes(30);
    /** The shared daily series (below) is refreshed at most this often — much longer than HISTORY_TTL
     * because it's one fetch serving every user and every one of the four ranges derived from it. */
    private static final Duration DAILY_SERIES_TTL = Duration.ofHours(6);

    /** ViewMoverItem's chart ranges, mapped to Yahoo's own range/interval query params. */
    private static final Map<String, String[]> HISTORY_RANGES = Map.of(
            "1D", new String[] { "1d", "5m" },
            "1W", new String[] { "5d", "15m" },
            "1M", new String[] { "1mo", "1d" },
            "3M", new String[] { "3mo", "1d" },
            "6M", new String[] { "6mo", "1d" },
            "1Y", new String[] { "1y", "1d" });
    /** These four are all daily-interval already, so they're never fetched individually — each is
     * just a different-length slice of the one shared 1-year daily series (see {@link #dailySeries}). */
    private static final Set<String> DERIVED_FROM_DAILY_SERIES = Set.of("1M", "3M", "6M", "1Y");
    private static final Map<String, Integer> DERIVED_RANGE_DAYS = Map.of("1M", 32, "3M", 93, "6M", 185, "1Y", 370);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached<MarketQuoteResponse>> quoteCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<SymbolSuggestion>>> searchCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<MarketHistoryResponse>> historyCache = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> cookiesPrimedAt = new AtomicReference<>();
    private final MarketHistoryCacheRepository historyCacheRepo;
    private final ObjectMapper sharedJson;

    public MarketDataService(MarketHistoryCacheRepository historyCacheRepo, ObjectMapper sharedJson) {
        this.historyCacheRepo = historyCacheRepo;
        this.sharedJson = sharedJson;
    }

    public List<SymbolSuggestion> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String key = query.trim().toLowerCase();
        Cached<List<SymbolSuggestion>> hit = searchCache.get(key);
        if (hit != null && hit.fresh(SEARCH_TTL)) return hit.value;

        // Each source degrades to empty independently — a failure in one must not suppress the other's results.
        List<SymbolSuggestion> results = new ArrayList<>(searchYahoo(query));
        results.addAll(searchMutualFunds(query));
        // India-first: surface NSE/BSE listings before the foreign lines, keeping each source's own order within a group.
        results.sort((a, b) -> Integer.compare(indianRank(a.symbol()), indianRank(b.symbol())));
        searchCache.put(key, new Cached<>(results));
        return results;
    }

    private List<SymbolSuggestion> searchYahoo(String query) {
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
        return results;
    }

    private List<SymbolSuggestion> searchMutualFunds(String query) {
        List<SymbolSuggestion> results = new ArrayList<>();
        try {
            String url = MFAPI_SEARCH_URL + "?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            JsonNode root = getMfapi(url);
            for (JsonNode f : root) {
                String schemeCode = f.path("schemeCode").asText(null);
                String schemeName = f.path("schemeName").asText(null);
                if (schemeCode == null || schemeCode.isBlank() || schemeName == null || schemeName.isBlank()) continue;
                results.add(new SymbolSuggestion(MF_PREFIX + schemeCode, schemeName, "AMFI", "Mutual Fund"));
            }
        } catch (Exception e) {
            log.debug("Mutual fund search failed for '{}': {}", query, e.toString());
        }
        return results;
    }

    public Optional<MarketQuoteResponse> quote(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        String key = symbol.trim().toUpperCase();
        Cached<MarketQuoteResponse> hit = quoteCache.get(key);
        if (hit != null && hit.fresh(QUOTE_TTL)) return Optional.ofNullable(hit.value);

        MarketQuoteResponse quote = isMutualFund(key) ? mfQuote(key) : yahooQuote(key);
        quoteCache.put(key, new Cached<>(quote));
        return Optional.ofNullable(quote);
    }

    private MarketQuoteResponse yahooQuote(String key) {
        try {
            JsonNode meta = get(CHART_URL + URLEncoder.encode(key, StandardCharsets.UTF_8) + "?range=1d&interval=1d")
                    .path("chart").path("result").path(0).path("meta");
            JsonNode price = meta.path("regularMarketPrice");
            if (!price.isNumber()) return null;
            return new MarketQuoteResponse(
                    firstNonBlank(meta.path("symbol").asText(null), key),
                    firstNonBlank(meta.path("longName").asText(null), meta.path("shortName").asText(null), key),
                    price.decimalValue(),
                    firstNonBlank(meta.path("currency").asText(null), "USD"),
                    Instant.now());
        } catch (Exception e) {
            log.debug("Quote lookup failed for '{}': {}", key, e.toString());
            return null;
        }
    }

    private MarketQuoteResponse mfQuote(String key) {
        try {
            JsonNode root = getMfapi(MFAPI_SCHEME_URL + stripMfPrefix(key));
            JsonNode latest = root.path("data").path(0);
            BigDecimal nav = parseNav(latest.path("nav").asText(null));
            if (nav == null) return null;
            Instant asOf = parseMfDate(latest.path("date").asText(null)).orElseGet(Instant::now);
            String name = firstNonBlank(root.path("meta").path("scheme_name").asText(null), key);
            return new MarketQuoteResponse(key, name, nav, "INR", asOf);
        } catch (Exception e) {
            log.debug("Mutual fund quote lookup failed for '{}': {}", key, e.toString());
            return null;
        }
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

    /**
     * Closing prices for {@code symbol} over one of ViewMoverItem's chart ranges (1D/1W/1M/3M/6M/1Y,
     * defaulting to 1M for anything else) — oldest point first. Same never-throws contract as quote().
     * 1M/3M/6M/1Y are all served from one shared, DB-cached 1-year daily series (see {@link #dailySeries})
     * instead of a Yahoo call per range — tickers are shared across users far more than they're unique
     * to one, so this cuts both latency and how often the feed gets hit.
     */
    public Optional<MarketHistoryResponse> history(String symbol, String rangeKey) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        String key = symbol.trim().toUpperCase();
        String range = HISTORY_RANGES.containsKey(rangeKey) ? rangeKey : "1M";
        if (isMutualFund(key)) {
            // NAV publishes once/day, so there's no per-range Yahoo-style fetch — one call already
            // returns the full dated series; every range is just a client-side slice of it.
            return mfSeries(key).map(series -> sliceToRange(series, range));
        }
        if (DERIVED_FROM_DAILY_SERIES.contains(range)) {
            return dailySeries(key).map(series -> sliceToRange(series, range));
        }
        String[] params = HISTORY_RANGES.get(range);
        return intradayHistory(key, range, params[0], params[1]);
    }

    private Optional<MarketHistoryResponse> mfSeries(String key) {
        Cached<MarketHistoryResponse> hit = historyCache.get(key);
        if (hit != null && hit.fresh(HISTORY_TTL)) return Optional.ofNullable(hit.value);
        Optional<MarketHistoryResponse> fetched = fetchMfHistory(key);
        historyCache.put(key, new Cached<>(fetched.orElse(null)));
        return fetched;
    }

    private Optional<MarketHistoryResponse> fetchMfHistory(String key) {
        try {
            JsonNode root = getMfapi(MFAPI_SCHEME_URL + stripMfPrefix(key));
            List<MarketHistoryResponse.Point> points = new ArrayList<>();
            for (JsonNode row : root.path("data")) {
                BigDecimal nav = parseNav(row.path("nav").asText(null));
                Optional<Instant> when = parseMfDate(row.path("date").asText(null));
                if (nav == null || when.isEmpty()) continue; // skip rather than fabricate
                points.add(new MarketHistoryResponse.Point(when.get(), nav));
            }
            if (points.isEmpty()) return Optional.empty();
            Collections.reverse(points); // mfapi returns newest-first; callers expect oldest-first
            return Optional.of(new MarketHistoryResponse(key, "INR", points));
        } catch (Exception e) {
            log.debug("Mutual fund history fetch failed for '{}': {}", key, e.toString());
            return Optional.empty();
        }
    }

    private Optional<MarketHistoryResponse> intradayHistory(String symbol, String rangeKey, String yahooRange, String yahooInterval) {
        String cacheKey = symbol + ":" + yahooRange + ":" + yahooInterval;
        Cached<MarketHistoryResponse> hit = historyCache.get(cacheKey);
        if (hit != null && hit.fresh(HISTORY_TTL)) return Optional.ofNullable(hit.value);

        MarketHistoryResponse history = fetchChart(symbol, yahooRange, yahooInterval).orElse(null);
        if (history == null) log.debug("History lookup failed for '{}' [{}]", symbol, rangeKey);
        historyCache.put(cacheKey, new Cached<>(history));
        return Optional.ofNullable(history);
    }

    /**
     * The one shared 1-year, daily-interval series backing the 1M/3M/6M/1Y ranges for every user —
     * persisted so a restart doesn't re-trigger a fetch storm, and refreshed at most every
     * {@link #DAILY_SERIES_TTL}. Falls back to whatever was last cached, however stale, if Yahoo
     * can't be reached — better than nothing for a chart that changes little day to day anyway.
     */
    private Optional<MarketHistoryResponse> dailySeries(String symbol) {
        Optional<MarketHistoryCache> cached = historyCacheRepo.findBySymbol(symbol);
        if (cached.isPresent() && cached.get().getFetchedAt().isAfter(Instant.now().minus(DAILY_SERIES_TTL))) {
            Optional<MarketHistoryResponse> fresh = toResponse(cached.get());
            if (fresh.isPresent()) return fresh;
        }
        Optional<MarketHistoryResponse> fetched = fetchChart(symbol, "1y", "1d");
        if (fetched.isPresent()) {
            saveDailySeries(symbol, fetched.get());
            return fetched;
        }
        return cached.flatMap(this::toResponse);
    }

    private void saveDailySeries(String symbol, MarketHistoryResponse series) {
        try {
            String pointsJson = sharedJson.writeValueAsString(series.points());
            MarketHistoryCache row = historyCacheRepo.findBySymbol(symbol).orElseGet(MarketHistoryCache::new);
            row.setSymbol(symbol);
            row.setCurrency(series.currency());
            row.setPointsJson(pointsJson);
            row.setFetchedAt(Instant.now());
            historyCacheRepo.save(row);
        } catch (Exception e) {
            log.debug("Failed to cache daily history for '{}': {}", symbol, e.toString());
        }
    }

    private Optional<MarketHistoryResponse> toResponse(MarketHistoryCache row) {
        try {
            List<MarketHistoryResponse.Point> points = sharedJson.readValue(row.getPointsJson(), new TypeReference<List<MarketHistoryResponse.Point>>() { });
            return Optional.of(new MarketHistoryResponse(row.getSymbol(), row.getCurrency(), points));
        } catch (Exception e) {
            log.debug("Corrupt cached history for '{}': {}", row.getSymbol(), e.toString());
            return Optional.empty();
        }
    }

    /** Never returns fewer than 2 points if the source series has them — falls back to the full
     * series rather than showing an unusably sparse (or empty) chart at the edges. */
    private MarketHistoryResponse sliceToRange(MarketHistoryResponse series, String rangeKey) {
        int days = DERIVED_RANGE_DAYS.getOrDefault(rangeKey, DERIVED_RANGE_DAYS.get("1M"));
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<MarketHistoryResponse.Point> sliced = series.points().stream().filter(p -> !p.timestamp().isBefore(cutoff)).toList();
        return sliced.size() >= 2 ? new MarketHistoryResponse(series.symbol(), series.currency(), sliced) : series;
    }

    private Optional<MarketHistoryResponse> fetchChart(String symbol, String yahooRange, String yahooInterval) {
        try {
            String url = CHART_URL + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "?range=" + yahooRange + "&interval=" + yahooInterval;
            JsonNode result = get(url).path("chart").path("result").path(0);
            JsonNode meta = result.path("meta");
            JsonNode timestamps = result.path("timestamp");
            JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
            List<MarketHistoryResponse.Point> points = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                JsonNode close = closes.path(i);
                if (!close.isNumber()) continue; // market-closed gaps come back null — skip rather than fabricate
                points.add(new MarketHistoryResponse.Point(Instant.ofEpochSecond(timestamps.path(i).asLong()), close.decimalValue()));
            }
            if (points.isEmpty()) return Optional.empty();
            return Optional.of(new MarketHistoryResponse(
                    firstNonBlank(meta.path("symbol").asText(null), symbol),
                    firstNonBlank(meta.path("currency").asText(null), "USD"),
                    points));
        } catch (Exception e) {
            log.debug("Chart fetch failed for '{}' [{}/{}]: {}", symbol, yahooRange, yahooInterval, e.toString());
            return Optional.empty();
        }
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

    /** mfapi.in needs no session cookie (that's Yahoo-specific) — a plain unauthenticated GET. */
    private JsonNode getMfapi(String url) throws Exception {
        HttpResponse<String> response = send(url);
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

    private static boolean isMutualFund(String key) {
        return key.startsWith(MF_PREFIX);
    }

    private static String stripMfPrefix(String key) {
        return key.substring(MF_PREFIX.length());
    }

    private static BigDecimal parseNav(String nav) {
        if (nav == null || nav.isBlank()) return null;
        try {
            return new BigDecimal(nav.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Optional<Instant> parseMfDate(String date) {
        if (date == null || date.isBlank()) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(date.trim(), MFAPI_DATE_FORMAT).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
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
