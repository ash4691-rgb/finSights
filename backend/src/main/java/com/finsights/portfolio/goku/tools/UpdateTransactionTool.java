package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.TransactionRequest;
import com.finsights.portfolio.dto.TransactionResponse;
import com.finsights.portfolio.goku.GokuWriteTool;
import com.finsights.portfolio.service.HoldingService;
import com.finsights.portfolio.service.TransactionService;
import jakarta.validation.Validator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** DataFlow-owned write — {@link TransactionService#update}, patch-style (see {@link GokuArgs#overlay}). */
@Component
class UpdateTransactionTool implements GokuWriteTool {
    private final TransactionService transactions;
    private final HoldingService holdings;
    private final ObjectMapper json;
    private final Validator validator;

    UpdateTransactionTool(TransactionService transactions, HoldingService holdings, ObjectMapper sharedJson, Validator validator) {
        this.transactions = transactions;
        this.holdings = holdings;
        this.json = sharedJson;
        this.validator = validator;
    }

    @Override public String name() { return "update_transaction"; }

    @Override public String description() {
        return "Corrects an existing transaction of the signed-in user. Send its id plus only the fields to change; "
                + "everything else keeps its current value. This only proposes the change: the user confirms it with a button.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new HashMap<>(CreateTransactionTool.FIELDS);
        schema.put("id", Map.of("type", "string", "description", "The transaction's id, from get_transactions."));
        return Map.of("type", "object", "properties", schema, "required", List.of("id"));
    }

    @Override public String preview(JsonNode input) {
        TransactionResponse current = transactions.get(GokuArgs.requireText(input, "id"));
        TransactionRequest request = GokuArgs.overlay(json, validator, current, input, TransactionRequest.class, "id");
        holdings.get(request.holdingId()); // a moved-to holding must belong to this user too
        return "Update the " + current.type() + " of " + current.date() + " on \"" + current.holdingName() + "\": "
                + GokuArgs.describeChanges(input, "id");
    }

    @Override public Object apply(JsonNode input) {
        String id = GokuArgs.requireText(input, "id");
        TransactionRequest request = GokuArgs.overlay(json, validator, transactions.get(id), input, TransactionRequest.class, "id");
        return transactions.update(id, request);
    }
}
