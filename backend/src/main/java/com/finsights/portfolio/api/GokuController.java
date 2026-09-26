package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.GokuChatRequest;
import com.finsights.portfolio.dto.GokuChatResponse;
import com.finsights.portfolio.dto.GokuConfigResponse;
import com.finsights.portfolio.service.GokuAccessService;
import com.finsights.portfolio.service.GokuChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/goku")
public class GokuController {
    private final GokuAccessService access;
    private final GokuChatService chat;

    public GokuController(GokuAccessService access, GokuChatService chat) {
        this.access = access;
        this.chat = chat;
    }

    /** Whether the signed-in user should see the chat bubble at all — checked before rendering it. */
    @GetMapping("/config")
    GokuConfigResponse config() {
        return new GokuConfigResponse(access.isAvailable(), access.isAdmin());
    }

    @PostMapping("/chat")
    GokuChatResponse chat(@Valid @RequestBody GokuChatRequest request) {
        access.requireAvailable();
        return chat.respond(request);
    }
}
