package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.UserAccountRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    /** The shared, password-less account the demo auth filter falls back to. */
    public static final String DEMO_EMAIL = "demo@finsights.local";

    private final UserAccountRepository users;

    public CurrentUserService(UserAccountRepository users) {
        this.users = users;
    }

    public UserAccount currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user");
        }
        String email = auth.getName();
        String displayName = email;
        if (auth.getPrincipal() instanceof OAuth2User oauthUser) {
            Object oauthEmail = oauthUser.getAttributes().get("email");
            if (oauthEmail != null) email = oauthEmail.toString();
            Object oauthName = oauthUser.getAttributes().get("name");
            if (oauthName != null) displayName = oauthName.toString();
        }
        final String finalEmail = email;
        final String finalDisplayName = displayName;
        return users.findByEmail(finalEmail)
                .map(existing -> {
                    // Only seed the display name; never clobber one the user has personalised in Settings.
                    if (existing.getDisplayName() == null || existing.getDisplayName().isBlank()
                            || existing.getDisplayName().equals(finalEmail)) {
                        existing.setDisplayName(finalDisplayName);
                        return users.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> users.save(new UserAccount(finalEmail, finalDisplayName)));
    }
}

