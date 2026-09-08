package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(nullable = false, updatable = false)
    private String email;
    private String displayName;
    /** BCrypt hash for email/password sign-in. Null for the demo account and for Google users. */
    private String passwordHash;
    private String phone;
    /** ISO-3166 alpha-2 country of residence; drives {@link #baseCurrency} via CountryCurrencyService. */
    @Column(nullable = false)
    private String country = "IN";
    @Column(nullable = false)
    private String baseCurrency = "INR";
    /** INDIAN (lakh/crore grouping) or INTERNATIONAL (million/billion grouping) — display only. */
    @Column(nullable = false)
    private String numberFormat = "INDIAN";
    @Column(nullable = false)
    private Boolean notifyEmail = true;
    @Column(nullable = false)
    private Boolean notifySms = false;
    @Column(nullable = false)
    private Boolean notifyPush = false;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal notifyThresholdPercent = new BigDecimal("5.00");
    // Insights "Hot picks" thresholds, one per lookback window — null means that window is off.
    @Column(precision = 6, scale = 2)
    private BigDecimal dailyThresholdPercent;
    @Column(precision = 6, scale = 2)
    private BigDecimal weeklyThresholdPercent;
    @Column(precision = 6, scale = 2)
    private BigDecimal monthlyThresholdPercent;
    @Column(precision = 6, scale = 2)
    private BigDecimal quarterlyThresholdPercent;
    @Column(precision = 6, scale = 2)
    private BigDecimal yearlyThresholdPercent;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected UserAccount() { }

    public UserAccount(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getBaseCurrency() { return baseCurrency; }
    public void setBaseCurrency(String baseCurrency) { this.baseCurrency = baseCurrency; }
    public String getNumberFormat() { return numberFormat; }
    public void setNumberFormat(String numberFormat) { this.numberFormat = numberFormat; }
    public Boolean getNotifyEmail() { return notifyEmail; }
    public void setNotifyEmail(Boolean notifyEmail) { this.notifyEmail = notifyEmail; }
    public Boolean getNotifySms() { return notifySms; }
    public void setNotifySms(Boolean notifySms) { this.notifySms = notifySms; }
    public Boolean getNotifyPush() { return notifyPush; }
    public void setNotifyPush(Boolean notifyPush) { this.notifyPush = notifyPush; }
    public BigDecimal getNotifyThresholdPercent() { return notifyThresholdPercent; }
    public void setNotifyThresholdPercent(BigDecimal notifyThresholdPercent) { this.notifyThresholdPercent = notifyThresholdPercent; }
    public BigDecimal getDailyThresholdPercent() { return dailyThresholdPercent; }
    public void setDailyThresholdPercent(BigDecimal dailyThresholdPercent) { this.dailyThresholdPercent = dailyThresholdPercent; }
    public BigDecimal getWeeklyThresholdPercent() { return weeklyThresholdPercent; }
    public void setWeeklyThresholdPercent(BigDecimal weeklyThresholdPercent) { this.weeklyThresholdPercent = weeklyThresholdPercent; }
    public BigDecimal getMonthlyThresholdPercent() { return monthlyThresholdPercent; }
    public void setMonthlyThresholdPercent(BigDecimal monthlyThresholdPercent) { this.monthlyThresholdPercent = monthlyThresholdPercent; }
    public BigDecimal getQuarterlyThresholdPercent() { return quarterlyThresholdPercent; }
    public void setQuarterlyThresholdPercent(BigDecimal quarterlyThresholdPercent) { this.quarterlyThresholdPercent = quarterlyThresholdPercent; }
    public BigDecimal getYearlyThresholdPercent() { return yearlyThresholdPercent; }
    public void setYearlyThresholdPercent(BigDecimal yearlyThresholdPercent) { this.yearlyThresholdPercent = yearlyThresholdPercent; }
    public Instant getCreatedAt() { return createdAt; }
}
