package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One email allowed to chat with Goku. Managed entirely through the admin allowlist API
 * ({@code /api/goku/admin/allowlist}) once seeded — see {@code GokuAllowlistService} — so
 * granting a new user access is a two-second admin-panel action, not a config change + redeploy.
 */
@Entity
@Table(name = "goku_allowed_users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class GokuAllowedUser {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(nullable = false, updatable = false, length = 254)
    private String email;
    /** The admin's email who added this entry, or "seed" for the initial boot-time seeding. */
    @Column(length = 254)
    private String addedBy;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected GokuAllowedUser() { }

    public GokuAllowedUser(String email, String addedBy) {
        this.email = email;
        this.addedBy = addedBy;
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getAddedBy() { return addedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
