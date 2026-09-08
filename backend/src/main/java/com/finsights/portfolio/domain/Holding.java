package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named position — a stock, fund, crypto, FD, loan — filed under one {@link Category} and
 * held at one broker. Asset/Liability type is inherited from its category.
 */
@Entity
@Table(name = "holdings")
public class Holding {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount user;
    // EAGER: almost every read (valuation, dashboard, exports) needs the category right away,
    // and it avoids LazyInitializationException once the loading transaction has closed.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private Category category;
    @Column(nullable = false, length = 128)
    private String name;
    /** Auto-generated, human-readable, space-free reference (e.g. "reliance-industries-4f2a"). Never editable. */
    @Column(unique = true)
    private String holdingRef;
    @Column(nullable = false, length = 96)
    private String broker;
    private String currency = "INR";
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private ValuationMethod valuationMethod;
    @Column(length = 24)
    private String tickerSymbol;
    @Column(precision = 24, scale = 8)
    private BigDecimal quantity;
    /** Remaining cost basis after average-cost SELLs; the ledger owns this (see HoldingService). */
    @Column(precision = 20, scale = 2)
    private BigDecimal investedValue = BigDecimal.ZERO;
    /** Profit/loss already locked in by SELLs (FIFO), from the ledger. Negative for interest paid on loans. */
    @Column(precision = 20, scale = 2)
    private BigDecimal realisedProfitLoss = BigDecimal.ZERO;
    /** Sum of INTEREST transaction amounts — added on top of the stored current value. */
    @Column(precision = 20, scale = 2)
    private BigDecimal accruedIncome = BigDecimal.ZERO;
    /** Manually-entered value for MANUAL/MARKET_PRICE/BROKER_SYNC; ignored for FIXED_RATE (computed). */
    @Column(precision = 20, scale = 2)
    private BigDecimal currentValue = BigDecimal.ZERO;
    @Column(precision = 10, scale = 6)
    private BigDecimal fixedAnnualRate;
    /** For FIXED_RATE: how often interest is paid out / compounded. */
    @Enumerated(EnumType.STRING)
    private CompoundingFrequency compoundingFrequency;
    private LocalDate fixedRateStartDate;
    /** Optional maturity date for FIXED_RATE; the value stops accruing after it. Null = open-ended. */
    private LocalDate fixedRateEndDate;
    // --- LIABILITY repayment schedule ---
    /** How the loan is repaid; ONE_TIME is a bullet payment, the rest recurring. */
    @Enumerated(EnumType.STRING)
    private RepaymentFrequency repaymentFrequency;
    /** Instalment amount for a recurring liability; may be left null if {@link #loanTermMonths} is set. */
    @Column(precision = 20, scale = 2)
    private BigDecimal emiAmount;
    /** Day of the month the instalment falls due (1–31), for recurring liabilities. */
    private Integer emiDayOfMonth;
    /** Remaining number of instalments, an alternative to {@link #emiAmount}. */
    private Integer loanTermMonths;
    /** Due date for a ONE_TIME liability. */
    private LocalDate repaymentDueDate;
    private Boolean liquidWithinSevenDays = false;
    private Boolean blocked = false;
    /** Manual drag-to-reorder position within the Holdings table; new holdings append to the end. */
    @Column(nullable = false)
    private int sortOrder = 0;
    /** Short one-liner shown in the ⓘ hover on the Holdings table; {@link #notes} is the longer free text. */
    @Column(length = 1024)
    private String description;
    @Column(length = 1000)
    private String notes;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "holding_tags", joinColumns = @JoinColumn(name = "holding_id"))
    @Column(name = "tag", length = 48)
    private Set<String> tags = new LinkedHashSet<>();
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    /** When the MARKET_PRICE current value was last refreshed from the live feed; null until first fetch. */
    private Instant priceUpdatedAt;
    @Version private long version;

    public Holding() { }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHoldingRef() { return holdingRef; }
    public void setHoldingRef(String holdingRef) { this.holdingRef = holdingRef; }
    public String getBroker() { return broker; }
    public void setBroker(String broker) { this.broker = broker; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public ValuationMethod getValuationMethod() { return valuationMethod; }
    public void setValuationMethod(ValuationMethod valuationMethod) { this.valuationMethod = valuationMethod; }
    public String getTickerSymbol() { return tickerSymbol; }
    public void setTickerSymbol(String tickerSymbol) { this.tickerSymbol = tickerSymbol; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getInvestedValue() { return investedValue; }
    public void setInvestedValue(BigDecimal investedValue) { this.investedValue = investedValue; }
    public BigDecimal getRealisedProfitLoss() { return realisedProfitLoss; }
    public void setRealisedProfitLoss(BigDecimal realisedProfitLoss) { this.realisedProfitLoss = realisedProfitLoss; }
    public BigDecimal getAccruedIncome() { return accruedIncome; }
    public void setAccruedIncome(BigDecimal accruedIncome) { this.accruedIncome = accruedIncome; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public BigDecimal getFixedAnnualRate() { return fixedAnnualRate; }
    public void setFixedAnnualRate(BigDecimal fixedAnnualRate) { this.fixedAnnualRate = fixedAnnualRate; }
    public CompoundingFrequency getCompoundingFrequency() { return compoundingFrequency; }
    public void setCompoundingFrequency(CompoundingFrequency compoundingFrequency) { this.compoundingFrequency = compoundingFrequency; }
    public LocalDate getFixedRateStartDate() { return fixedRateStartDate; }
    public void setFixedRateStartDate(LocalDate fixedRateStartDate) { this.fixedRateStartDate = fixedRateStartDate; }
    public LocalDate getFixedRateEndDate() { return fixedRateEndDate; }
    public void setFixedRateEndDate(LocalDate fixedRateEndDate) { this.fixedRateEndDate = fixedRateEndDate; }
    public RepaymentFrequency getRepaymentFrequency() { return repaymentFrequency; }
    public void setRepaymentFrequency(RepaymentFrequency repaymentFrequency) { this.repaymentFrequency = repaymentFrequency; }
    public BigDecimal getEmiAmount() { return emiAmount; }
    public void setEmiAmount(BigDecimal emiAmount) { this.emiAmount = emiAmount; }
    public Integer getEmiDayOfMonth() { return emiDayOfMonth; }
    public void setEmiDayOfMonth(Integer emiDayOfMonth) { this.emiDayOfMonth = emiDayOfMonth; }
    public Integer getLoanTermMonths() { return loanTermMonths; }
    public void setLoanTermMonths(Integer loanTermMonths) { this.loanTermMonths = loanTermMonths; }
    public LocalDate getRepaymentDueDate() { return repaymentDueDate; }
    public void setRepaymentDueDate(LocalDate repaymentDueDate) { this.repaymentDueDate = repaymentDueDate; }
    public Boolean getLiquidWithinSevenDays() { return liquidWithinSevenDays; }
    public void setLiquidWithinSevenDays(Boolean liquidWithinSevenDays) { this.liquidWithinSevenDays = liquidWithinSevenDays; }
    public Boolean getBlocked() { return blocked; }
    public void setBlocked(Boolean blocked) { this.blocked = blocked; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Set<String> getTags() { return tags; }
    public void setTags(Set<String> tags) { this.tags = tags; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPriceUpdatedAt() { return priceUpdatedAt; }
    public void setPriceUpdatedAt(Instant priceUpdatedAt) { this.priceUpdatedAt = priceUpdatedAt; }
}
