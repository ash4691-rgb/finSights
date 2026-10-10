package com.finsights.portfolio.domain;

import com.finsights.portfolio.security.BrokerTokenConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One user's linked broker account — currently only Zerodha Kite Connect (see issue #90).
 * {@code accessToken}/{@code refreshToken} are encrypted at rest (see {@link BrokerTokenConverter});
 * everything else here is plain so the daily sync scheduler and the Brokers page can read
 * connection status (last synced, needs re-auth) without ever touching the encryptor.
 *
 * <p>Kite Connect v3 has no refresh-token flow — its access tokens expire daily and require the
 * user to re-authenticate through the login redirect, not a silent background refresh. {@code
 * refreshToken} is kept nullable and unused for Kite specifically, but named generically (not
 * {@code kiteRefreshToken}) since a future broker with a real refresh flow can reuse this column.
 */
@Entity
@Table(name = "broker_connections", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "broker_key" }))
public class BrokerConnection {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;
    /** Matches BrokerService.SOURCES' keys — "kite" for v1. */
    @Column(name = "broker_key", nullable = false, length = 32)
    private String brokerKey;
    @Convert(converter = BrokerTokenConverter.class)
    @Column(name = "access_token", nullable = false, length = 1024)
    private String accessToken;
    @Convert(converter = BrokerTokenConverter.class)
    @Column(name = "refresh_token", length = 1024)
    private String refreshToken;
    /** Best-effort estimate of when accessToken stops working (see the class doc) — advisory for
     *  the UI's "needs re-auth" flag, never authoritative; the next sync's own 4xx response from
     *  Kite is the real signal, and updates lastSyncError regardless of what this predicted. */
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt = Instant.now();
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
    @Column(name = "last_sync_error", length = 512)
    private String lastSyncError;

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getBrokerKey() { return brokerKey; }
    public void setBrokerKey(String brokerKey) { this.brokerKey = brokerKey; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConnectedAt() { return connectedAt; }
    public void setConnectedAt(Instant connectedAt) { this.connectedAt = connectedAt; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public String getLastSyncError() { return lastSyncError; }
    public void setLastSyncError(String lastSyncError) { this.lastSyncError = lastSyncError; }
}
