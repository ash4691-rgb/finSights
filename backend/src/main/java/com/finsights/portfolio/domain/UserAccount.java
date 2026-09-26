package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

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
    /** True for the demo account and Google users (never asked to verify); false from creation
     *  for a local email/password signup until the emailed link is clicked. LocalAuthService
     *  blocks {@code login()} while this is false — Google's own login path never checks it,
     *  since Google has already verified the address before handing it to us. */
    @Column(nullable = false)
    @ColumnDefault("true")
    private Boolean emailVerified = true;
    /** Set alongside emailVerified=false at local registration; cleared once verified. */
    private String verificationToken;
    private Instant verificationTokenExpiresAt;
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
    /** "Don't show this again" for the CustomLayoutOnboarding tour — once true, the tour never
     *  replays for this user; while false, it re-shows every time they enter Edit Layout mode.
     *  Needs a SQL-level default (not just the Java-side one below): ddl-auto=update's ALTER
     *  TABLE has to backfill this NOT NULL column for every existing row, and it only knows
     *  how to do that from a column default, not from the entity's default field value. */
    @Column(nullable = false)
    @ColumnDefault("false")
    private Boolean customLayoutOnboardingDismissed = false;
    /** "Don't show this again" for the UserOnboarding tour (the app-concepts walkthrough shown
     *  on first entering the app) — same one-way-flip semantics as customLayoutOnboardingDismissed. */
    @Column(nullable = false)
    @ColumnDefault("false")
    private Boolean userOnboardingDismissed = false;
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
    public Boolean getEmailVerified() { return emailVerified; }
    public void setEmailVerified(Boolean emailVerified) { this.emailVerified = emailVerified; }
    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }
    public Instant getVerificationTokenExpiresAt() { return verificationTokenExpiresAt; }
    public void setVerificationTokenExpiresAt(Instant verificationTokenExpiresAt) { this.verificationTokenExpiresAt = verificationTokenExpiresAt; }
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
    public Boolean getCustomLayoutOnboardingDismissed() { return customLayoutOnboardingDismissed; }
    public void setCustomLayoutOnboardingDismissed(Boolean customLayoutOnboardingDismissed) { this.customLayoutOnboardingDismissed = customLayoutOnboardingDismissed; }
    public Boolean getUserOnboardingDismissed() { return userOnboardingDismissed; }
    public void setUserOnboardingDismissed(Boolean userOnboardingDismissed) { this.userOnboardingDismissed = userOnboardingDismissed; }
    public Instant getCreatedAt() { return createdAt; }
}
