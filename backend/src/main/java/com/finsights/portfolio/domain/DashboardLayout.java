package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** A user's saved widget/zone arrangement for one editable-layout page, as opaque JSON. */
@Entity
@Table(name = "dashboard_layouts",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "page"}))
public class DashboardLayout {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private UserAccount user;
    @Column(nullable = false, length = 32)
    private String page;
    @Lob @Column(nullable = false)
    private String config;
    @Version private long version;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getPage() { return page; }
    public void setPage(String page) { this.page = page; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
