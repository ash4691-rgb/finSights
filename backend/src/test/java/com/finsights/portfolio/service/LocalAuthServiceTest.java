package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.UserAccountRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class LocalAuthServiceTest {

    @Mock UserAccountRepository users;
    @Mock MailService mail;
    private LocalAuthService service;

    @BeforeEach
    void setUp() {
        service = new LocalAuthService(users, new BCryptPasswordEncoder(), mail, true);
    }

    @Test
    void registerHashesThePasswordAndKeepsItUnverifiedUntilTheEmailLinkIsClicked() {
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.empty());
        when(users.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        UserAccount user = service.register("Sam@Example.com ", "Sam", "hunter2horse");

        assertThat(user.getEmail()).isEqualTo("sam@example.com");
        assertThat(user.getDisplayName()).isEqualTo("Sam");
        assertThat(user.getPasswordHash()).isNotNull().isNotEqualTo("hunter2horse");
        assertThat(user.getEmailVerified()).isFalse();
        assertThat(user.getVerificationToken()).isNotBlank();
        assertThat(user.getVerificationTokenExpiresAt()).isNotNull();
        verify(mail).sendVerificationEmail(org.mockito.ArgumentMatchers.eq("sam@example.com"), anyString());
    }

    @Test
    void registerAutoVerifiesAndSendsNothingWhenVerificationIsOff() {
        LocalAuthService noVerification = new LocalAuthService(users, new BCryptPasswordEncoder(), mail, false);
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.empty());
        when(users.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        UserAccount user = noVerification.register("Sam@Example.com ", "Sam", "hunter2horse");

        assertThat(user.getEmailVerified()).isTrue();
        assertThat(user.getVerificationToken()).isNull();
        verify(mail, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void loginSkipsTheVerificationCheckWhenVerificationIsOff() {
        LocalAuthService noVerification = new LocalAuthService(users, new BCryptPasswordEncoder(), mail, false);
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setPasswordHash(new BCryptPasswordEncoder().encode("hunter2horse"));
        stored.setEmailVerified(false);
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.of(stored));

        assertThat(noVerification.login("sam@example.com", "hunter2horse")).isSameAs(stored);
    }

    @Test
    void registerRejectsADuplicateEmail() {
        when(users.findByEmail("taken@example.com")).thenReturn(Optional.of(new UserAccount("taken@example.com", "Taken")));

        assertThatThrownBy(() -> service.register("taken@example.com", "Nope", "password123"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void registerRejectsTheReservedDemoEmail() {
        assertThatThrownBy(() -> service.register(CurrentUserService.DEMO_EMAIL, "Demo", "password123"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void loginSucceedsWithTheRightPassword() {
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setPasswordHash(new BCryptPasswordEncoder().encode("hunter2horse"));
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.of(stored));

        assertThat(service.login("sam@example.com", "hunter2horse")).isSameAs(stored);
    }

    @Test
    void loginFailsWithTheWrongPassword() {
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setPasswordHash(new BCryptPasswordEncoder().encode("hunter2horse"));
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.login("sam@example.com", "wrong"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void loginFailsForAnAccountWithoutAPassword() {
        UserAccount googleUser = new UserAccount("g@example.com", "G"); // passwordHash stays null
        when(users.findByEmail("g@example.com")).thenReturn(Optional.of(googleUser));

        assertThatThrownBy(() -> service.login("g@example.com", "anything"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void loginFailsForAnUnverifiedLocalAccountWithoutSendingAnything() {
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setPasswordHash(new BCryptPasswordEncoder().encode("hunter2horse"));
        stored.setEmailVerified(false);
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.login("sam@example.com", "hunter2horse"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("isn't verified")
                .hasMessageContaining("Resend");
        verify(mail, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void resendVerificationSendsANewEmailAndIncrementsTheCounter() {
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setEmailVerified(false);
        String originalToken = stored.getVerificationToken();
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.of(stored));

        String message = service.resendVerification("sam@example.com");

        assertThat(message).contains("has been sent");
        assertThat(stored.getVerificationToken()).isNotBlank().isNotEqualTo(originalToken);
        assertThat(stored.getVerificationResendCount()).isEqualTo(1);
        verify(mail).sendVerificationEmail(eq("sam@example.com"), anyString());
    }

    @Test
    void resendVerificationStopsOnceTheDailyLimitIsReached() {
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setEmailVerified(false);
        stored.setVerificationResendDate(LocalDate.now());
        stored.setVerificationResendCount(5);
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.of(stored));

        String message = service.resendVerification("sam@example.com");

        assertThat(message).contains("resend limit");
        assertThat(stored.getVerificationResendCount()).isEqualTo(5);
        verify(mail, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void resendVerificationResetsTheCounterOnANewDay() {
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setEmailVerified(false);
        stored.setVerificationResendDate(LocalDate.now().minusDays(1));
        stored.setVerificationResendCount(5);
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.of(stored));

        String message = service.resendVerification("sam@example.com");

        assertThat(message).contains("has been sent");
        assertThat(stored.getVerificationResendDate()).isEqualTo(LocalDate.now());
        assertThat(stored.getVerificationResendCount()).isEqualTo(1);
        verify(mail, times(1)).sendVerificationEmail(eq("sam@example.com"), anyString());
    }

    @Test
    void resendVerificationRejectsAnUnknownEmail() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resendVerification("nobody@example.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No account found");
    }

    @Test
    void resendVerificationTellsAnAlreadyVerifiedUserToJustLogIn() {
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setEmailVerified(true);
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.of(stored));

        String message = service.resendVerification("sam@example.com");

        assertThat(message).contains("already verified");
        verify(mail, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void verifyEmailActivatesTheAccountAndClearsTheToken() {
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setEmailVerified(false);
        stored.setVerificationToken("abc123");
        stored.setVerificationTokenExpiresAt(java.time.Instant.now().plusSeconds(3600));
        when(users.findByVerificationToken("abc123")).thenReturn(Optional.of(stored));
        when(users.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        service.verifyEmail("abc123");

        assertThat(stored.getEmailVerified()).isTrue();
        assertThat(stored.getVerificationToken()).isNull();
        assertThat(stored.getVerificationTokenExpiresAt()).isNull();
    }

    @Test
    void verifyEmailRejectsAnExpiredToken() {
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setEmailVerified(false);
        stored.setVerificationToken("abc123");
        stored.setVerificationTokenExpiresAt(java.time.Instant.now().minusSeconds(1));
        when(users.findByVerificationToken("abc123")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.verifyEmail("abc123"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void verifyEmailRejectsAnUnknownToken() {
        when(users.findByVerificationToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyEmail("nope"))
                .isInstanceOf(ResponseStatusException.class);
    }

    private UserAccount userWithPassword(String raw) {
        UserAccount user = new UserAccount("sam@example.com", "Sam");
        user.setPasswordHash(new BCryptPasswordEncoder().encode(raw));
        return user;
    }

    @Test
    void changePasswordReplacesTheHashWhenTheCurrentPasswordMatches() {
        UserAccount user = userWithPassword("hunter2horse");

        service.changePassword(user, "hunter2horse", "correct-horse-battery");

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertThat(encoder.matches("correct-horse-battery", user.getPasswordHash())).isTrue();
        assertThat(encoder.matches("hunter2horse", user.getPasswordHash())).isFalse();
        verify(users).save(user);
    }

    @Test
    void changePasswordRejectsAWrongCurrentPasswordWithA400NotA401() {
        UserAccount user = userWithPassword("hunter2horse");
        String before = user.getPasswordHash();

        assertThatThrownBy(() -> service.changePassword(user, "wrong-guess", "correct-horse-battery"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("Current password is incorrect");
        assertThat(user.getPasswordHash()).isEqualTo(before);
        verify(users, never()).save(any(UserAccount.class));
    }

    @Test
    void changePasswordRejectsReusingTheCurrentPassword() {
        UserAccount user = userWithPassword("hunter2horse");

        assertThatThrownBy(() -> service.changePassword(user, "hunter2horse", "hunter2horse"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different");
        verify(users, never()).save(any(UserAccount.class));
    }

    @Test
    void changePasswordIsRefusedForAccountsWithoutAPassword() {
        UserAccount googleUser = new UserAccount("sam@example.com", "Sam");

        assertThatThrownBy(() -> service.changePassword(googleUser, "anything", "correct-horse-battery"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("doesn't sign in with a password");
        assertThat(googleUser.getPasswordHash()).isNull();
        verify(users, never()).save(any(UserAccount.class));
    }
}
