package com.finsights.portfolio.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.NewsCache;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.NewsItemResponse;
import com.finsights.portfolio.dto.WatchlistResponse;
import com.finsights.portfolio.repository.NewsCacheRepository;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Recent news headlines for market-linked holdings and watchlist symbols, for Signals' Radar.
 * Backed by Google News' public RSS search — no API key, so (like {@link MarketDataService}'s
 * Yahoo calls) every fetch can fail or be rate-limited at any time and degrades to "keep whatever
 * was cached" rather than throwing. Fetching is scheduler-driven only ({@link NewsRefreshScheduler});
 * {@link #forUser()} only ever reads the DB cache, never fetches inline with a page load.
 */
@Service
public class NewsService {
    private static final Logger log = LoggerFactory.getLogger(NewsService.class);
    private static final String SEARCH_URL = "https://news.google.com/rss/search";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Duration TTL = Duration.ofHours(1);
    private static final int MAX_PER_SYMBOL = 5;   // stored per ticker
    private static final int MAX_FOR_USER = 8;     // shown in Radar
    private static final String MF_PREFIX = "MF:"; // mirrors MarketDataService — a scheme code has no usable search text
    /** "News older than 14 days are not required" — applied at read time in headlinesFor() so it
     * self-heals any already-cached row too, not just newly-fetched ones. Package-private (not
     * private) so NewsRefreshScheduler can prune NewsDismissalService's rows against the same
     * cutoff — an article older than this can never resurface anyway. */
    static final Duration MAX_AGE = Duration.ofDays(14);
    /** Bounded, same reasoning as MarketDataService.QUOTE_CONCURRENCY — Google News is a free,
     * unauthenticated feed more sensitive to bursts than Yahoo, hence the smaller cap. */
    private static final int FETCH_CONCURRENCY = 5;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final NewsCacheRepository cache;
    private final HoldingService holdings;
    private final WatchlistService watchlist;
    private final TickerPreferenceService preferences;
    private final NewsDismissalService dismissals;
    private final ObjectMapper sharedJson;

    public NewsService(NewsCacheRepository cache, HoldingService holdings, WatchlistService watchlist,
                        TickerPreferenceService preferences, NewsDismissalService dismissals, ObjectMapper sharedJson) {
        this.cache = cache;
        this.holdings = holdings;
        this.watchlist = watchlist;
        this.preferences = preferences;
        this.dismissals = dismissals;
        this.sharedJson = sharedJson;
    }

    /** Refreshes any symbol whose cache is missing or older than {@link #TTL} — called only by
     * {@link NewsRefreshScheduler}, never from a request thread. Never throws: one symbol's
     * failure (network, rate-limit, malformed feed) never blocks the rest.
     *
     * Deliberately NOT {@code @Transactional} at this level — same reasoning as
     * {@link LivePriceService#refreshAll}: each {@code fetch(symbol)} is a blocking external HTTP
     * call (up to ~7s worst case), and wrapping the whole per-symbol loop in one transaction would
     * hold a single connection from the small (5-max) Hikari pool for the entire loop's duration,
     * starving concurrent requests. {@code cache.findBySymbol}/{@code cache.save} each still get
     * their own short, implicit transaction from the repository proxy — only the HTTP call itself
     * now happens outside any transaction.
     *
     * Fetches up to {@link #FETCH_CONCURRENCY} symbols' RSS feeds at once rather than one at a
     * time — "with multiple symbols, if possible": Google News RSS has no multi-symbol batch query
     * (unlike Yahoo's quote endpoint), so concurrency is the only lever available here. */
    public void refreshAll(Set<String> symbols) {
        Map<String, NewsCache> existingBySymbol = new HashMap<>();
        List<String> toRefresh = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank() || symbol.startsWith(MF_PREFIX)) continue;
            Optional<NewsCache> existing = cache.findBySymbol(symbol);
            existing.ifPresent(row -> existingBySymbol.put(symbol, row));
            if (existing.isEmpty() || existing.get().getFetchedAt().isBefore(Instant.now().minus(TTL))) {
                toRefresh.add(symbol);
            }
        }
        if (toRefresh.isEmpty()) return;

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(FETCH_CONCURRENCY, toRefresh.size()));
        try {
            List<Callable<Void>> tasks = toRefresh.stream()
                    .<Callable<Void>>map(symbol -> () -> {
                        try {
                            save(symbol, fetch(symbol), existingBySymbol.get(symbol));
                        } catch (Exception e) {
                            log.debug("News fetch failed for '{}': {}", symbol, e.toString());
                        }
                        return null;
                    })
                    .toList();
            executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }
    }

    /** The current user's news feed: every cached headline (within {@link #MAX_AGE}) for their
     * market-linked holdings and watchlist symbols, minus anything they've excluded (by ticker) or
     * dismissed (by article), ranked starred-tickers-first and newest-first within each group,
     * capped at {@link #MAX_FOR_USER}. Read-only — a symbol with no cache row yet (never refreshed)
     * or a stale one (last refresh failed) just contributes whatever's there, including nothing.
     *
     * Deduplicates by ticker symbol first: the same symbol can be reachable from more than one
     * holding (e.g. the same stock via two brokers), or from both a holding and a watchlist entry,
     * and each used to add its cached headlines again for every reference — the same article
     * showing up two or three times in Radar. One subject per symbol now (a holding wins over a
     * watchlist entry for the same symbol, since it's the more concrete relationship); a second,
     * cheap pass drops any article whose link repeats even across different symbols, as a safety
     * net rather than the primary fix. */
    public List<NewsItemResponse> forUser() {
        Set<String> starred = preferences.starredSymbols();
        Set<String> excluded = preferences.excludedSymbols();
        Set<String> dismissedLinks = dismissals.dismissedLinks();

        Map<String, Subject> bySymbol = new LinkedHashMap<>();
        for (HoldingResponse h : holdings.list()) {
            if (h.kind() == HoldingKind.LIABILITY || !isMarketLinked(h.valuationMethod())) continue;
            String key = normalizedSymbol(h.tickerSymbol());
            if (key == null || excluded.contains(key)) continue;
            bySymbol.put(key, new Subject("HOLDING", h.id(), h.name(), h.tickerSymbol()));
        }
        for (WatchlistResponse w : watchlist.list()) {
            String key = normalizedSymbol(w.tickerSymbol());
            if (key == null || excluded.contains(key)) continue;
            bySymbol.putIfAbsent(key, new Subject("WATCHLIST", w.id(), w.name(), w.tickerSymbol()));
        }

        List<NewsItemResponse> out = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        for (Subject subject : bySymbol.values()) {
            for (Headline hl : headlinesFor(subject.tickerSymbol())) {
                if (dismissedLinks.contains(hl.link()) || !seenLinks.add(hl.link())) continue;
                out.add(toResponse(subject.subjectType(), subject.id(), subject.name(), subject.tickerSymbol(), hl, starred));
            }
        }
        return out.stream()
                .sorted(Comparator.comparing(NewsItemResponse::starred).reversed()
                        .thenComparing(Comparator.comparing(NewsItemResponse::publishedAt).reversed()))
                .limit(MAX_FOR_USER)
                .toList();
    }

    private boolean isMarketLinked(ValuationMethod method) {
        return method == ValuationMethod.MARKET_PRICE || method == ValuationMethod.BROKER_SYNC;
    }

    private String normalizedSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
    }

    /** The holding or watchlist entry a symbol's news is attributed to in forUser()'s deduped feed
     *  — tickerSymbol is kept in its original (non-normalized) form since that's what the cache is
     *  actually keyed by. */
    private record Subject(String subjectType, String id, String name, String tickerSymbol) { }

    private List<Headline> headlinesFor(String symbol) {
        if (symbol == null || symbol.isBlank() || symbol.startsWith(MF_PREFIX)) return List.of();
        Instant cutoff = Instant.now().minus(MAX_AGE);
        return cache.findBySymbol(symbol).map(row -> {
            try {
                List<Headline> all = sharedJson.readValue(row.getItemsJson(), new TypeReference<List<Headline>>() { });
                return all.stream().filter(hl -> hl.publishedAt().isAfter(cutoff)).toList();
            } catch (Exception e) {
                log.debug("Corrupt cached news for '{}': {}", symbol, e.toString());
                return List.<Headline>of();
            }
        }).orElseGet(List::of);
    }

    private NewsItemResponse toResponse(String subjectType, String id, String name, String tickerSymbol, Headline hl, Set<String> starred) {
        boolean isStarred = tickerSymbol != null && starred.contains(tickerSymbol.trim().toUpperCase());
        return new NewsItemResponse(subjectType, id, name, tickerSymbol, hl.title(), hl.link(), hl.source(), hl.publishedAt(), isStarred);
    }

    private void save(String symbol, List<Headline> headlines, NewsCache existing) {
        try {
            String json = sharedJson.writeValueAsString(headlines);
            NewsCache row = existing != null ? existing : new NewsCache();
            row.setSymbol(symbol);
            row.setItemsJson(json);
            row.setFetchedAt(Instant.now());
            cache.save(row);
        } catch (Exception e) {
            log.debug("Failed to cache news for '{}': {}", symbol, e.toString());
        }
    }

    /** NSE/BSE tickers carry an exchange suffix Google News doesn't understand; the bare symbol
     * plus a couple of disambiguating words makes a decent search query without it. */
    private List<Headline> fetch(String symbol) throws Exception {
        String bareSymbol = symbol.replaceAll("\\.(NS|BO)$", "");
        String query = bareSymbol + " stock news";
        String url = SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&hl=en-IN&gl=IN&ceid=IN:en";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(4))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return parse(response.body());
    }

    // Package-private (not private) so NewsServiceTest can exercise RSS parsing directly with
    // canned XML, without needing a live fetch — same reasoning as DueSchedule's testable statics.
    List<Headline> parse(String xml) throws Exception {
        var doc = newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList items = doc.getElementsByTagName("item");
        List<Headline> out = new ArrayList<>();
        for (int i = 0; i < items.getLength() && out.size() < MAX_PER_SYMBOL; i++) {
            Element item = (Element) items.item(i);
            String title = text(item, "title");
            String link = text(item, "link");
            String pubDate = text(item, "pubDate");
            String source = sourceOf(item);
            if (title == null || link == null || pubDate == null) continue;
            Instant publishedAt;
            try { publishedAt = DateTimeFormatter.RFC_1123_DATE_TIME.parse(pubDate, Instant::from); }
            catch (Exception e) { continue; } // an unparseable date is unusable for sorting — skip the item
            out.add(new Headline(stripSourceSuffix(title, source), link, source, publishedAt));
        }
        return out;
    }

    /** Google News' custom <source> element inside each <item> — its text content is the
     * publisher's display name (e.g. "Economic Times"), distinct from <title>/<link>/<pubDate>. */
    private String sourceOf(Element item) {
        NodeList sources = item.getElementsByTagName("source");
        return sources.getLength() > 0 ? sources.item(0).getTextContent().trim() : "";
    }

    /** Google News titles are usually "Headline - Publisher" — drop that trailing segment when it
     * exactly matches the <source> element, so the displayed title doesn't repeat the source chip. */
    private String stripSourceSuffix(String title, String source) {
        if (source.isBlank()) return title.trim();
        String suffix = " - " + source;
        return title.endsWith(suffix) ? title.substring(0, title.length() - suffix.length()).trim() : title.trim();
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        Node node = nodes.item(0);
        String value = node.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Hardened against XXE — same policy as XmlService.newDocumentBuilder(), copied rather than
     * shared since that one serves a different domain (user-uploaded transaction XML). */
    private DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder();
    }

    /** One cached headline — the opaque payload stored in NewsCache.itemsJson. */
    public record Headline(String title, String link, String source, Instant publishedAt) { }
}
