package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.NewsCache;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.NewsItemResponse;
import com.finsights.portfolio.dto.WatchlistResponse;
import com.finsights.portfolio.repository.NewsCacheRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NewsServiceTest {

    @Mock NewsCacheRepository cache;
    @Mock HoldingService holdings;
    @Mock WatchlistService watchlist;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private NewsService service;

    @BeforeEach
    void setUp() {
        service = new NewsService(cache, holdings, watchlist, json);
    }

    // --- parse() ------------------------------------------------------------------------------

    @Test
    void parseStripsTheTrailingSourceSuffixWhenItExactlyMatches() throws Exception {
        String xml = rss(item("Reliance shares surge - Economic Times", "https://a", "Economic Times", "Tue, 13 Oct 2026 10:00:00 GMT"));

        List<NewsService.Headline> headlines = service.parse(xml);

        assertThat(headlines).hasSize(1);
        assertThat(headlines.get(0).title()).isEqualTo("Reliance shares surge");
        assertThat(headlines.get(0).source()).isEqualTo("Economic Times");
        assertThat(headlines.get(0).link()).isEqualTo("https://a");
        assertThat(headlines.get(0).publishedAt()).isEqualTo(Instant.parse("2026-10-13T10:00:00Z"));
    }

    @Test
    void parseLeavesTheTitleAloneWhenItDoesNotEndWithTheSourceSuffix() throws Exception {
        String xml = rss(item("Reliance shares surge after results", "https://a", "Economic Times", "Tue, 13 Oct 2026 10:00:00 GMT"));

        List<NewsService.Headline> headlines = service.parse(xml);

        assertThat(headlines.get(0).title()).isEqualTo("Reliance shares surge after results");
    }

    @Test
    void parseSkipsItemsWithAnUnparseablePubDate() throws Exception {
        String xml = rss(item("Headline one", "https://a", "ET", "not a date")
                + item("Headline two", "https://b", "ET", "Tue, 13 Oct 2026 10:00:00 GMT"));

        List<NewsService.Headline> headlines = service.parse(xml);

        assertThat(headlines).hasSize(1);
        assertThat(headlines.get(0).title()).isEqualTo("Headline two");
    }

    @Test
    void parseCapsAtFiveItemsPerSymbol() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            items.append(item("Headline " + i, "https://a/" + i, "ET", "Tue, 13 Oct 2026 10:00:00 GMT"));
        }
        List<NewsService.Headline> headlines = service.parse(rss(items.toString()));

        assertThat(headlines).hasSize(5);
    }

    private static String item(String title, String link, String source, String pubDate) {
        return "<item><title>" + title + "</title><link>" + link + "</link><pubDate>" + pubDate
                + "</pubDate><source url=\"https://example.com\">" + source + "</source></item>";
    }

    private static String rss(String items) {
        return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Google News</title>" + items + "</channel></rss>";
    }

    // --- refreshAll() ---------------------------------------------------------------------------

    @Test
    void refreshAllSkipsMutualFundTickersWithoutTouchingTheCacheAtAll() {
        service.refreshAll(Set.of("MF:120503"));

        verifyNoInteractions(cache);
    }

    @Test
    void refreshAllSkipsASymbolWhoseCacheIsStillFreshWithinTheTtl() {
        NewsCache fresh = new NewsCache();
        fresh.setSymbol("AAPL");
        fresh.setFetchedAt(Instant.now().minus(10, ChronoUnit.MINUTES)); // well inside the 1h TTL
        fresh.setItemsJson("[]");
        when(cache.findBySymbol("AAPL")).thenReturn(Optional.of(fresh));

        service.refreshAll(Set.of("AAPL"));

        verify(cache, never()).save(any());
    }

    // --- forUser() --------------------------------------------------------------------------

    @Test
    void forUserMergesHoldingAndWatchlistNewsTaggedWithTheirSubjectType() throws Exception {
        HoldingResponse holding = holding("h-1", "RELIANCE", ValuationMethod.MARKET_PRICE, HoldingKind.ASSET);
        WatchlistResponse watch = watchlistItem("w-1", "NIFTY", "Nifty 50");
        when(holdings.list()).thenReturn(List.of(holding));
        when(watchlist.list()).thenReturn(List.of(watch));
        stubCache("RELIANCE", headline("Reliance news", Instant.parse("2026-10-13T09:00:00Z")));
        stubCache("NIFTY", headline("Nifty news", Instant.parse("2026-10-13T10:00:00Z")));

        List<NewsItemResponse> result = service.forUser();

        assertThat(result).hasSize(2);
        // Newest first.
        assertThat(result.get(0).title()).isEqualTo("Nifty news");
        assertThat(result.get(0).subjectType()).isEqualTo("WATCHLIST");
        assertThat(result.get(0).id()).isEqualTo("w-1");
        assertThat(result.get(1).title()).isEqualTo("Reliance news");
        assertThat(result.get(1).subjectType()).isEqualTo("HOLDING");
        assertThat(result.get(1).id()).isEqualTo("h-1");
    }

    @Test
    void forUserExcludesNonMarketLinkedAndLiabilityHoldings() {
        HoldingResponse manual = holding("h-1", "RELIANCE", ValuationMethod.MANUAL, HoldingKind.ASSET);
        HoldingResponse liability = holding("h-2", "HDFCBANK", ValuationMethod.MARKET_PRICE, HoldingKind.LIABILITY);
        when(holdings.list()).thenReturn(List.of(manual, liability));
        when(watchlist.list()).thenReturn(List.of());

        List<NewsItemResponse> result = service.forUser();

        assertThat(result).isEmpty();
        verifyNoInteractions(cache);
    }

    @Test
    void forUserIgnoresAWatchlistItemWithNoTickerSymbol() {
        when(holdings.list()).thenReturn(List.of());
        when(watchlist.list()).thenReturn(List.of(watchlistItem("w-1", null, "Untracked idea")));

        List<NewsItemResponse> result = service.forUser();

        assertThat(result).isEmpty();
    }

    @Test
    void forUserCapsAtEightItemsAcrossAllSymbols() throws Exception {
        WatchlistResponse watch = watchlistItem("w-1", "NIFTY", "Nifty 50");
        when(holdings.list()).thenReturn(List.of());
        when(watchlist.list()).thenReturn(List.of(watch));
        NewsService.Headline[] ten = new NewsService.Headline[10];
        for (int i = 0; i < 10; i++) {
            ten[i] = new NewsService.Headline("H" + i, "https://a/" + i, "ET", Instant.parse("2026-10-13T00:00:00Z").plusSeconds(i));
        }
        stubCache("NIFTY", ten);

        List<NewsItemResponse> result = service.forUser();

        assertThat(result).hasSize(8);
        assertThat(result.get(0).title()).isEqualTo("H9"); // newest of the ten
    }

    private void stubCache(String symbol, NewsService.Headline... headlines) throws Exception {
        NewsCache row = new NewsCache();
        row.setSymbol(symbol);
        row.setFetchedAt(Instant.now());
        row.setItemsJson(json.writeValueAsString(List.of(headlines)));
        when(cache.findBySymbol(symbol)).thenReturn(Optional.of(row));
    }

    private static NewsService.Headline headline(String title, Instant publishedAt) {
        return new NewsService.Headline(title, "https://a", "ET", publishedAt);
    }

    private HoldingResponse holding(String id, String ticker, ValuationMethod method, HoldingKind kind) {
        return new HoldingResponse(
                id, id, "c-1", "Growth Equity", "Reliance Industries", kind, method,
                ticker, "Kite", "INR", "INR", new BigDecimal("100"), new BigDecimal("110"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, null, null, null, null, null, null, false, false, null, null, Set.of(), Instant.now(), Instant.now(), null, false, null);
    }

    private WatchlistResponse watchlistItem(String id, String ticker, String name) {
        return new WatchlistResponse(id, name, ticker, null, new BigDecimal("100"), "INR", Instant.now(), Instant.now());
    }
}
