package com.finsights.portfolio.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/** A savings goal, in the user's base currency. No linked categories = measured against all assets. */
@Entity
@Table(name = "goals")
public class Goal {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(name = "user_id", nullable = false)
    private String userId;
    @Column(nullable = false, length = 128)
    private String name;
    @Column(name = "target_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal targetAmount;
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;
    @Column(name = "monthly_contribution", nullable = false, precision = 20, scale = 2)
    private BigDecimal monthlyContribution = BigDecimal.ZERO;
    @Column(name = "expected_return_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal expectedReturnPercent;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "goal_categories", joinColumns = @JoinColumn(name = "goal_id"))
    @Column(name = "category_id", nullable = false)
    private Set<String> categoryIds = new LinkedHashSet<>();
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Goal() { }

    public Goal(String userId) {
        this.userId = userId;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getTargetAmount() { return targetAmount; }
    public void setTargetAmount(BigDecimal targetAmount) { this.targetAmount = targetAmount; }
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
    public BigDecimal getMonthlyContribution() { return monthlyContribution; }
    public void setMonthlyContribution(BigDecimal monthlyContribution) { this.monthlyContribution = monthlyContribution; }
    public BigDecimal getExpectedReturnPercent() { return expectedReturnPercent; }
    public void setExpectedReturnPercent(BigDecimal expectedReturnPercent) { this.expectedReturnPercent = expectedReturnPercent; }
    public Set<String> getCategoryIds() { return categoryIds; }
    public void setCategoryIds(Set<String> categoryIds) { this.categoryIds = new LinkedHashSet<>(categoryIds); }
    public Instant getCreatedAt() { return createdAt; }
}
