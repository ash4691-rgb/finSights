package com.finsights.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * A tag the user has applied to a holding, remembered together with the holding's
 * category and valuation method so the add/edit form can suggest the tags that
 * tend to go with a given kind of holding.
 */
@Entity
@Table(name = "tag_suggestions", uniqueConstraints = @UniqueConstraint(
        name = "uk_tag_suggestion_context",
        columnNames = {"user_id", "tag", "category_id", "valuation_method"}))
public class TagSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private UserAccount user;

    @Column(nullable = false, length = 60)
    private String tag;

    @Column(name = "category_id", length = 40)
    private String categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_method", length = 20)
    private ValuationMethod valuationMethod;

    @Column(nullable = false)
    private int usageCount = 0;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

    public ValuationMethod getValuationMethod() { return valuationMethod; }
    public void setValuationMethod(ValuationMethod valuationMethod) { this.valuationMethod = valuationMethod; }

    public int getUsageCount() { return usageCount; }
    public void setUsageCount(int usageCount) { this.usageCount = usageCount; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
