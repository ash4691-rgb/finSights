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
import java.util.List;
import java.util.Optional;
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

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final NewsCacheRepository cache;
    private final HoldingService holdings;
    private final WatchlistService watchlist;
    private final ObjectMapper sharedJson;

    public NewsService(NewsCacheRepository cache, HoldingService holdings, WatchlistService watchlist, ObjectMapper sharedJson) {
        this.cache = cache;
        this.holdings = holdings;
        this.watchlist = watchlist;
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
     * now happens outside any transaction. */
    public void refreshAll(java.util.Set<String> symbols) {
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank() || symbol.startsWith(MF_PREFIX)) continue;
            Optional<NewsCache> existing = cache.findBySymbol(symbol);
            if (existing.isPresent() && existing.get().getFetchedAt().isAfter(Instant.now().minus(TTL))) continue;
            try {
                List<Headline> headlines = fetch(symbol);
                save(symbol, headlines, existing.orElse(null));
            } catch (Exception e) {
                log.debug("News fetch failed for '{}': {}", symbol, e.toString());
            }
        }
    }

    /** The current user's news feed: every cached headline for their market-linked holdings and
     * watchlist symbols, newest first, capped at {@link #MAX_FOR_USER}. Read-only — a symbol with
     * no cache row yet (never refreshed) or a stale one (last refresh failed) just contributes
     * whatever's there, including nothing. */
    public List<NewsItemResponse> forUser() {
        List<NewsItemResponse> out = new ArrayList<>();
        for (HoldingResponse h : holdings.list()) {
            if (h.kind() == HoldingKind.LIABILITY || !isMarketLinked(h.valuationMethod())) continue;
            headlinesFor(h.tickerSymbol()).forEach(hl -> out.add(toResponse("HOLDING", h.id(), h.name(), h.tickerSymbol(), hl)));
        }
        for (WatchlistResponse w : watchlist.list()) {
            headlinesFor(w.tickerSymbol()).forEach(hl -> out.add(toResponse("WATCHLIST", w.id(), w.name(), w.tickerSymbol(), hl)));
        }
        return out.stream()
                .sorted(Comparator.comparing(NewsItemResponse::publishedAt).reversed())
                .limit(MAX_FOR_USER)
                .toList();
    }

    private boolean isMarketLinked(ValuationMethod method) {
        return method == ValuationMethod.MARKET_PRICE || method == ValuationMethod.BROKER_SYNC;
    }

    private List<Headline> headlinesFor(String symbol) {
        if (symbol == null || symbol.isBlank() || symbol.startsWith(MF_PREFIX)) return List.of();
        return cache.findBySymbol(symbol).map(row -> {
            try {
                return sharedJson.readValue(row.getItemsJson(), new TypeReference<List<Headline>>() { });
            } catch (Exception e) {
                log.debug("Corrupt cached news for '{}': {}", symbol, e.toString());
                return List.<Headline>of();
            }
        }).orElseGet(List::of);
    }

    private NewsItemResponse toResponse(String subjectType, String id, String name, String tickerSymbol, Headline hl) {
        return new NewsItemResponse(subjectType, id, name, tickerSymbol, hl.title(), hl.link(), hl.source(), hl.publishedAt());
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
