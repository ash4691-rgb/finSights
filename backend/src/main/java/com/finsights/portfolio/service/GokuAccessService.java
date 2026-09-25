package com.finsights.portfolio.service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Feature-gates Goku to an allowlist of emails, the same shape as demo-mode's auth config: a
 * comma-separated list in config, checked case-insensitively against the signed-in user. Phase 1
 * ships with just the one account from the rollout plan; widening it later is a config change,
 * not a code change.
 */
@Service
public class GokuAccessService {
    private final CurrentUserService currentUser;

    @Value("${app.goku.enabled:true}") private boolean enabled;
    @Value("${app.goku.allowlist:ash4691@gmail.com,demo@finsights.local}") private String allowlistRaw;
    @Value("${app.goku.anthropic-api-key:}") private String apiKey;

    public GokuAccessService(CurrentUserService currentUser) { this.currentUser = currentUser; }

    /** Whether the chat bubble should render at all for the signed-in user. */
    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.isBlank() && isCurrentUserAllowed();
    }

    private boolean isCurrentUserAllowed() {
        String email = currentUser.currentUser().getEmail();
        return email != null && allowlist().contains(email.toLowerCase());
    }

    private Set<String> allowlist() {
        return Arrays.stream(allowlistRaw.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    /** Enforced server-side on every {@code /api/goku/chat} call — the frontend hiding the bubble is not the gate. */
    public void requireAvailable() {
        if (!isAvailable()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Goku isn't available for this account yet");
        }
    }
}
