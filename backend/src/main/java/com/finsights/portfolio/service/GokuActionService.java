package com.finsights.portfolio.service;

import com.finsights.portfolio.dto.GokuActionResult;
import com.finsights.portfolio.goku.GokuToolRegistry;
import com.finsights.portfolio.goku.GokuWriteTool;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The only code path that ever applies a Goku write: the signed-in user confirming a pending
 * action they were shown. The action is resolved scoped to that user ({@link GokuActionStore#take}),
 * and the write tool then runs in this same request's security context — so its service call
 * scopes to the same user a second time, independently.
 */
@Service
public class GokuActionService {
    private final GokuActionStore actions;
    private final GokuToolRegistry tools;
    private final CurrentUserService currentUser;

    public GokuActionService(GokuActionStore actions, GokuToolRegistry tools, CurrentUserService currentUser) {
        this.actions = actions;
        this.tools = tools;
        this.currentUser = currentUser;
    }

    public GokuActionResult confirm(String actionId) {
        GokuActionStore.Stored action = actions.take(currentUser.currentUser().getId(), actionId);
        GokuWriteTool tool = tools.find(action.toolName())
                .filter(GokuWriteTool.class::isInstance).map(GokuWriteTool.class::cast)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "That change is no longer supported."));
        try {
            tool.apply(action.input());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return new GokuActionResult(actionId, action.view().summary(), true);
    }

    public void dismiss(String actionId) {
        actions.take(currentUser.currentUser().getId(), actionId);
    }
}
