package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.HoldingService;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * DataFlow-owned write, wrapped for Goku. Calls {@link HoldingService#create(HoldingRequest)}, the
 * same path the Add Holding form uses — the holding belongs to whichever user is signed in to this
 * chat, the same as every read tool. {@code BROKER_SYNC} is deliberately left out of the
 * valuationMethod enum below: that method means a connected broker keeps the holding's figures
 * current, which this tool has no way to actually wire up.
 */
@Component
class CreateHoldingTool implements GokuTool {
    private final HoldingService holdings;
    private final ObjectMapper json;
    private final Validator validator;

    CreateHoldingTool(HoldingService holdings, ObjectMapper sharedJson, Validator validator) {
        this.holdings = holdings;
        this.json = sharedJson;
        this.validator = validator;
    }

    @Override public String name() { return "create_holding"; }

    @Override public String description() {
        return "Creates a new holding (a stock, fund, crypto position, fixed deposit, loan, or similar) under "
                + "an existing category, for the signed-in user. Confirm the details with the user before "
                + "calling this — get the category via get_categories first if you don't already have its id. "
                + "Creates immediately, with no undo tool.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.ofEntries(
                        Map.entry("categoryId", Map.of("type", "string", "description", "The owning category's id, from get_categories.")),
                        Map.entry("name", Map.of("type", "string", "description", "1-128 characters.")),
                        Map.entry("valuationMethod", Map.of("type", "string",
                                "enum", List.of("MANUAL", "MARKET_PRICE", "FIXED_RATE"),
                                "description", "MARKET_PRICE needs tickerSymbol and quantity; FIXED_RATE needs fixedAnnualRate, compoundingFrequency and fixedRateStartDate. Omit for MANUAL.")),
                        Map.entry("tickerSymbol", Map.of("type", "string", "description", "Exchange ticker, for MARKET_PRICE only, e.g. RELIANCE.NS.")),
                        Map.entry("broker", Map.of("type", "string", "description", "Required — which broker/platform this is held at.")),
                        Map.entry("currency", Map.of("type", "string", "description", "ISO currency code. Omit to use the account's default.")),
                        Map.entry("quantity", Map.of("type", "number", "description", "Required for MARKET_PRICE.")),
                        Map.entry("investedValue", Map.of("type", "number")),
                        Map.entry("currentValue", Map.of("type", "number")),
                        Map.entry("fixedAnnualRate", Map.of("type", "number", "description", "Percent, for FIXED_RATE only, e.g. 7.1.")),
                        Map.entry("compoundingFrequency", Map.of("type", "string",
                                "enum", List.of("DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "HALF_YEARLY", "ANNUALLY", "AT_MATURITY"))),
                        Map.entry("fixedRateStartDate", Map.of("type", "string", "description", "YYYY-MM-DD, for FIXED_RATE only.")),
                        Map.entry("fixedRateEndDate", Map.of("type", "string", "description", "YYYY-MM-DD, optional even for FIXED_RATE.")),
                        Map.entry("repaymentFrequency", Map.of("type", "string",
                                "enum", List.of("WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY", "ONE_TIME"),
                                "description", "For a LIABILITY category's loan only.")),
                        Map.entry("emiAmount", Map.of("type", "number")),
                        Map.entry("emiDayOfMonth", Map.of("type", "integer", "description", "1-31.")),
                        Map.entry("loanTermMonths", Map.of("type", "integer")),
                        Map.entry("repaymentDueDate", Map.of("type", "string", "description", "YYYY-MM-DD, next/first due date.")),
                        Map.entry("liquidWithinSevenDays", Map.of("type", "boolean")),
                        Map.entry("description", Map.of("type", "string")),
                        Map.entry("notes", Map.of("type", "string")),
                        Map.entry("tags", Map.of("type", "array", "items", Map.of("type", "string")))),
                "required", List.of("categoryId", "name", "broker"));
    }

    @Override public Object execute(JsonNode input) {
        HoldingRequest request = GokuArgs.parseAndValidate(json, validator, input, HoldingRequest.class);
        return holdings.create(request);
    }
}
