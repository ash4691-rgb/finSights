package com.finsights.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Talks to Zerodha's Kite Connect v3 REST API — the login handshake and a holdings read. Built
 * against Kite's public documentation (https://kite.trade/docs/connect/v3/), not verified live
 * (no Kite Connect developer credentials in this environment) — see the PR description for what
 * still needs a real end-to-end smoke test once {@code KITE_API_KEY}/{@code KITE_API_SECRET} are
 * configured. Same never-throws, degrade-to-"no data" contract as {@link MarketDataService}: a
 * dead or unreachable Kite API never propagates an exception to a caller.
 */
@Service
public class KiteConnectClient {

    private static final Logger log = LoggerFactory.getLogger(KiteConnectClient.class);
    private static final String LOGIN_URL = "https://kite.zerodha.com/connect/login";
    private static final String TOKEN_URL = "https://api.kite.trade/session/token";
    private static final String HOLDINGS_URL = "https://api.kite.trade/portfolio/holdings";
    /** Kite Connect's own API version header/query param — v3 is the current (and, per their
     *  docs, only actively supported) version. */
    private static final String KITE_VERSION = "3";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey;
    private final String apiSecret;
    private final String redirectUrl;

    public KiteConnectClient(
            @Value("${app.kite.api-key}") String apiKey,
            @Value("${app.kite.api-secret}") String apiSecret,
            @Value("${app.kite.redirect-url}") String redirectUrl) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.redirectUrl = redirectUrl;
    }

    /** False until KITE_API_KEY/KITE_API_SECRET/KITE_REDIRECT_URL are all set — callers (the
     *  Brokers page's Connect button, the daily scheduler) check this before doing anything else,
     *  the same way SymbolSearchInput's Google-sign-in button checks googleEnabled. */
    public boolean isConfigured() {
        return !blank(apiKey) && !blank(apiSecret) && !blank(redirectUrl);
    }

    /** The URL to send the browser to for Kite's own login page — the first step of Kite Connect
     *  v3's login flow (redirect → user logs in on kite.zerodha.com → Kite redirects back to our
     *  own KITE_REDIRECT_URL with a one-time request_token). */
    public String loginUrl() {
        return LOGIN_URL + "?v=" + KITE_VERSION + "&api_key=" + enc(apiKey);
    }

    /**
     * Exchanges the one-time {@code request_token} Kite's redirect handed us for a session {@code
     * access_token}. Per Kite Connect v3: {@code POST /session/token} with {@code api_key},
     * {@code request_token}, and a {@code checksum} = SHA-256({@code api_key + request_token +
     * api_secret}), hex-encoded — this proves the exchange request actually came from us (the
     * holder of {@code api_secret}), not an attacker who merely observed the redirect's token.
     */
    public Optional<KiteSession> exchangeRequestToken(String requestToken) {
        if (requestToken == null || requestToken.isBlank()) return Optional.empty();
        try {
            String checksum = sha256Hex(apiKey + requestToken + apiSecret);
            String body = "api_key=" + enc(apiKey) + "&request_token=" + enc(requestToken) + "&checksum=" + enc(checksum);
            HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-Kite-Version", KITE_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Kite token exchange failed: HTTP {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode data = json.readTree(response.body()).path("data");
            String accessToken = data.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) return Optional.empty();
            return Optional.of(new KiteSession(accessToken, data.path("user_id").asText(null)));
        } catch (Exception e) {
            log.warn("Kite token exchange failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * This user's current equity holdings, read live from Kite right now — not cached; callers
     * (the daily sync job) decide the cadence. {@code Optional.empty()} means "couldn't reach
     * Kite or got a bad response" and must NOT be treated as "zero holdings" by a caller — see
     * {@link BrokerSyncScheduler}, which relies on exactly that distinction to avoid wiping out
     * synced holdings on a transient Kite outage. An {@code Optional} holding an empty list is a
     * genuine "Kite reports you hold nothing right now."
     */
    public Optional<List<KiteHolding>> fetchHoldings(String accessToken) {
        if (blank(accessToken)) return Optional.empty();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(HOLDINGS_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .header("X-Kite-Version", KITE_VERSION)
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Kite holdings fetch failed: HTTP {}", response.statusCode());
                return Optional.empty();
            }
            List<KiteHolding> out = new ArrayList<>();
            for (JsonNode row : json.readTree(response.body()).path("data")) {
                String symbol = row.path("tradingsymbol").asText(null);
                long instrumentToken = row.path("instrument_token").asLong(0);
                if (symbol == null || symbol.isBlank() || instrumentToken <= 0) continue; // malformed row — skip, don't fabricate
                BigDecimal quantity = row.path("quantity").decimalValue();
                JsonNode priceNode = row.path("last_price");
                BigDecimal lastPrice = priceNode.isNumber() ? priceNode.decimalValue() : null;
                out.add(new KiteHolding(instrumentToken, symbol, quantity, lastPrice));
            }
            return Optional.of(out);
        } catch (Exception e) {
            log.warn("Kite holdings fetch failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private static String sha256Hex(String input) throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    public record KiteSession(String accessToken, String kiteUserId) { }

    /** {@code lastPrice} is nullable — Kite can omit it for an illiquid/delisted instrument. */
    public record KiteHolding(long instrumentToken, String tradingSymbol, BigDecimal quantity, BigDecimal lastPrice) { }
}
