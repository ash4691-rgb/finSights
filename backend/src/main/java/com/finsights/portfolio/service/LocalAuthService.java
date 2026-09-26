package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.UserAccountRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Email + password registration and sign-in. Coexists with demo and Google auth. */
@Service
public class LocalAuthService {
    private static final int VERIFICATION_TOKEN_VALID_HOURS = 24;

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final MailService mail;

    public LocalAuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, MailService mail) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mail = mail;
    }

    @Transactional
    public UserAccount register(String email, String displayName, String rawPassword) {
        String normalized = email.trim().toLowerCase();
        if (normalized.equals(CurrentUserService.DEMO_EMAIL)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That email address is reserved");
        }
        if (users.findByEmail(normalized).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with that email already exists");
        }
        String name = displayName == null || displayName.isBlank() ? normalized : displayName.trim();
        UserAccount user = new UserAccount(normalized, name);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        // Unlike the entity's default (true, for the demo account and Google users, who are
        // never asked to verify), a fresh local signup starts unverified until the emailed link
        // is clicked.
        user.setEmailVerified(false);
        String token = UUID.randomUUID().toString();
        user.setVerificationToken(token);
        user.setVerificationTokenExpiresAt(Instant.now().plus(VERIFICATION_TOKEN_VALID_HOURS, ChronoUnit.HOURS));
        user = users.save(user);
        mail.sendVerificationEmail(user.getEmail(), token);
        return user;
    }

    public UserAccount login(String email, String rawPassword) {
        UserAccount user = users.findByEmail(email.trim().toLowerCase())
                .filter(u -> u.getPasswordHash() != null && passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Please verify your email before logging in — check your inbox for the activation link.");
        }
        return user;
    }

    @Transactional
    public void verifyEmail(String token) {
        UserAccount user = users.findByVerificationToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "That verification link is invalid or has already been used."));
        if (user.getVerificationTokenExpiresAt() == null || user.getVerificationTokenExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That verification link has expired. Please sign up again to get a new one.");
        }
        user.setEmailVerified(true);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiresAt(null);
        users.save(user);
    }
}
