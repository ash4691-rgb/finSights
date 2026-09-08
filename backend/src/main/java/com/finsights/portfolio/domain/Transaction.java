package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
public class Transaction {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount user;
    // EAGER: toResponse() always needs holding.instrument's name/class/currency right away.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private Holding holding;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private TransactionType type;
    @Column(nullable = false)
    private LocalDate date;
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;
    @Column(precision = 24, scale = 8)
    private BigDecimal quantity;
    @Column(length = 1024)
    private String notes;
    /** For REPAY: the part of {@link #amount} that cut principal (rest was interest). Set when the row is saved. */
    @Column(precision = 20, scale = 2)
    private BigDecimal principalPortion;
    private Instant createdAt = Instant.now();

    public Transaction() { }

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public Holding getHolding() { return holding; }
    public void setHolding(Holding holding) { this.holding = holding; }
    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public BigDecimal getPrincipalPortion() { return principalPortion; }
    public void setPrincipalPortion(BigDecimal principalPortion) { this.principalPortion = principalPortion; }
    public Instant getCreatedAt() { return createdAt; }
}
