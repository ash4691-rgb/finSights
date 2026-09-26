package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.UserAccountRepository;
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
        service = new LocalAuthService(users, new BCryptPasswordEncoder(), mail);
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
    void loginFailsForAnUnverifiedLocalAccount() {
        UserAccount stored = new UserAccount("sam@example.com", "Sam");
        stored.setPasswordHash(new BCryptPasswordEncoder().encode("hunter2horse"));
        stored.setEmailVerified(false);
        when(users.findByEmail("sam@example.com")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.login("sam@example.com", "hunter2horse"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("verify your email");
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
}
