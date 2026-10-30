package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.GokuChatRequest;
import com.finsights.portfolio.dto.GokuChatResponse;
import com.finsights.portfolio.dto.GokuConfigResponse;
import com.finsights.portfolio.dto.GokuUploadResponse;
import com.finsights.portfolio.service.CurrentUserService;
import com.finsights.portfolio.service.GokuAccessService;
import com.finsights.portfolio.service.GokuChatService;
import com.finsights.portfolio.service.GokuUploadStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/goku")
public class GokuController {
    private final GokuAccessService access;
    private final GokuChatService chat;
    private final GokuUploadStore uploads;
    private final CurrentUserService currentUser;

    public GokuController(GokuAccessService access, GokuChatService chat, GokuUploadStore uploads, CurrentUserService currentUser) {
        this.access = access;
        this.chat = chat;
        this.uploads = uploads;
        this.currentUser = currentUser;
    }

    /** Whether the signed-in user should see the chat bubble at all — checked before rendering it. */
    @GetMapping("/config")
    GokuConfigResponse config() {
        boolean available = access.isAvailable();
        boolean admin = access.isAdmin();
        String reason = admin && !available ? access.unavailableReason() : null;
        return new GokuConfigResponse(available, admin, reason);
    }

    @PostMapping("/chat")
    GokuChatResponse chat(@Valid @RequestBody GokuChatRequest request) {
        access.requireAvailable();
        return chat.respond(request);
    }

    /**
     * Stashes a file the user attached in the chat so a later {@code import_statement} tool call
     * can read it — see {@link GokuUploadStore}. The upload itself never reaches the model; only
     * the returned {@code uploadId} does, via a note the frontend adds to that chat message.
     */
    @PostMapping(value = "/uploads", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    GokuUploadResponse upload(@RequestBody byte[] file, @RequestHeader(value = "X-Filename", required = false) String filename) {
        access.requireAvailable();
        String id = uploads.store(currentUser.currentUser().getId(), filename, file);
        return new GokuUploadResponse(id, filename);
    }
}
