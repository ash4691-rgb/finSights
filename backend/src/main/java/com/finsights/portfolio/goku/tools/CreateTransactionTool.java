package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.TransactionRequest;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.TransactionService;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * DataFlow-owned write, wrapped for Goku. Calls {@link TransactionService#create}, the same path
 * the Log Transaction form uses — {@code create} resolves {@code holdingId} via its own
 * {@code findOwnedHolding}, scoped to the signed-in user through CurrentUserService, same as
 * every read tool, so this can never touch another user's holding even if the model had its id.
 */
@Component
class CreateTransactionTool implements GokuTool {
    private final TransactionService transactions;
    private final ObjectMapper json;
    private final Validator validator;

    CreateTransactionTool(TransactionService transactions, ObjectMapper sharedJson, Validator validator) {
        this.transactions = transactions;
        this.json = sharedJson;
        this.validator = validator;
    }

    @Override public String name() { return "create_transaction"; }

    @Override public String description() {
        return "Logs a transaction (buy, sell, split, interest, adjustment, or loan repayment) against an "
                + "existing holding of the signed-in user. Confirm the details with the user before calling "
                + "this — get the holding via get_holdings first if you don't already have its id. Creates "
                + "immediately, with no undo tool.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "holdingId", Map.of("type", "string", "description", "The holding's id, from get_holdings."),
                        "type", Map.of("type", "string", "enum", List.of("BUY", "SELL", "SPLIT", "INTEREST", "ADJUSTMENT", "REPAY")),
                        "date", Map.of("type", "string", "description", "YYYY-MM-DD."),
                        "amount", Map.of("type", "number", "description", "Always required — pass 0 for a SPLIT (it has no money amount)."),
                        "quantity", Map.of("type", "number", "description",
                                "Units bought/sold for BUY/SELL/ADJUSTMENT. For SPLIT this is the multiplier instead (e.g. 2 for a 2-for-1) and is required."),
                        "interestPaid", Map.of("type", "boolean", "description", "REPAY only: whether this instalment's interest portion was paid."),
                        "notes", Map.of("type", "string")),
                "required", List.of("holdingId", "type", "date", "amount"));
    }

    @Override public Object execute(JsonNode input) {
        TransactionRequest request = GokuArgs.parseAndValidate(json, validator, input, TransactionRequest.class);
        return transactions.create(request);
    }
}
