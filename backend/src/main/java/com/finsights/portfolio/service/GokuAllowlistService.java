package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.GokuAllowedUser;
import com.finsights.portfolio.dto.GokuAllowedUserResponse;
import com.finsights.portfolio.repository.GokuAllowedUserRepository;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
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
 *
 * <p>Which model a given user's chats run on is admin-assignable the same way — see
 * {@link #modelFor(String)} and {@link #setModel(String, String)} — with {@code app.goku.model}
 * as the fallback for anyone an admin hasn't explicitly assigned one to.
 */
@Service
public class GokuAllowlistService {
    @Value("${app.goku.seed-allowlist:ash4691@gmail.com,demo@finsights.local}") private String seedAllowlist;
    @Value("${app.goku.model:claude-haiku-4-5-20251001}") private String defaultModel;
    @Value("${app.goku.available-models:claude-opus-5-5,claude-sonnet-5,claude-haiku-4-5-20251001}") private String availableModelsRaw;

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

    /** The model ids an admin may assign — what {@code GokuAdminModal}'s dropdown offers. */
    public List<String> availableModels() {
        return Arrays.stream(availableModelsRaw.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    /** The model a chat turn for this user should run on — their own assignment, or the default. */
    public String modelFor(String email) {
        if (email == null || email.isBlank()) return defaultModel;
        return allowed.findByEmailIgnoreCase(email.trim().toLowerCase())
                .map(GokuAllowedUser::getModel)
                .filter(m -> m != null && !m.isBlank())
                .orElse(defaultModel);
    }

    /** Assigns a specific model to an already-allowed user. Rejects anything not in {@link #availableModels()}. */
    public GokuAllowedUserResponse setModel(String email, String model) {
        GokuAllowedUser user = allowed.findByEmailIgnoreCase(normalize(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That email isn't on the allowlist"));
        user.setModel(normalizeModel(model));
        return toResponse(allowed.save(user));
    }

    private String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        return email.trim().toLowerCase();
    }

    private String normalizeModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        if (trimmed.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model is required");
        if (!availableModels().contains(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported model: " + trimmed);
        }
        return trimmed;
    }

    private GokuAllowedUserResponse toResponse(GokuAllowedUser user) {
        String effectiveModel = user.getModel() != null && !user.getModel().isBlank() ? user.getModel() : defaultModel;
        return new GokuAllowedUserResponse(user.getEmail(), user.getCreatedAt(), user.getAddedBy(), effectiveModel);
    }
}
