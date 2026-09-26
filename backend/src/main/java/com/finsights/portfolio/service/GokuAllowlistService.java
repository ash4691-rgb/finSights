package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.GokuAllowedUser;
import com.finsights.portfolio.dto.GokuAllowedUserResponse;
import com.finsights.portfolio.repository.GokuAllowedUserRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who can chat with Goku, backed by the database rather than a config env var — so granting
 * access to a new user is an admin-panel action (see {@code GokuAdminController}), not a config
 * change that needs a redeploy. {@code seed-allowlist} only ever runs once, against an empty
 * table on first boot, so the app isn't invisible out of the box; every change after that goes
 * through this service.
 */
@Service
public class GokuAllowlistService {
    @Value("${app.goku.seed-allowlist:ash4691@gmail.com,demo@finsights.local}") private String seedAllowlist;

    private final GokuAllowedUserRepository allowed;

    public GokuAllowlistService(GokuAllowedUserRepository allowed) { this.allowed = allowed; }

    @PostConstruct
    void seedIfEmpty() {
        if (allowed.count() > 0) return;
        for (String email : seedAllowlist.split(",")) {
            String normalized = email.trim().toLowerCase();
            if (!normalized.isBlank()) allowed.save(new GokuAllowedUser(normalized, "seed"));
        }
    }

    public boolean isAllowed(String email) {
        return email != null && !email.isBlank() && allowed.existsByEmailIgnoreCase(email.trim());
    }

    public List<GokuAllowedUserResponse> list() {
        return allowed.findAllByOrderByCreatedAtAsc().stream().map(this::toResponse).toList();
    }

    /** Idempotent — adding an email that's already allowed just returns the existing entry. */
    public GokuAllowedUserResponse add(String email, String addedBy) {
        String normalized = normalize(email);
        return allowed.findByEmailIgnoreCase(normalized)
                .map(this::toResponse)
                .orElseGet(() -> toResponse(allowed.save(new GokuAllowedUser(normalized, addedBy))));
    }

    /** Idempotent — removing an email that isn't there is a no-op. */
    public void remove(String email) {
        allowed.deleteByEmailIgnoreCase(normalize(email));
    }

    private String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        return email.trim().toLowerCase();
    }

    private GokuAllowedUserResponse toResponse(GokuAllowedUser user) {
        return new GokuAllowedUserResponse(user.getEmail(), user.getCreatedAt(), user.getAddedBy());
    }
}
