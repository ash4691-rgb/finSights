package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A symbol the user wants tracked for Hot picks without owning it as a {@link Holding} — its
 * price trail lives in {@link PriceSnapshot} rows keyed to this item's id.
 */
@Entity
@Table(name = "watchlist_items")
public class WatchlistItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private UserAccount user;
    @Column(nullable = false, length = 128)
    private String name;
    private String tickerSymbol;
    @Column(length = 1024)
    private String notes;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    @Version private long version;

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTickerSymbol() { return tickerSymbol; }
    public void setTickerSymbol(String tickerSymbol) { this.tickerSymbol = tickerSymbol; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
