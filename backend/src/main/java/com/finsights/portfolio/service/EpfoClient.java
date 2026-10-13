package com.finsights.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.BrokerConnection;
import com.finsights.portfolio.dto.BrokerSnapshot;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Talks to EPFO's member portal to read a member's current PF balance (principal + interest
 * credited, as EPFO's own passbook reports it as a single figure — there's no separate
 * "transaction" concept here the way there is for Kite/CoinSwitch/INDstocks holdings).
 *
 * <p><b>Read this before relying on it.</b> Unlike Kite Connect, CoinSwitch, or INDstocks — all of
 * which publish a real API for exactly this purpose — EPFO has no public third-party API at all
 * (confirmed via public search: see the PR description). This client logs in using the member's
 * own UAN and portal password, same as logging into unifiedportal-mem.epfindia.gov.in by hand,
 * per explicit user direction despite that gap. Two consequences follow directly from that:
 * <ul>
 * <li><b>The login step almost certainly needs a CAPTCHA this client cannot solve.</b> EPFO's own
 * member login page requires an on-screen CAPTCHA (confirmed via public search — no official docs
 * exist to verify further). A plain UAN+password POST, with no CAPTCHA token, is expected to be
 * rejected by the real portal every time, regardless of whether the credentials themselves are
 * correct. Until a CAPTCHA-solving step is added, treat "Sync now"/the connect flow's live
 * validation as very likely to always fail here — this is a known, structural gap, not a bug to
 * chase in the usual sense.
 * <li><b>The exact request shape below is a best-effort placeholder, not a verified spec.</b> No
 * official EPFO API documentation exists publicly; the login/passbook endpoints and field names
 * here are inferred from the portal's known base URL and conventional patterns, the same way
 * Kite/CoinSwitch/INDstocks were built against public docs — except there IS no public doc to
 * build this one against. Confirm and adjust against the real portal's actual network traffic
 * before relying on this.
 * </ul>
 * Same never-throws, degrade-to-"no data" contract as every other broker client in this codebase.
 */
@Service
public class EpfoClient implements BrokerDataClient {

    private static final Logger log = LoggerFactory.getLogger(EpfoClient.class);
    private static final String LOGIN_URL = "https://unifiedportal-mem.epfindia.gov.in/memberinterface/";
    private static final String PASSBOOK_URL = "https://passbook.epfindia.gov.in/MemberPassBook/login";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .cookieHandler(new java.net.CookieManager()).build();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String brokerKey() { return "epfo"; }

    @Override
    public Optional<List<BrokerSnapshot>> fetchSnapshots(BrokerConnection connection) {
        return fetchBalance(connection.getAccessToken(), connection.getRefreshToken()).map(balance -> {
            Instant now = Instant.now();
            // One row per EPFO account — there's no concept of multiple "holdings" here the way
            // a demat account has multiple stocks, just one PF balance. "quantity" is a nominal 1.
            return List.of(new BrokerSnapshot("epfo:" + connection.getAccessToken(), "EPF Balance", BigDecimal.ONE, balance, now));
        });
    }

    /**
     * This user's current EPF balance (principal + interest credited so far, as the member
     * passbook itself reports it), read live right now. {@code Optional.empty()} means "couldn't
     * log in, couldn't reach the portal, or got a response this client couldn't parse" — see the
     * CAPTCHA caveat in the class doc for why that's the overwhelmingly likely outcome in
     * practice right now. Also doubles as credential validation when first connecting.
     */
    public Optional<BigDecimal> fetchBalance(String uan, String password) {
        if (blank(uan) || blank(password)) return Optional.empty();
        try {
            if (!login(uan, password)) return Optional.empty();
            HttpRequest request = HttpRequest.newBuilder(URI.create(PASSBOOK_URL))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("EPFO passbook fetch failed: HTTP {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode root = json.readTree(response.body());
            JsonNode balanceNode = root.path("pfBalance").isMissingNode() ? root.path("balance") : root.path("pfBalance");
            if (balanceNode.isMissingNode() || balanceNode.isNull()) return Optional.empty();
            return Optional.of(new BigDecimal(balanceNode.asText("0")));
        } catch (Exception e) {
            log.warn("EPFO balance fetch failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** @return true only on an unambiguous successful login (HTTP 2xx). Everything else —
     *  including the CAPTCHA rejection this will almost always hit — degrades to false. */
    private boolean login(String uan, String password) throws Exception {
        String body = "username=" + enc(uan) + "&password=" + enc(password);
        HttpRequest request = HttpRequest.newBuilder(URI.create(LOGIN_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("EPFO login failed: HTTP {} (a CAPTCHA requirement is the most likely cause — see EpfoClient's class doc)",
                    response.statusCode());
            return false;
        }
        return true;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
