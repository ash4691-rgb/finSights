package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Email + password registration and sign-in. Coexists with demo and Google auth. */
@Service
public class LocalAuthService {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    public LocalAuthService(UserAccountRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
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
        return users.save(user);
    }

    public UserAccount login(String email, String rawPassword) {
        UserAccount user = users.findByEmail(email.trim().toLowerCase())
                .filter(u -> u.getPasswordHash() != null && passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        return user;
    }
}
