package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.UserAccountRepository;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationSchedulerTest {
    @Mock UserAccountRepository users;
    @Mock NotificationService notifications;
    @Mock UserContextRunner runner;
    @Mock MailService mail;
    private UserAccount asha;
    private final NotificationEmail email = new NotificationEmail("Subject", "Body", List.of("k1"));

    @BeforeEach
    void setUp() {
        asha = user("asha@example.com", true, true);
        lenient().when(runner.runAs(anyString(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    private NotificationScheduler scheduler(boolean enabled) {
        return new NotificationScheduler(users, notifications, runner, mail, enabled);
    }

    @Test
    void disabledSendsNothing() {
        scheduler(false).sendWeeklyDigests();
        scheduler(false).sendDailyAlerts();
        verifyNoInteractions(users, notifications, mail);
    }

    @Test
    void weeklyDigestGoesToEachOptedInUserAndOneFailureDoesNotStopTheRest() {
        UserAccount broken = user("broken@example.com", true, true);
        when(users.findAll()).thenReturn(List.of(broken, asha));
        when(runner.runAs(eq("broken@example.com"), any())).thenThrow(new IllegalStateException("bad data"));
        when(notifications.weeklyDigest()).thenReturn(email);
        when(mail.send("asha@example.com", "Subject", "Body")).thenReturn(true);

        scheduler(true).sendWeeklyDigests();

        verify(mail).send("asha@example.com", "Subject", "Body");
        verify(mail, never()).send(eq("broken@example.com"), anyString(), anyString());
    }

    @Test
    void dailyAlertMarksKeysSentOnlyAfterDelivery() {
        when(users.findAll()).thenReturn(List.of(asha));
        when(notifications.pendingAlert()).thenReturn(Optional.of(email));
        when(mail.send("asha@example.com", "Subject", "Body")).thenReturn(true);

        scheduler(true).sendDailyAlerts();

        verify(notifications).markSent(asha.getId(), List.of("k1"));
        verify(notifications).pruneSent();
    }

    @Test
    void failedDeliveryOrNothingNewRecordsNothing() {
        UserAccount ravi = user("ravi@example.com", true, true);
        when(users.findAll()).thenReturn(List.of(asha, ravi));
        when(runner.runAs(eq("asha@example.com"), any())).thenReturn(Optional.of(email));
        when(runner.runAs(eq("ravi@example.com"), any())).thenReturn(Optional.empty());
        when(mail.send(anyString(), anyString(), anyString())).thenReturn(false);

        scheduler(true).sendDailyAlerts();

        verify(notifications, never()).markSent(any(), any());
        verify(mail, never()).send(eq("ravi@example.com"), anyString(), anyString());
    }

    @Test
    void oneUsersAlertFailureDoesNotStopTheJob() {
        when(users.findAll()).thenReturn(List.of(asha));
        when(runner.runAs(eq("asha@example.com"), any())).thenThrow(new IllegalStateException("boom"));

        scheduler(true).sendDailyAlerts();

        verify(notifications).pruneSent();
    }

    @Test
    void onlyVerifiedOptedInRealAccountsReceiveEmail() {
        when(users.findAll()).thenReturn(List.of(asha,
                user("off@example.com", false, true),
                user("unverified@example.com", true, false),
                user(CurrentUserService.DEMO_EMAIL, true, true),
                user(" ", true, true)));
        UserAccount nullFlag = user("null@example.com", true, true);
        nullFlag.setNotifyEmail(null);

        assertThat(scheduler(true).recipients()).containsExactly(asha);
        assertThat(NotificationScheduler.wantsEmail(nullFlag)).isFalse();
        UserAccount noEmail = user("x@example.com", true, true);
        ReflectionTestUtils.setField(noEmail, "email", null);
        assertThat(NotificationScheduler.wantsEmail(noEmail)).isFalse();
    }

    private static UserAccount user(String email, boolean notify, boolean verified) {
        UserAccount user = new UserAccount(email, "Name");
        ReflectionTestUtils.setField(user, "id", "id-" + email);
        user.setNotifyEmail(notify);
        user.setEmailVerified(verified);
        return user;
    }
}
