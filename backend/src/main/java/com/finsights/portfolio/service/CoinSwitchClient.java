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
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Talks to CoinSwitch PRO's trading API to read a user's crypto wallet balances
 * (https://coinswitch.co/switch/crypto/api-trading-setup-guide/). Unlike Kite Connect, there's no
 * central developer app to register with CoinSwitch — each user generates their own Ed25519 API
 * key/secret pair from their own account (Profile → API Trading) and pastes it directly into
 * FinSights; see {@link BrokerConnectionService#connectWithCredentials}. Built against the public
 * setup guide and a community reference client, not verified live (no CoinSwitch account/keys in
 * this environment) — same never-throws, degrade-to-"no data" contract as every other broker
 * client in this codebase.
 */
@Service
public class CoinSwitchClient implements BrokerDataClient {

    private static final Logger log = LoggerFactory.getLogger(CoinSwitchClient.class);
    private static final String BASE_URL = "https://coinswitch.co";
    private static final String PORTFOLIO_PATH = "/trade/api/v2/user/portfolio";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String brokerKey() { return "coinswitch"; }

    @Override
    public Optional<List<BrokerSnapshot>> fetchSnapshots(BrokerConnection connection) {
        return fetchPortfolio(connection.getAccessToken(), connection.getRefreshToken()).map(holdings -> {
            Instant now = Instant.now();
            // currentValue is left null here — CoinSwitch's portfolio response (per the reference
            // client this was built against) exposes main_balance/blocked_balance_order but no
            // confirmed INR-value field; fill this in once a live response confirms the field name.
            return holdings.stream().map(h -> new BrokerSnapshot("coinswitch:" + h.currency(), h.quantity(), null, now)).toList();
        });
    }

    /**
     * This user's current crypto wallet balances, read live right now — not cached; callers (the
     * daily sync job, and connect-time validation) decide when to call it. {@code Optional.empty()}
     * means "couldn't reach CoinSwitch, got a bad response, or the credentials were rejected" and
     * must NOT be treated as "zero holdings" — see this class's {@link #fetchSnapshots} and
     * {@link BrokerSyncScheduler}. Also doubles as credential validation when first connecting: a
     * live, successful call is the only real proof a pasted key/secret pair actually works.
     */
    public Optional<List<CoinSwitchHolding>> fetchPortfolio(String apiKey, String apiSecret) {
        if (blank(apiKey) || blank(apiSecret)) return Optional.empty();
        try {
            String epoch = String.valueOf(Instant.now().toEpochMilli());
            String signature = sign(apiSecret, "GET" + PORTFOLIO_PATH + epoch);
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + PORTFOLIO_PATH))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("X-AUTH-APIKEY", apiKey)
                    .header("X-AUTH-SIGNATURE", signature)
                    .header("X-AUTH-EPOCH", epoch)
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("CoinSwitch portfolio fetch failed: HTTP {}", response.statusCode());
                return Optional.empty();
            }
            List<CoinSwitchHolding> out = new ArrayList<>();
            for (JsonNode row : json.readTree(response.body()).path("data")) {
                String currency = row.path("currency").asText(null);
                if (currency == null || currency.isBlank()) continue; // malformed row — skip, don't fabricate
                BigDecimal quantity = decimalOf(row.path("main_balance")).add(decimalOf(row.path("blocked_balance_order")));
                if (quantity.signum() <= 0) continue; // nothing actually held in this currency
                out.add(new CoinSwitchHolding(currency, quantity));
            }
            return Optional.of(out);
        } catch (Exception e) {
            log.warn("CoinSwitch portfolio fetch failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** CoinSwitch's own signing scheme: Ed25519-sign the UTF-8 bytes of {@code method + path +
     *  epoch} (no separators, no query string handling needed since this endpoint takes none),
     *  using the hex-decoded secret as the raw 32-byte Ed25519 seed, hex-encoding the result.
     *  Package-private (not private) so it's directly unit-testable — the crypto plumbing itself
     *  must be proven correct independent of this sandbox's blocked network, which would
     *  otherwise make a broken signature implementation look identical to "no network" (both
     *  degrade to {@code Optional.empty()} from {@link #fetchPortfolio}). */
    static String sign(String hexSecret, String message) throws Exception {
        byte[] seed = HexFormat.of().parseHex(hexSecret);
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(signer.sign());
    }

    private static BigDecimal decimalOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return BigDecimal.ZERO;
        try { return new BigDecimal(node.asText("0")); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    public record CoinSwitchHolding(String currency, BigDecimal quantity) { }
}
