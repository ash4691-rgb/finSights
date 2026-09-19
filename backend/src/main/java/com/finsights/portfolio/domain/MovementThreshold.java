package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-user, per-period movement thresholds that drive Top movers — a single % for each lookback
 * window (see {@link com.finsights.portfolio.service.MovementService#PERIOD_DAYS}); a move past it
 * in either direction, up or down, counts. One row per user, created lazily on first save.
 */
@Entity
@Table(name = "movement_thresholds")
public class MovementThreshold {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(unique = true)
    private UserAccount user;

    @Column(precision = 6, scale = 2) private BigDecimal dailyPercent;
    @Column(precision = 6, scale = 2) private BigDecimal weeklyPercent;
    @Column(precision = 6, scale = 2) private BigDecimal monthlyPercent;
    @Column(precision = 6, scale = 2) private BigDecimal quarterlyPercent;
    @Column(precision = 6, scale = 2) private BigDecimal yearlyPercent;
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public BigDecimal getDailyPercent() { return dailyPercent; }
    public void setDailyPercent(BigDecimal value) { this.dailyPercent = value; }
    public BigDecimal getWeeklyPercent() { return weeklyPercent; }
    public void setWeeklyPercent(BigDecimal value) { this.weeklyPercent = value; }
    public BigDecimal getMonthlyPercent() { return monthlyPercent; }
    public void setMonthlyPercent(BigDecimal value) { this.monthlyPercent = value; }
    public BigDecimal getQuarterlyPercent() { return quarterlyPercent; }
    public void setQuarterlyPercent(BigDecimal value) { this.quarterlyPercent = value; }
    public BigDecimal getYearlyPercent() { return yearlyPercent; }
    public void setYearlyPercent(BigDecimal value) { this.yearlyPercent = value; }
    public Instant getUpdatedAt() { return updatedAt; }
}
