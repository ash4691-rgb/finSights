package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A user-defined bucket ("Retirement", "Emergency Fund", "Growth Equity"…) that holdings are
 * filed under. Deliberately thin — just a name and Asset/Liability type; invested/current
 * value, P&amp;L, weightage, and liquid/NPA amounts are rollups computed from its holdings.
 */
@Entity
@Table(name = "categories")
public class Category {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount user;
    @Column(nullable = false, length = 128)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private HoldingKind kind;
    /** Short one-liner shown in the ⓘ hover on the Categories table. */
    @Column(length = 1024)
    private String description;
    /** Valuation methods holdings filed under this category may use. Empty means no restriction
     *  (every method is allowed) — the default for categories created before this field existed. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "category_valuation_methods", joinColumns = @JoinColumn(name = "category_id"))
    @Enumerated(EnumType.STRING) @Column(name = "valuation_method")
    private Set<ValuationMethod> allowedValuationMethods = new LinkedHashSet<>();
    /** Manual drag-to-reorder position within the Categories table; new categories append to the end. */
    @Column(nullable = false)
    private int sortOrder = 0;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    @Version private long version;

    public Category() { }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public HoldingKind getKind() { return kind; }
    public void setKind(HoldingKind kind) { this.kind = kind; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Set<ValuationMethod> getAllowedValuationMethods() { return allowedValuationMethods; }
    public void setAllowedValuationMethods(Set<ValuationMethod> allowedValuationMethods) { this.allowedValuationMethods = allowedValuationMethods; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
