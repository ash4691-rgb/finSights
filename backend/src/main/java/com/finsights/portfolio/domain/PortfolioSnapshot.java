package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One category's invested and current value, captured once a week (keyed on the Monday
 * of the ISO week). Powers the portfolio timeline on the Insights page. Category name and
 * kind are denormalised so the trail survives a category being renamed or removed.
 */
@Entity
@Table(name = "portfolio_snapshots", uniqueConstraints = @UniqueConstraint(
        name = "uk_portfolio_snapshot_week", columnNames = {"user_id", "category_id", "week_of"}))
public class PortfolioSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount user;
    @Column(name = "category_id", nullable = false)
    private String categoryId;
    @Column(nullable = false)
    private String categoryName;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private HoldingKind kind;
    @Column(name = "week_of", nullable = false)
    private LocalDate weekOf;
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal investedValue = BigDecimal.ZERO;
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal currentValue = BigDecimal.ZERO;
    @Column(nullable = false)
    private Instant recordedAt = Instant.now();

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public HoldingKind getKind() { return kind; }
    public void setKind(HoldingKind kind) { this.kind = kind; }
    public LocalDate getWeekOf() { return weekOf; }
    public void setWeekOf(LocalDate weekOf) { this.weekOf = weekOf; }
    public BigDecimal getInvestedValue() { return investedValue; }
    public void setInvestedValue(BigDecimal investedValue) { this.investedValue = investedValue; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
