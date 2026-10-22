package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.GokuAllowedUser;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.GokuAccessResult;
import com.finsights.portfolio.dto.GokuModelTier;
import com.finsights.portfolio.dto.GokuUserSearchResult;
import com.finsights.portfolio.repository.GokuAllowedUserRepository;
import com.finsights.portfolio.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * <p>Access is one of three fixed tiers — {@link GokuModelTier#HAIKU}, {@link GokuModelTier#SONNET},
 * or {@link GokuModelTier#OPUS} — each pinned to one admin-configured model id (the latest
 * available for that tier), plus {@link GokuModelTier#NO_ACCESS} to remove someone. There's no
 * free-form model list: {@link #setAccess} is the one entry point that both grants/revokes
 * allowlist membership and assigns the model, and {@link #modelFor} is what a chat request
 * actually resolves to run on.
 */
@Service
public class GokuAllowlistService {
    @Value("${app.goku.seed-allowlist:ash4691@gmail.com,demo@finsights.local}") private String seedAllowlist;
    @Value("${app.goku.model-haiku:claude-haiku-4-5-20251001}") private String modelHaiku;
    @Value("${app.goku.model-sonnet:claude-sonnet-5}") private String modelSonnet;
    @Value("${app.goku.model-opus:claude-opus-5-5}") private String modelOpus;

    private final GokuAllowedUserRepository allowed;
    private final UserAccountRepository users;

    public GokuAllowlistService(GokuAllowedUserRepository allowed, UserAccountRepository users) {
        this.allowed = allowed;
        this.users = users;
    }

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

    /** The model a chat turn for this user should run on — their tier's model, or the Haiku default. */
    public String modelFor(String email) {
        if (email == null || email.isBlank()) return modelHaiku;
        return allowed.findByEmailIgnoreCase(email.trim().toLowerCase())
                .map(GokuAllowedUser::getModel)
                .filter(m -> m != null && !m.isBlank())
                .orElse(modelHaiku);
    }

    /** Up to 10 registered users whose email contains {@code q}, each tagged with their current tier. */
    public List<GokuUserSearchResult> searchUsers(String q) {
        if (q == null || q.trim().length() < 2) return List.of();
        return users.findTop10ByEmailContainingIgnoreCaseOrderByEmailAsc(q.trim()).stream()
                .map(u -> new GokuUserSearchResult(u.getEmail(), u.getDisplayName(), currentTier(u.getEmail())))
                .toList();
    }

    public GokuModelTier currentTier(String email) {
        return allowed.findByEmailIgnoreCase(normalize(email))
                .map(u -> tierFor(u.getModel()))
                .orElse(GokuModelTier.NO_ACCESS);
    }

    /** Every allowed user, bucketed by their current tier — always has all three keys, even if empty. */
    public Map<GokuModelTier, List<String>> groupedByTier() {
        Map<GokuModelTier, List<String>> grouped = new EnumMap<>(GokuModelTier.class);
        grouped.put(GokuModelTier.HAIKU, new ArrayList<>());
        grouped.put(GokuModelTier.SONNET, new ArrayList<>());
        grouped.put(GokuModelTier.OPUS, new ArrayList<>());
        for (GokuAllowedUser user : allowed.findAllByOrderByEmailAsc()) {
            grouped.get(tierFor(user.getModel())).add(user.getEmail());
        }
        return grouped;
    }

    /**
     * Grants, changes, or revokes a user's access in one action. {@code NO_ACCESS} removes them
     * (idempotent if they weren't allowed); any other tier adds them if they're new, then assigns
     * that tier's model.
     */
    public GokuAccessResult setAccess(String email, GokuModelTier tier, String adminEmail) {
        String normalized = normalize(email);
        if (tier == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A tier is required");
        if (tier == GokuModelTier.NO_ACCESS) {
            allowed.deleteByEmailIgnoreCase(normalized);
            return new GokuAccessResult(normalized, GokuModelTier.NO_ACCESS);
        }
        GokuAllowedUser user = allowed.findByEmailIgnoreCase(normalized)
                .orElseGet(() -> new GokuAllowedUser(normalized, adminEmail));
        user.setModel(modelIdFor(tier));
        allowed.save(user);
        return new GokuAccessResult(normalized, tier);
    }

    private String modelIdFor(GokuModelTier tier) {
        return switch (tier) {
            case HAIKU -> modelHaiku;
            case SONNET -> modelSonnet;
            case OPUS -> modelOpus;
            case NO_ACCESS -> throw new IllegalArgumentException("NO_ACCESS has no model");
        };
    }

    /** Maps a stored (or default) model id back to its tier — unrecognized/blank falls back to Haiku. */
    private GokuModelTier tierFor(String modelId) {
        String effective = modelId == null || modelId.isBlank() ? modelHaiku : modelId;
        if (effective.equals(modelOpus)) return GokuModelTier.OPUS;
        if (effective.equals(modelSonnet)) return GokuModelTier.SONNET;
        return GokuModelTier.HAIKU;
    }

    private String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        return email.trim().toLowerCase();
    }
}
