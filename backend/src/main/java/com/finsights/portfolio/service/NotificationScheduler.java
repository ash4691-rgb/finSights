package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.UserAccountRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Emails the weekly digest (Mondays, after the 03:00 snapshot) and the daily alert to every account
 * with email notifications on. Off unless ALERTS_ENABLED=true, so a deploy never starts emailing
 * users by surprise. One account failing never stops the others.
 */
@Component
public class NotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final UserAccountRepository users;
    private final NotificationService notifications;
    private final UserContextRunner runner;
    private final MailService mail;
    private final boolean enabled;

    public NotificationScheduler(UserAccountRepository users, NotificationService notifications, UserContextRunner runner,
                                 MailService mail, @Value("${app.alerts.enabled:false}") boolean enabled) {
        this.users = users;
        this.notifications = notifications;
        this.runner = runner;
        this.mail = mail;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 7 8 * * MON", zone = "Asia/Kolkata")
    public void sendWeeklyDigests() {
        if (!enabled) return;
        int sent = 0;
        for (UserAccount user : recipients()) {
            try {
                NotificationEmail email = runner.runAs(user.getEmail(), notifications::weeklyDigest);
                if (mail.send(user.getEmail(), email.subject(), email.body())) sent++;
            } catch (RuntimeException ex) {
                log.warn("Weekly digest failed for user {}", user.getId(), ex);
            }
        }
        log.info("Weekly digest: {} sent", sent);
    }

    @Scheduled(cron = "0 37 8 * * *", zone = "Asia/Kolkata")
    public void sendDailyAlerts() {
        if (!enabled) return;
        int sent = 0;
        for (UserAccount user : recipients()) {
            try {
                if (sendAlertTo(user)) sent++;
            } catch (RuntimeException ex) {
                log.warn("Daily alert failed for user {}", user.getId(), ex);
            }
        }
        notifications.pruneSent();
        log.info("Daily alerts: {} sent", sent);
    }

    private boolean sendAlertTo(UserAccount user) {
        var pending = runner.runAs(user.getEmail(), notifications::pendingAlert);
        if (pending.isEmpty()) return false;
        NotificationEmail email = pending.get();
        if (!mail.send(user.getEmail(), email.subject(), email.body())) return false;
        notifications.markSent(user.getId(), email.alertKeys());
        return true;
    }

    List<UserAccount> recipients() {
        return users.findAll().stream().filter(NotificationScheduler::wantsEmail).toList();
    }

    static boolean wantsEmail(UserAccount user) {
        if (!Boolean.TRUE.equals(user.getNotifyEmail())) return false;
        if (Boolean.FALSE.equals(user.getEmailVerified())) return false;
        String email = user.getEmail();
        return email != null && !email.isBlank() && !CurrentUserService.DEMO_EMAIL.equalsIgnoreCase(email);
    }
}
