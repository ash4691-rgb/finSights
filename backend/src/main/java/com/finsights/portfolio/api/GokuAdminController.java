package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.GokuAllowedUserResponse;
import com.finsights.portfolio.dto.GokuAllowlistAddRequest;
import com.finsights.portfolio.service.CurrentUserService;
import com.finsights.portfolio.service.GokuAccessService;
import com.finsights.portfolio.service.GokuAllowlistService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Who can chat with Goku — gated to {@code app.goku.admin-allowlist} on every call, not just by hiding the link. */
@RestController
@RequestMapping("/api/goku/admin/allowlist")
public class GokuAdminController {
    private final GokuAccessService access;
    private final GokuAllowlistService allowlist;
    private final CurrentUserService currentUser;

    public GokuAdminController(GokuAccessService access, GokuAllowlistService allowlist, CurrentUserService currentUser) {
        this.access = access;
        this.allowlist = allowlist;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<GokuAllowedUserResponse> list() {
        access.requireAdmin();
        return allowlist.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GokuAllowedUserResponse add(@Valid @RequestBody GokuAllowlistAddRequest request) {
        access.requireAdmin();
        return allowlist.add(request.email(), currentUser.currentUser().getEmail());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@RequestParam String email) {
        access.requireAdmin();
        allowlist.remove(email);
    }
}
