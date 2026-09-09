package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A user's decision on one Action Centre row (keyed by {@link com.finsights.portfolio.dto.ActionItemResponse#key()}).
 * DONE / DELETED hide the row for good; DEFERRED hides it until {@link #deferredUntil}.
 */
@Entity
@Table(name = "action_dismissals", uniqueConstraints = @UniqueConstraint(
        name = "uk_action_dismissal", columnNames = {"user_id", "action_key"}))
public class ActionDismissal {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount user;
    @Column(name = "action_key", nullable = false, length = 512)
    private String actionKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private ActionDismissalStatus status;
    private LocalDate deferredUntil;
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getActionKey() { return actionKey; }
    public void setActionKey(String actionKey) { this.actionKey = actionKey; }
    public ActionDismissalStatus getStatus() { return status; }
    public void setStatus(ActionDismissalStatus status) { this.status = status; }
    public LocalDate getDeferredUntil() { return deferredUntil; }
    public void setDeferredUntil(LocalDate deferredUntil) { this.deferredUntil = deferredUntil; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
