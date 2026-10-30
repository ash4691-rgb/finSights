package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.TransactionResponse;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.HoldingService;
import com.finsights.portfolio.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * DataFlow-owned data, wrapped for Goku — calls the same {@code TransactionService} the
 * Transactions page uses. The category and tag filters and the totals are projections over that
 * list (a tag lives on the holding, so it's resolved to holding ids via {@code HoldingService}).
 */
@Component
class GetTransactionsTool implements GokuTool {
    static final int MAX_ROWS = 150;

    private final TransactionService transactions;
    private final HoldingService holdings;

    GetTransactionsTool(TransactionService transactions, HoldingService holdings) {
        this.transactions = transactions;
        this.holdings = holdings;
    }

    @Override public String name() { return "get_transactions"; }

    @Override public String description() {
        return "Lists the signed-in user's transactions, most recent first, plus `totals` (count and amount per type "
                + "and currency) computed over every match — quote those rather than adding rows up yourself. Filter by "
                + "holding, category, tag, transaction type, broker (substring), and/or a date range. At most " + MAX_ROWS
                + " rows are listed (`truncated` says so); totals always cover every match.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.ofEntries(
                Map.entry("holding_id", Map.of("type", "string",
                        "description", "Only transactions for this holding id, as returned by get_holdings.")),
                Map.entry("type", Map.of("type", "string",
                        "enum", Arrays.stream(TransactionType.values()).map(Enum::name).toList(),
                        "description", "Only transactions of this type.")),
                Map.entry("broker", Map.of("type", "string", "description", "Only transactions whose holding's broker contains this text.")),
                Map.entry("category_id", Map.of("type", "string", "description", "Only transactions on holdings in this category id, from get_categories.")),
                Map.entry("tag", Map.of("type", "string", "description", "Only transactions on holdings carrying this tag (case-insensitive).")),
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
        String categoryId = GokuArgs.text(input, "category_id");
        String tag = GokuArgs.text(input, "tag");
        Set<String> taggedHoldings = tag == null ? null : holdings.list(null).stream()
                .filter(h -> h.tags() != null && h.tags().stream().anyMatch(tag::equalsIgnoreCase))
                .map(HoldingResponse::id).collect(Collectors.toSet());
        List<TransactionResponse> matches = transactions.list(holdingId, type, broker, from, to, currency).stream()
                .filter(t -> categoryId == null || categoryId.equals(t.categoryId()))
                .filter(t -> taggedHoldings == null || taggedHoldings.contains(t.holdingId()))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", matches.size());
        result.put("totals", totals(matches));
        result.put("transactions", matches.size() > MAX_ROWS ? matches.subList(0, MAX_ROWS) : matches);
        result.put("truncated", matches.size() > MAX_ROWS);
        return result;
    }

    private static List<Map<String, Object>> totals(List<TransactionResponse> matches) {
        return matches.stream()
                .collect(Collectors.groupingBy(t -> t.type() + "|" + t.currency(), LinkedHashMap::new, Collectors.toList()))
                .values().stream().map(rows -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("type", rows.get(0).type());
                    row.put("currency", rows.get(0).currency());
                    row.put("count", rows.size());
                    row.put("amount", rows.stream().map(TransactionResponse::amount).filter(a -> a != null)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
                    return row;
                }).toList();
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
