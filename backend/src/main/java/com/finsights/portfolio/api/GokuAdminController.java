package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.GokuAccessRequest;
import com.finsights.portfolio.dto.GokuAccessResult;
import com.finsights.portfolio.dto.GokuModelTier;
import com.finsights.portfolio.dto.GokuUserSearchResult;
import com.finsights.portfolio.service.CurrentUserService;
import com.finsights.portfolio.service.GokuAccessService;
import com.finsights.portfolio.service.GokuAllowlistService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Who can chat with Goku and on which model — gated to {@code app.goku.admin-allowlist} on every call, not just by hiding the panel. */
@RestController
@RequestMapping("/api/goku/admin")
public class GokuAdminController {
    private final GokuAccessService access;
    private final GokuAllowlistService allowlist;
    private final CurrentUserService currentUser;

    public GokuAdminController(GokuAccessService access, GokuAllowlistService allowlist, CurrentUserService currentUser) {
        this.access = access;
        this.allowlist = allowlist;
        this.currentUser = currentUser;
    }

    /** Typeahead: up to 10 registered users matching {@code q}, each tagged with their current tier. */
    @GetMapping("/users")
    List<GokuUserSearchResult> searchUsers(@RequestParam String q) {
        access.requireAdmin();
        return allowlist.searchUsers(q);
    }

    /** Every allowed user bucketed by tier — the three expandable sections in the admin panel. */
    @GetMapping("/allowlist/grouped")
    Map<GokuModelTier, List<String>> grouped() {
        access.requireAdmin();
        return allowlist.groupedByTier();
    }

    /** Grants, changes, or (with {@code NO_ACCESS}) revokes one user's access, in one action. */
    @PutMapping("/allowlist/access")
    GokuAccessResult setAccess(@RequestParam String email, @Valid @RequestBody GokuAccessRequest request) {
        access.requireAdmin();
        return allowlist.setAccess(email, request.tier(), currentUser.currentUser().getEmail());
    }
}
