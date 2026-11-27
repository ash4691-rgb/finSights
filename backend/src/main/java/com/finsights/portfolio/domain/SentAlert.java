package com.finsights.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** One alert item (by its stable key) already emailed to one user. See NotificationService. */
@Entity
@Table(name = "sent_alerts", uniqueConstraints = @UniqueConstraint(name = "uk_sent_alert", columnNames = { "user_id", "alert_key" }))
public class SentAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(name = "user_id", nullable = false)
    private String userId;
    @Column(name = "alert_key", nullable = false, length = 512)
    private String alertKey;
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    protected SentAlert() { }

    public SentAlert(String userId, String alertKey) {
        this.userId = userId;
        this.alertKey = alertKey;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getAlertKey() { return alertKey; }
    public Instant getSentAt() { return sentAt; }
}
