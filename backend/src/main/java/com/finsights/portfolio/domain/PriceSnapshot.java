package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One recorded value, for either a {@link Holding} (MANUAL/MARKET_PRICE/BROKER_SYNC only — FIXED_RATE
 * is valued analytically and never snapshotted) or a {@link WatchlistItem}, at a point in time.
 * Insights' Hot picks feature diffs the latest snapshot against the closest one at/before each
 * lookback window (daily/weekly/monthly/quarterly/yearly) to compute a movement percentage.
 */
@Entity
@Table(name = "price_snapshots")
public class PriceSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private UserAccount user;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SnapshotSubject subjectType;
    @Column(nullable = false)
    private String subjectId;
    @Column(name = "amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal value;
    @Column(nullable = false)
    private Instant recordedAt = Instant.now();

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public SnapshotSubject getSubjectType() { return subjectType; }
    public void setSubjectType(SnapshotSubject subjectType) { this.subjectType = subjectType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
