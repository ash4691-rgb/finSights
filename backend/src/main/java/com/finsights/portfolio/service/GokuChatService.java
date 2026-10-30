package com.finsights.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finsights.portfolio.dto.GokuChatRequest;
import com.finsights.portfolio.dto.GokuChatResponse;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.goku.GokuToolRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tool-calling orchestration loop behind {@code POST /api/goku/chat}: sends the conversation
 * plus every registered {@link GokuTool} to the Claude API, executes whichever tools the model
 * asks for, feeds the results back, and repeats until the model has a final answer (or the round
 * budget below runs out — its own guard against a stuck loop burning API calls forever).
 *
 * <p>Every tool — read or write — runs scoped to the signed-in user via the owning service's own
 * {@link CurrentUserService} lookup: this loop has no user id to pass even if it wanted to, so a
 * write tool creates only in this user's own account and a read tool only ever sees this user's
 * own data. There is no tool, and no planned one, that takes a user/email as an argument.
 */
@Service
public class GokuChatService {
    private static final Logger log = LoggerFactory.getLogger(GokuChatService.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final URI MESSAGES_URI = URI.create("https://api.anthropic.com/v1/messages");
    /** Caps how many tool round-trips one chat turn can spend — a cost/latency guard, not just a UX
     *  one. Higher than a read-only assistant would need because a bulk statement import can mean
     *  one create_holding/create_transaction call per extracted row, in sequence. */
    private static final int MAX_TOOL_ROUNDS = 20;
    private static final int MAX_TOKENS = 2048;

    private static final String SYSTEM_PROMPT = """
            You are Goku, the chat assistant built into FinSights, a personal portfolio tracker.

            Scope:
            - You answer questions about the signed-in user's own portfolio data — what happened, what it's \
            worth, what's due — using only the tools provided. You never see data directly; every fact you \
            state must come from a tool result in this conversation.
            - Never invent a holding, transaction, figure, or trend a tool call didn't return. If a tool has \
            nothing relevant, say so plainly rather than guessing.
            - FinSights tracks wealth; it does not place trades or give investment advice, and neither do you. \
            Never recommend buying, selling, or reallocating anything, and never predict future prices or \
            returns. If asked for advice, say that's outside what FinSights does, and offer to show the \
            relevant numbers instead.
            - You can only ever see or change the signed-in user's own data — there is no tool, for reading \
            or writing, that can reach anyone else's.
            - Keep answers short, concrete, and numbers-first. Name the currency on every money figure.

            Making changes (create_category, create_holding, create_transaction, import_statement):
            - These take effect immediately and there is no undo tool. Always state exactly what you're \
            about to create (or, for import_statement, what you're about to read) and get an explicit yes \
            before calling the tool — never create something because it seemed implied.
            - A holding needs its category (get_categories first if you don't have the id) and a transaction \
            needs its holding (get_holdings first) — never guess an id.
            - import_statement only reads a statement the user attached and reports candidate rows; it saves \
            nothing. Walk through what it found, flag anything in its `warnings`, and only call \
            create_holding/create_transaction for rows the user confirms — one tool call per row. Check \
            get_holdings first for a holding the statement's row might already match, and ask the user rather \
            than silently creating a duplicate.
            - If a required detail is missing or ambiguous, ask instead of filling in a plausible-looking \
            value.
            """;

    private final GokuToolRegistry tools;
    private final GokuRateLimiter rateLimiter;
    private final CurrentUserService currentUser;
    private final GokuAllowlistService allowlist;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${app.goku.anthropic-api-key:}") private String apiKey;

    public GokuChatService(GokuToolRegistry tools, GokuRateLimiter rateLimiter, CurrentUserService currentUser,
            GokuAllowlistService allowlist) {
        this.tools = tools;
        this.rateLimiter = rateLimiter;
        this.currentUser = currentUser;
        this.allowlist = allowlist;
    }

    public GokuChatResponse respond(GokuChatRequest request) {
        String userId = currentUser.currentUser().getId();
        if (!rateLimiter.tryConsume(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've hit Goku's daily question limit — try again tomorrow.");
        }
        List<GokuChatRequest.Message> history = request.messages();
        if (!"user".equals(history.get(history.size() - 1).role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The conversation must end with a user message");
        }

        ArrayNode messages = json.createArrayNode();
        for (GokuChatRequest.Message m : history) {
            ObjectNode node = json.createObjectNode();
            node.put("role", m.role());
            node.put("content", m.content());
            messages.add(node);
        }

        String model = allowlist.modelFor(currentUser.currentUser().getEmail());
        String reply = converse(messages, model);
        return new GokuChatResponse(reply, rateLimiter.remainingToday(userId));
    }

    private String converse(ArrayNode messages, String model) {
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JsonNode response = callClaude(messages, model);
            JsonNode content = response.path("content");
            ObjectNode assistantMessage = json.createObjectNode();
            assistantMessage.put("role", "assistant");
            assistantMessage.set("content", content);
            messages.add(assistantMessage);

            if (!"tool_use".equals(response.path("stop_reason").asText())) {
                return extractText(content);
            }
            messages.add(runTools(content));
        }
        log.warn("Goku hit its {}-round tool budget without a final answer", MAX_TOOL_ROUNDS);
        return "I wasn't able to fully work that out within my tool budget — try asking something more specific.";
    }

    /** Executes every tool_use block in one assistant turn and packages the results as the next user turn. */
    private ObjectNode runTools(JsonNode content) {
        ArrayNode results = json.createArrayNode();
        for (JsonNode block : content) {
            if (!"tool_use".equals(block.path("type").asText())) continue;
            String toolUseId = block.path("id").asText();
            String toolName = block.path("name").asText();
            JsonNode input = block.path("input");

            String resultJson;
            boolean isError = false;
            try {
                GokuTool tool = tools.find(toolName)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown tool \"" + toolName + "\""));
                resultJson = json.writeValueAsString(tool.execute(input));
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                log.info("Goku tool {} failed for this call: {}", toolName, e.getMessage());
                resultJson = "{\"error\":" + json.valueToTree(e.getMessage() == null ? "Something went wrong" : e.getMessage()) + "}";
                isError = true;
            }

            ObjectNode toolResult = json.createObjectNode();
            toolResult.put("type", "tool_result");
            toolResult.put("tool_use_id", toolUseId);
            toolResult.put("content", resultJson);
            if (isError) toolResult.put("is_error", true);
            results.add(toolResult);
        }
        ObjectNode userMessage = json.createObjectNode();
        userMessage.put("role", "user");
        userMessage.set("content", results);
        return userMessage;
    }

    private String extractText(JsonNode content) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                if (!text.isEmpty()) text.append("\n");
                text.append(block.path("text").asText());
            }
        }
        return text.isEmpty() ? "I don't have an answer for that." : text.toString();
    }

    private JsonNode callClaude(ArrayNode messages, String model) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", SYSTEM_PROMPT);
        body.set("messages", messages);
        body.set("tools", json.valueToTree(tools.definitions()));

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(MESSAGES_URI)
                    .timeout(Duration.ofSeconds(30))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> httpResponse = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() != 200) {
                log.warn("Anthropic API returned {}: {}", httpResponse.statusCode(), httpResponse.body());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Goku's model backend is unavailable right now — try again shortly.");
            }
            return json.readTree(httpResponse.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to reach the Anthropic API", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Goku's model backend is unavailable right now — try again shortly.");
        }
    }
}
