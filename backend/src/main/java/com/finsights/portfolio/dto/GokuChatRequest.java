package com.finsights.portfolio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The full conversation so far — the client resends it each turn; Goku keeps no server-side chat history. */
public record GokuChatRequest(@NotEmpty @Size(max = 40) @Valid List<Message> messages) {

    public record Message(
            @NotBlank @Pattern(regexp = "user|assistant", message = "must be \"user\" or \"assistant\"") String role,
            @NotBlank @Size(max = 4000) String content) {}
}
