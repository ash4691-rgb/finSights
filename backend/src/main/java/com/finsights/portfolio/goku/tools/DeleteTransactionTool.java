package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.dto.TransactionResponse;
import com.finsights.portfolio.goku.GokuWriteTool;
import com.finsights.portfolio.service.TransactionService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** DataFlow-owned write — {@link TransactionService#delete}, which also re-syncs the holding's figures. */
@Component
class DeleteTransactionTool implements GokuWriteTool {
    private final TransactionService transactions;

    DeleteTransactionTool(TransactionService transactions) { this.transactions = transactions; }

    @Override public String name() { return "delete_transaction"; }

    @Override public String description() {
        return "Deletes one of the signed-in user's transactions; its holding's quantity and values are recalculated. "
                + "Irreversible. This only proposes the change: the user confirms it with a button.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of("id", Map.of("type", "string", "description", "The transaction's id, from get_transactions.")),
                "required", List.of("id"));
    }

    @Override public boolean destructive() { return true; }

    @Override public String preview(JsonNode input) {
        TransactionResponse current = transactions.get(GokuArgs.requireText(input, "id"));
        String amount = current.amount() == null ? "" : " of " + current.amount().toPlainString() + " " + current.currency();
        return "Delete the " + current.type() + amount + " on " + current.date() + " for \"" + current.holdingName()
                + "\". This can't be undone.";
    }

    @Override public Object apply(JsonNode input) {
        String id = GokuArgs.requireText(input, "id");
        transactions.delete(id);
        return Map.of("deleted", id);
    }
}
