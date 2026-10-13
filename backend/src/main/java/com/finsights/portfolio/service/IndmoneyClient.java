package com.finsights.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.BrokerConnection;
import com.finsights.portfolio.dto.BrokerSnapshot;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Talks to INDstocks' trading API (api.indstocks.com) to read a user's demat equity holdings.
 * Like CoinSwitch, there's no central developer app to register — each user generates their own
 * bearer token from their own INDmoney account (the API Trading dashboard on indstocks.com) and
 * pastes it directly into FinSights; see {@link BrokerConnectionService#connectWithCredentials}.
 * Built against INDstocks' public docs and a community reference, not verified live (no INDmoney
 * account/token in this environment) — in particular, confirm the field names below against a
 * real response before relying on this in production. Same never-throws, degrade-to-"no data"
 * contract as every other broker client in this codebase.
 */
@Service
public class IndmoneyClient implements BrokerDataClient {

    private static final Logger log = LoggerFactory.getLogger(IndmoneyClient.class);
    private static final String HOLDINGS_URL = "https://api.indstocks.com/portfolio/holdings";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String brokerKey() { return "indmoney"; }

    @Override
    public Optional<List<BrokerSnapshot>> fetchSnapshots(BrokerConnection connection) {
        return fetchHoldings(connection.getAccessToken()).map(holdings -> {
            Instant now = Instant.now();
            return holdings.stream()
                    .map(h -> new BrokerSnapshot("indmoney:" + h.securityId(), h.tradingSymbol(), h.quantity(), h.marketValue(), now))
                    .toList();
        });
    }

    /**
     * This user's current demat equity holdings, read live right now — not cached; callers (the
     * daily sync job, and connect-time validation) decide when to call it. {@code Optional.empty()}
     * means "couldn't reach INDstocks, got a bad response, or the token was rejected" and must NOT
     * be treated as "zero holdings" — see this class's {@link #fetchSnapshots} and {@link
     * BrokerSyncScheduler}. Also doubles as token validation when first connecting.
     */
    public Optional<List<IndHolding>> fetchHoldings(String token) {
        if (blank(token)) return Optional.empty();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(HOLDINGS_URL))
                    .timeout(Duration.ofSeconds(8))
                    // INDstocks' own convention: the raw token value, with no "Bearer " prefix
                    // (unlike the separate INDmoney web API, which does use one).
                    .header("Authorization", token)
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("INDstocks holdings fetch failed: HTTP {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode root = json.readTree(response.body());
            JsonNode rows = root.has("data") ? root.path("data") : root;
            List<IndHolding> out = new ArrayList<>();
            for (JsonNode row : rows) {
                String symbol = row.path("trading_symbol").asText(null);
                if (symbol == null || symbol.isBlank()) continue; // malformed row — skip, don't fabricate
                String securityId = row.path("security_id").asText(symbol);
                BigDecimal quantity = decimalOf(row.path("quantity"));
                BigDecimal marketValue = row.path("market_value").isMissingNode() ? null : decimalOf(row.path("market_value"));
                out.add(new IndHolding(securityId, symbol, quantity, marketValue));
            }
            return Optional.of(out);
        } catch (Exception e) {
            log.warn("INDstocks holdings fetch failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private static BigDecimal decimalOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return BigDecimal.ZERO;
        try { return new BigDecimal(node.asText("0")); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    public record IndHolding(String securityId, String tradingSymbol, BigDecimal quantity, BigDecimal marketValue) { }
}
