package com.finsights.portfolio.api;

import com.finsights.portfolio.domain.BrokerConnection;
import com.finsights.portfolio.dto.BrokersResponse;
import com.finsights.portfolio.dto.SyncNowResponse;
import com.finsights.portfolio.service.BrokerConnectionService;
import com.finsights.portfolio.service.BrokerService;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/brokers")
public class BrokerController {
    private final BrokerService brokers;
    private final BrokerConnectionService connections;
    @Value("${app.frontend-url}") private String frontendUrl;

    public BrokerController(BrokerService brokers, BrokerConnectionService connections) {
        this.brokers = brokers;
        this.connections = connections;
    }

    @GetMapping
    BrokersResponse overview(@RequestParam(required = false) String currency) { return brokers.overview(currency); }

    /** Returns the URL to send the browser to — the frontend does {@code window.location.href =
     *  url}, the same pattern LoginScreen already uses for Google's own OAuth handoff. */
    @GetMapping("/{key}/connect")
    Map<String, String> connect(@PathVariable String key) {
        return Map.of("url", connections.beginConnect(key));
    }

    /** Where Kite's login redirect lands — a real browser navigation (not XHR), carrying our own
     *  session cookie same as any other page view, so completeConnect() can resolve the current
     *  user normally. Always redirects the browser back into the app; never returns JSON, since
     *  nothing but a browser ever hits this URL. */
    @GetMapping("/{key}/callback")
    ResponseEntity<Void> callback(@PathVariable String key,
                                   @RequestParam(value = "request_token", required = false) String requestToken,
                                   @RequestParam(required = false) String status) {
        boolean connected = "success".equals(status) && connections.completeConnect(key, requestToken);
        String redirect = frontendUrl + "/?broker=" + key + "&brokerStatus=" + (connected ? "connected" : "failed");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirect)).build();
    }

    /** The paste-your-own-credentials connect flow (CoinSwitch, INDstocks) — a plain JSON form
     *  submission, not a browser redirect, so unlike {@link #callback} a failed validation
     *  surfaces as a normal 4xx with a message the form can show directly. */
    @PostMapping("/{key}/connect-with-key")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void connectWithKey(@PathVariable String key, @RequestBody ConnectWithKeyRequest body) {
        connections.connectWithCredentials(key, body.apiKey(), body.apiSecret());
    }

    @DeleteMapping("/{key}/connect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(@PathVariable String key) { connections.disconnect(key); }

    /** "Sync now" — pulls this connection's holdings right away instead of waiting for the daily
     *  scheduled job. A fetch failure (broker unreachable this round) is a routine outcome, not
     *  an error response: it comes back as {@code synced: false} with {@code lastSyncError} set,
     *  same information the Brokers page already shows for the scheduled job's own failures. */
    @PostMapping("/{key}/sync-now")
    SyncNowResponse syncNow(@PathVariable String key) {
        BrokerConnection connection = connections.syncNow(key);
        return new SyncNowResponse(connection.getLastSyncError() == null, connection.getLastSyncedAt(), connection.getLastSyncError());
    }

    public record ConnectWithKeyRequest(String apiKey, String apiSecret) { }
}
