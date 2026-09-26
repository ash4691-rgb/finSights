package com.finsights.portfolio.service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Feature-gates Goku two ways:
 * <ul>
 *   <li><b>Who can chat with it</b> — the database-backed allowlist ({@link GokuAllowlistService}),
 *       managed through the admin panel.</li>
 *   <li><b>Who can manage that allowlist</b> — a small, rarely-changed config value
 *       ({@code app.goku.admin-allowlist}), on purpose kept out of the database: granting admin
 *       access is a higher-trust action than granting chat access, and a config change + redeploy
 *       is the right amount of friction for it.</li>
 * </ul>
 */
@Service
public class GokuAccessService {
    private final CurrentUserService currentUser;
    private final GokuAllowlistService allowlist;

    @Value("${app.goku.enabled:true}") private boolean enabled;
    @Value("${app.goku.admin-allowlist:ash4691@gmail.com}") private String adminAllowlistRaw;
    @Value("${app.goku.anthropic-api-key:}") private String apiKey;

    public GokuAccessService(CurrentUserService currentUser, GokuAllowlistService allowlist) {
        this.currentUser = currentUser;
        this.allowlist = allowlist;
    }

    /** Whether the chat nav entry should render at all for the signed-in user. */
    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.isBlank() && allowlist.isAllowed(currentUserEmail());
    }

    /** Whether the signed-in user can manage who else is allowed to chat with Goku. */
    public boolean isAdmin() {
        return adminEmails().contains(currentUserEmail());
    }

    private String currentUserEmail() {
        String email = currentUser.currentUser().getEmail();
        return email == null ? null : email.toLowerCase();
    }

    private Set<String> adminEmails() {
        return Arrays.stream(adminAllowlistRaw.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    /** Enforced server-side on every {@code /api/goku/chat} call — the frontend hiding the entry is not the gate. */
    public void requireAvailable() {
        if (!isAvailable()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Goku isn't available for this account yet");
        }
    }

    /** Enforced server-side on every admin allowlist call — a hidden admin panel link is not the gate. */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to manage Goku's allowlist");
        }
    }
}
