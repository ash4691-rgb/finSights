package com.finsights.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A broker-synced instrument {@link com.finsights.portfolio.service.BrokerHoldingSyncConsumer}
 * couldn't auto-apply onto a {@link Holding} — it surfaces in the Action Centre instead (see
 * {@code ActionCentreService}) until the user resolves it, one way or the other. One row per
 * (user, brokerSyncRef): a later sync of the same instrument refreshes this row's quantity/value
 * in place rather than creating a duplicate, so the Action Centre never shows the same pending
 * instrument twice.
 */
@Entity
@Table(name = "pending_broker_holdings")
public class PendingBrokerHolding {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;
    @Column(name = "broker_key", nullable = false, length = 32)
    private String brokerKey;
    /** Matches {@link Holding#getBrokerSyncRef()} once this becomes a real holding. */
    @Column(name = "broker_sync_ref", nullable = false, length = 160)
    private String brokerSyncRef;
    /** The broker's own symbol/name for this instrument — pre-fills the holding name if created. */
    @Column(nullable = false, length = 128)
    private String suggestedName;
    @Column(precision = 24, scale = 8)
    private BigDecimal quantity;
    @Column(precision = 20, scale = 2)
    private BigDecimal currentValue;
    private Instant asOf;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PendingBrokerHoldingReason reason;
    /** Set only for {@link PendingBrokerHoldingReason#NAME_CONFLICT} — the existing holding this
     *  instrument's name collided with, which the user can link instead of creating a duplicate. */
    @Column(name = "conflicting_holding_id")
    private String conflictingHoldingId;
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getBrokerKey() { return brokerKey; }
    public void setBrokerKey(String brokerKey) { this.brokerKey = brokerKey; }
    public String getBrokerSyncRef() { return brokerSyncRef; }
    public void setBrokerSyncRef(String brokerSyncRef) { this.brokerSyncRef = brokerSyncRef; }
    public String getSuggestedName() { return suggestedName; }
    public void setSuggestedName(String suggestedName) { this.suggestedName = suggestedName; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public Instant getAsOf() { return asOf; }
    public void setAsOf(Instant asOf) { this.asOf = asOf; }
    public PendingBrokerHoldingReason getReason() { return reason; }
    public void setReason(PendingBrokerHoldingReason reason) { this.reason = reason; }
    public String getConflictingHoldingId() { return conflictingHoldingId; }
    public void setConflictingHoldingId(String conflictingHoldingId) { this.conflictingHoldingId = conflictingHoldingId; }
    public Instant getCreatedAt() { return createdAt; }
}
