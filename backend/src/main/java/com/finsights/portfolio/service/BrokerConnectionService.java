package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.BrokerConnection;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.BrokerConnectionRepository;
import com.finsights.portfolio.security.BrokerTokenEncryption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The connect/callback/disconnect side of broker linking (issue #90) — one-time, user-reviewed
 * actions, same shape as approving a CAS-extracted row (#89). The daily, unattended side (actually
 * pulling and applying a snapshot) is {@link BrokerSyncScheduler}, not this class.
 *
 * <p>Two different connect flows live here, because brokers authenticate third-party apps two
 * different ways: Kite Connect needs a central developer app (one registered with Zerodha, shared
 * across every FinSights user) and an OAuth-style login redirect — see {@link #beginConnect}/
 * {@link #completeConnect}. CoinSwitch and INDstocks have no such central app at all: each user
 * generates their own API key/secret (or bearer token) directly from their own broker account and
 * pastes it into FinSights — see {@link #connectWithCredentials}. Groww/EPFO stay PLANNED/manual
 * until each has a confirmed supported API.
 */
@Service
public class BrokerConnectionService {

    static final String KITE = "kite";
    static final String COINSWITCH = "coinswitch";
    static final String INDMONEY = "indmoney";
    private static final Set<String> CONNECTABLE = Set.of(KITE, COINSWITCH, INDMONEY);

    private final BrokerConnectionRepository connections;
    private final CurrentUserService currentUser;
    private final KiteConnectClient kite;
    private final CoinSwitchClient coinSwitch;
    private final IndmoneyClient indmoney;

    public BrokerConnectionService(BrokerConnectionRepository connections, CurrentUserService currentUser,
            KiteConnectClient kite, CoinSwitchClient coinSwitch, IndmoneyClient indmoney) {
        this.connections = connections;
        this.currentUser = currentUser;
        this.kite = kite;
        this.coinSwitch = coinSwitch;
        this.indmoney = indmoney;
    }

    public boolean isConnectable(String brokerKey) {
        return CONNECTABLE.contains(brokerKey);
    }

    public boolean kiteReady() {
        return kite.isConfigured() && BrokerTokenEncryption.isConfigured();
    }

    /** The URL to send the browser to start the login handshake. */
    public String beginConnect(String brokerKey) {
        requireKite(brokerKey);
        if (!kiteReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kite Connect isn't configured on this deployment yet");
        }
        return kite.loginUrl();
    }

    /** Called from the redirect Kite sends the browser back to, carrying a one-time
     *  request_token. Exchanges it for a session access_token and stores (or replaces) this
     *  user's connection for {@code brokerKey}. Returns false on any failure — an unreachable
     *  Kite, a rejected exchange, a missing token — never throws, so the controller can always
     *  redirect the browser back into the app with a clear success/failure signal. */
    @Transactional
    public boolean completeConnect(String brokerKey, String requestToken) {
        requireKite(brokerKey);
        if (!kiteReady()) return false;
        return kite.exchangeRequestToken(requestToken).map(session -> {
            UserAccount user = currentUser.currentUser();
            BrokerConnection connection = connections.findByUser_IdAndBrokerKey(user.getId(), brokerKey)
                    .orElseGet(BrokerConnection::new);
            connection.setUser(user);
            connection.setBrokerKey(brokerKey);
            connection.setAccessToken(session.accessToken());
            connection.setExpiresAt(nextKiteSessionReset());
            connection.setConnectedAt(Instant.now());
            connection.setLastSyncError(null);
            connections.save(connection);
            return true;
        }).orElse(false);
    }

    /**
     * The paste-your-own-credentials connect flow for brokers with no central developer app
     * (CoinSwitch, INDstocks) — unlike {@link #beginConnect}'s OAuth redirect, there's nothing to
     * register with the broker ahead of time; each user hands us a credential they generated
     * themselves. Validates by making one live call before storing anything — never store a
     * credential we haven't confirmed actually works, same principle as {@link #completeConnect}'s
     * live token exchange. Throws (never silently fails) since this is a synchronous form
     * submission, not a browser redirect — the controller lets the exception surface as the
     * form's error message.
     */
    @Transactional
    public void connectWithCredentials(String brokerKey, String apiKey, String apiSecret) {
        if (KITE.equals(brokerKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kite connects via the login redirect, not a pasted key");
        }
        if (!CONNECTABLE.contains(brokerKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or not-yet-connectable broker: " + brokerKey);
        }
        if (!BrokerTokenEncryption.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Broker credential storage isn't configured on this deployment yet");
        }
        if (blank(apiKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An API key is required");
        }
        boolean valid = switch (brokerKey) {
            case COINSWITCH -> !blank(apiSecret) && coinSwitch.fetchPortfolio(apiKey, apiSecret).isPresent();
            case INDMONEY -> indmoney.fetchHoldings(apiKey).isPresent();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported broker for API-key connect: " + brokerKey);
        };
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Couldn't verify these credentials with " + brokerKey + " — double check them and try again.");
        }
        UserAccount user = currentUser.currentUser();
        BrokerConnection connection = connections.findByUser_IdAndBrokerKey(user.getId(), brokerKey).orElseGet(BrokerConnection::new);
        connection.setUser(user);
        connection.setBrokerKey(brokerKey);
        connection.setAccessToken(apiKey);
        connection.setRefreshToken(COINSWITCH.equals(brokerKey) ? apiSecret : null);
        // Neither broker publishes a predictable expiry the way Kite's daily session reset does —
        // statusFor()'s needsReauth stays false here until a real sync failure says otherwise.
        connection.setExpiresAt(null);
        connection.setConnectedAt(Instant.now());
        connection.setLastSyncError(null);
        connections.save(connection);
    }

    /** Unlinking a broker connection doesn't change past holdings — it just stops future syncs
     *  (see issue #90). Deleting this row is enough: each already-synced Holding is its own
     *  record, never cascaded from BrokerConnection, so it keeps its last BROKER_SYNC value
     *  exactly as-is until the user edits it (e.g. switching it back to MANUAL). */
    @Transactional
    public void disconnect(String brokerKey) {
        requireConnectable(brokerKey);
        connections.findByUser_IdAndBrokerKey(currentUser.currentUser().getId(), brokerKey)
                .ifPresent(connections::delete);
    }

    public Optional<ConnectionStatus> statusFor(String userId, String brokerKey) {
        if (!isConnectable(brokerKey)) return Optional.empty();
        return connections.findByUser_IdAndBrokerKey(userId, brokerKey).map(c -> new ConnectionStatus(
                c.getLastSyncedAt(), c.getExpiresAt() != null && c.getExpiresAt().isBefore(Instant.now()), c.getLastSyncError()));
    }

    /** Kite access tokens expire at the start of the next trading day (~6am IST) regardless of
     *  when during the day they were issued — there's no fixed TTL to add, just "tomorrow
     *  morning." Purely advisory for the UI's "needs re-auth" flag; the next sync's own 4xx from
     *  Kite is the real signal and updates lastSyncError regardless of what this predicted. */
    private Instant nextKiteSessionReset() {
        return ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                .plusDays(1).withHour(6).withMinute(0).withSecond(0).withNano(0).toInstant();
    }

    /** {@link #beginConnect}/{@link #completeConnect} are Kite's own OAuth-redirect flow — not a
     *  generic "is this broker connectable at all" check, which is {@link #isConnectable}. */
    private void requireKite(String brokerKey) {
        if (!KITE.equals(brokerKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported broker, or this broker doesn't use the login-redirect flow: " + brokerKey);
        }
    }

    private void requireConnectable(String brokerKey) {
        if (!isConnectable(brokerKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or not-yet-connectable broker: " + brokerKey);
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    public record ConnectionStatus(Instant lastSyncedAt, boolean needsReauth, String lastSyncError) { }
}
