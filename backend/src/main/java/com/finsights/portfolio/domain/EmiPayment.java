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
import java.math.BigDecimal;
import java.time.LocalDate;

/** Records that a liability's EMI for one month has been paid. */
@Entity
@Table(name = "emi_payments", uniqueConstraints = @UniqueConstraint(
        name = "uk_emi_payment_period", columnNames = {"holding_id", "period"}))
public class EmiPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private UserAccount user;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Holding holding;

    /** First day of the month the EMI is for. */
    @Column(nullable = false)
    private LocalDate period;

    @Column(nullable = false)
    private LocalDate paidOn;

    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal amount;

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public Holding getHolding() { return holding; }
    public void setHolding(Holding holding) { this.holding = holding; }
    public LocalDate getPeriod() { return period; }
    public void setPeriod(LocalDate period) { this.period = period; }
    public LocalDate getPaidOn() { return paidOn; }
    public void setPaidOn(LocalDate paidOn) { this.paidOn = paidOn; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
