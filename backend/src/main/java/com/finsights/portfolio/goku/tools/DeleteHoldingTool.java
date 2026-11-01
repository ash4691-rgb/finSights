package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.goku.GokuWriteTool;
import com.finsights.portfolio.service.HoldingService;
import com.finsights.portfolio.service.TransactionService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** DataFlow-owned write — {@link HoldingService#delete}, which also removes the holding's transactions. */
@Component
class DeleteHoldingTool implements GokuWriteTool {
    private final HoldingService holdings;
    private final TransactionService transactions;

    DeleteHoldingTool(HoldingService holdings, TransactionService transactions) {
        this.holdings = holdings;
        this.transactions = transactions;
    }

    @Override public String name() { return "delete_holding"; }

    @Override public String description() {
        return "Deletes one of the signed-in user's holdings, along with all of its transactions. Irreversible. "
                + "This only proposes the change: the user confirms it with a button.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of("id", Map.of("type", "string", "description", "The holding's id, from get_holdings.")),
                "required", List.of("id"));
    }

    @Override public boolean destructive() { return true; }

    @Override public String preview(JsonNode input) {
        String id = GokuArgs.requireText(input, "id");
        HoldingResponse current = holdings.get(id);
        int txns = transactions.list(id, null, null, null, null, null).size();
        return "Delete holding \"" + current.name() + "\" (" + current.broker() + ") and its " + txns
                + " transaction" + (txns == 1 ? "" : "s") + ". This can't be undone.";
    }

    @Override public Object apply(JsonNode input) {
        String id = GokuArgs.requireText(input, "id");
        holdings.delete(id);
        return Map.of("deleted", id);
    }
}
