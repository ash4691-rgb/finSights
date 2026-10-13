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
 * The connect/callback/disconnect/sync-now side of broker linking (issue #90) — user-initiated
 * actions, same shape as approving a CAS-extracted row (#89). The unattended daily side (looping
 * over every connection) is {@link BrokerSyncScheduler}; the actual per-connection fetch-and-apply
 * logic both that and {@link #syncNow} delegate to is {@link BrokerSyncService}.
 *
 * <p>Two different connect flows live here, because brokers authenticate third-party apps
 * different ways: Kite Connect needs a central developer app (one registered with Zerodha, shared
 * across every FinSights user) and an OAuth-style login redirect — see {@link #beginConnect}/
 * {@link #completeConnect}. CoinSwitch, INDstocks, and EPFO have no such central app at all: each
 * user hands FinSights a credential tied to their own account directly — an API key/secret, a
 * bearer token, or (EPFO) their own portal UAN/password — and pastes it in; see {@link
 * #connectWithCredentials}. EPFO's credential is categorically riskier than the others (a
 * government-portal password, not a revocable scoped API key) and has no real API behind it at
 * all — see {@link EpfoClient}'s class doc for why its live validation is expected to fail far
 * more often than the other three. Groww stays PLANNED until it has a confirmed supported API.
 */
@Service
public class BrokerConnectionService {

    static final String KITE = "kite";
    static final String COINSWITCH = "coinswitch";
    static final String INDMONEY = "indmoney";
    static final String EPFO = "epfo";
    private static final Set<String> CONNECTABLE = Set.of(KITE, COINSWITCH, INDMONEY, EPFO);

    private final BrokerConnectionRepository connections;
    private final CurrentUserService currentUser;
    private final KiteConnectClient kite;
    private final CoinSwitchClient coinSwitch;
    private final IndmoneyClient indmoney;
    private final EpfoClient epfo;
    private final BrokerSyncService syncService;

    public BrokerConnectionService(BrokerConnectionRepository connections, CurrentUserService currentUser,
            KiteConnectClient kite, CoinSwitchClient coinSwitch, IndmoneyClient indmoney, EpfoClient epfo,
            BrokerSyncService syncService) {
        this.connections = connections;
        this.currentUser = currentUser;
        this.kite = kite;
        this.coinSwitch = coinSwitch;
        this.indmoney = indmoney;
        this.epfo = epfo;
        this.syncService = syncService;
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

    /** Brokers whose first pasted field is a second, non-null secret stored in {@code
     *  refreshToken} rather than left null — CoinSwitch's API secret, EPFO's portal password. */
    private static final Set<String> USES_SECOND_FIELD = Set.of(COINSWITCH, EPFO);

    /**
     * The paste-your-own-credentials connect flow for brokers with no central developer app
     * (CoinSwitch, INDstocks, EPFO) — unlike {@link #beginConnect}'s OAuth redirect, there's
     * nothing to register with the broker ahead of time; each user hands us a credential tied to
     * their own account directly. Validates by making one live call before storing anything —
     * never store a credential we haven't confirmed actually works, same principle as {@link
     * #completeConnect}'s live token exchange. Throws (never silently fails) since this is a
     * synchronous form submission, not a browser redirect — the controller lets the exception
     * surface as the form's error message.
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, EPFO.equals(brokerKey) ? "A UAN is required" : "An API key is required");
        }
        boolean valid = switch (brokerKey) {
            case COINSWITCH -> !blank(apiSecret) && coinSwitch.fetchPortfolio(apiKey, apiSecret).isPresent();
            case INDMONEY -> indmoney.fetchHoldings(apiKey).isPresent();
            case EPFO -> !blank(apiSecret) && epfo.fetchBalance(apiKey, apiSecret).isPresent();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported broker for API-key connect: " + brokerKey);
        };
        if (!valid) {
            String hint = EPFO.equals(brokerKey)
                    ? " — this is very likely EPFO's login CAPTCHA, which this integration can't solve yet, rather than a wrong UAN/password."
                    : " — double check them and try again.";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Couldn't verify these credentials with " + brokerKey + hint);
        }
        UserAccount user = currentUser.currentUser();
        BrokerConnection connection = connections.findByUser_IdAndBrokerKey(user.getId(), brokerKey).orElseGet(BrokerConnection::new);
        connection.setUser(user);
        connection.setBrokerKey(brokerKey);
        connection.setAccessToken(apiKey);
        connection.setRefreshToken(USES_SECOND_FIELD.contains(brokerKey) ? apiSecret : null);
        // None of these publish a predictable expiry the way Kite's daily session reset does —
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

    /** The "Sync now" button — fetches and applies this user's connection right now instead of
     *  waiting for the daily scheduled job. Delegates the actual fetch to {@link
     *  BrokerSyncService#syncOne}, which is also what the scheduler itself calls, so both paths
     *  share the exact same "a failed fetch is never zero holdings" safety behavior. Returns the
     *  updated connection (never throws for a fetch failure itself — that's recorded onto the
     *  connection's own lastSyncError, same as the scheduled job, and the controller reads it
     *  back off the return value rather than an exception). */
    @Transactional
    public BrokerConnection syncNow(String brokerKey) {
        requireConnectable(brokerKey);
        BrokerConnection connection = connections.findByUser_IdAndBrokerKey(currentUser.currentUser().getId(), brokerKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not connected to " + brokerKey + " yet"));
        syncService.syncOne(connection);
        return connection;
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
