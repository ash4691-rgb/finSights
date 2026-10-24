package com.finsights.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One user's "cross" on one specific news article (keyed by its link) — a per-article dismissal,
 * not a ticker-level block. Hides that exact article from this user's Radar going forward, but
 * leaves the ticker itself, and every other article about it, fully visible; see
 * {@link TickerPreference} for the separate, untouched ticker-level star/exclude mechanism.
 * Pruned past {@link com.finsights.portfolio.service.NewsService}'s own 14-day retention window —
 * an article older than that can never resurface anyway (NewsService#headlinesFor already filters
 * it out), so there's nothing left for the dismissal to guard against.
 */
@Entity
@Table(name = "dismissed_news_items", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "link" }))
public class DismissedNewsItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private UserAccount user;
    @Column(nullable = false, length = 1024)
    private String link;
    @Column(nullable = false)
    private Instant dismissedAt = Instant.now();

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }
    public Instant getDismissedAt() { return dismissedAt; }
    public void setDismissedAt(Instant dismissedAt) { this.dismissedAt = dismissedAt; }
}
