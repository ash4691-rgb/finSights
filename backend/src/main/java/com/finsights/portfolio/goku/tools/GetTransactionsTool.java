package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.TransactionService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** DataFlow-owned data, wrapped for Goku — calls the same {@code TransactionService} the Transactions page uses. */
@Component
class GetTransactionsTool implements GokuTool {
    private final TransactionService transactions;

    GetTransactionsTool(TransactionService transactions) { this.transactions = transactions; }

    @Override public String name() { return "get_transactions"; }

    @Override public String description() {
        return "Lists the signed-in user's transactions, most recent first. Optionally filtered to one holding, "
                + "a transaction type, a broker (substring match), and/or a date range.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.ofEntries(
                Map.entry("holding_id", Map.of("type", "string",
                        "description", "Only transactions for this holding id, as returned by get_holdings.")),
                Map.entry("type", Map.of("type", "string",
                        "enum", Arrays.stream(TransactionType.values()).map(Enum::name).toList(),
                        "description", "Only transactions of this type.")),
                Map.entry("broker", Map.of("type", "string", "description", "Only transactions whose holding's broker contains this text.")),
                Map.entry("from", Map.of("type", "string", "description", "Only transactions on or after this date, YYYY-MM-DD.")),
                Map.entry("to", Map.of("type", "string", "description", "Only transactions on or before this date, YYYY-MM-DD.")),
                Map.entry("currency", Map.of("type", "string",
                        "description", "ISO currency code to convert amounts into, e.g. INR or USD. Omit for each transaction's native currency."))));
    }

    @Override public Object execute(JsonNode input) {
        String holdingId = GokuArgs.text(input, "holding_id");
        TransactionType type = parseType(GokuArgs.text(input, "type"));
        String broker = GokuArgs.text(input, "broker");
        LocalDate from = parseDate(GokuArgs.text(input, "from"));
        LocalDate to = parseDate(GokuArgs.text(input, "to"));
        String currency = GokuArgs.text(input, "currency");
        return transactions.list(holdingId, type, broker, from, to, currency);
    }

    private TransactionType parseType(String raw) {
        if (raw == null) return null;
        try {
            return TransactionType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown transaction type \"" + raw + "\" — expected one of "
                    + Arrays.stream(TransactionType.values()).map(Enum::name).collect(Collectors.joining(", ")));
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date \"" + raw + "\" isn't in YYYY-MM-DD format");
        }
    }
}
