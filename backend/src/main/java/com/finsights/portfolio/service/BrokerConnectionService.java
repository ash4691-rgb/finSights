package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.BrokerConnection;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.BrokerConnectionRepository;
import com.finsights.portfolio.security.BrokerTokenEncryption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The connect/callback/disconnect side of broker linking (issue #90) — one-time, user-reviewed
 * actions, same shape as approving a CAS-extracted row (#89). The daily, unattended side (actually
 * pulling and applying a snapshot) is {@link BrokerSyncScheduler}, not this class.
 */
@Service
public class BrokerConnectionService {

    /** The only broker with a real connect flow in v1 — Groww/INDmoney/EPFO stay PLANNED/manual
     *  until each has a confirmed supported API (see issue #90's non-goals). */
    static final String KITE = "kite";

    private final BrokerConnectionRepository connections;
    private final CurrentUserService currentUser;
    private final KiteConnectClient kite;

    public BrokerConnectionService(BrokerConnectionRepository connections, CurrentUserService currentUser, KiteConnectClient kite) {
        this.connections = connections;
        this.currentUser = currentUser;
        this.kite = kite;
    }

    public boolean isConnectable(String brokerKey) {
        return KITE.equals(brokerKey);
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

    /** Unlinking a broker connection doesn't change past holdings — it just stops future syncs
     *  (see issue #90). Deleting this row is enough: each already-synced Holding is its own
     *  record, never cascaded from BrokerConnection, so it keeps its last BROKER_SYNC value
     *  exactly as-is until the user edits it (e.g. switching it back to MANUAL). */
    @Transactional
    public void disconnect(String brokerKey) {
        requireKite(brokerKey);
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

    private void requireKite(String brokerKey) {
        if (!isConnectable(brokerKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or not-yet-connectable broker: " + brokerKey);
        }
    }

    public record ConnectionStatus(Instant lastSyncedAt, boolean needsReauth, String lastSyncError) { }
}
