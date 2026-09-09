package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-user, per-period movement thresholds that drive Top movers — an independent up % and down %
 * for each lookback window (see {@link com.finsights.portfolio.service.MovementService#PERIOD_DAYS}).
 * One row per user, created lazily on first save.
 */
@Entity
@Table(name = "movement_thresholds")
public class MovementThreshold {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(unique = true)
    private UserAccount user;

    @Column(precision = 6, scale = 2) private BigDecimal dailyUpPercent;
    @Column(precision = 6, scale = 2) private BigDecimal dailyDownPercent;
    @Column(precision = 6, scale = 2) private BigDecimal weeklyUpPercent;
    @Column(precision = 6, scale = 2) private BigDecimal weeklyDownPercent;
    @Column(precision = 6, scale = 2) private BigDecimal monthlyUpPercent;
    @Column(precision = 6, scale = 2) private BigDecimal monthlyDownPercent;
    @Column(precision = 6, scale = 2) private BigDecimal quarterlyUpPercent;
    @Column(precision = 6, scale = 2) private BigDecimal quarterlyDownPercent;
    @Column(precision = 6, scale = 2) private BigDecimal yearlyUpPercent;
    @Column(precision = 6, scale = 2) private BigDecimal yearlyDownPercent;
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public BigDecimal getDailyUpPercent() { return dailyUpPercent; }
    public void setDailyUpPercent(BigDecimal value) { this.dailyUpPercent = value; }
    public BigDecimal getDailyDownPercent() { return dailyDownPercent; }
    public void setDailyDownPercent(BigDecimal value) { this.dailyDownPercent = value; }
    public BigDecimal getWeeklyUpPercent() { return weeklyUpPercent; }
    public void setWeeklyUpPercent(BigDecimal value) { this.weeklyUpPercent = value; }
    public BigDecimal getWeeklyDownPercent() { return weeklyDownPercent; }
    public void setWeeklyDownPercent(BigDecimal value) { this.weeklyDownPercent = value; }
    public BigDecimal getMonthlyUpPercent() { return monthlyUpPercent; }
    public void setMonthlyUpPercent(BigDecimal value) { this.monthlyUpPercent = value; }
    public BigDecimal getMonthlyDownPercent() { return monthlyDownPercent; }
    public void setMonthlyDownPercent(BigDecimal value) { this.monthlyDownPercent = value; }
    public BigDecimal getQuarterlyUpPercent() { return quarterlyUpPercent; }
    public void setQuarterlyUpPercent(BigDecimal value) { this.quarterlyUpPercent = value; }
    public BigDecimal getQuarterlyDownPercent() { return quarterlyDownPercent; }
    public void setQuarterlyDownPercent(BigDecimal value) { this.quarterlyDownPercent = value; }
    public BigDecimal getYearlyUpPercent() { return yearlyUpPercent; }
    public void setYearlyUpPercent(BigDecimal value) { this.yearlyUpPercent = value; }
    public BigDecimal getYearlyDownPercent() { return yearlyDownPercent; }
    public void setYearlyDownPercent(BigDecimal value) { this.yearlyDownPercent = value; }
    public Instant getUpdatedAt() { return updatedAt; }
}
