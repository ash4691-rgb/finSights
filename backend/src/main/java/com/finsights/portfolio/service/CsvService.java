package com.finsights.portfolio.service;

import com.finsights.portfolio.dto.ImportResultResponse;
import com.finsights.portfolio.dto.TransactionResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Bulk CSV export/import for transactions — instruments and holdings stay manual-entry only. */
@Service
public class CsvService {

    static final List<String> COLUMNS = TransactionRowParser.FIELDS;
    /** Extra, read-only columns appended to exports for context; ignored on import. */
    private static final List<String> EXPORT_EXTRA_COLUMNS = List.of("holdingName", "broker");

    private final TransactionService transactions;

    public CsvService(TransactionService transactions) {
        this.transactions = transactions;
    }

    public String export() {
        List<String> header = new ArrayList<>(COLUMNS);
        header.addAll(EXPORT_EXTRA_COLUMNS);
        StringBuilder out = new StringBuilder(String.join(",", header)).append("\r\n");
        for (TransactionResponse t : transactions.list(null, null, null, null, null, null)) {
            out.append(String.join(",", List.of(
                    cell(t.id()), cell(t.holdingId()), cell(t.type().name()), cell(t.date().toString()),
                    cell(plain(t.amount())), cell(t.quantity() == null ? "" : plain(t.quantity())), cell(nz(t.notes())),
                    cell(nz(t.holdingName())), cell(nz(t.broker()))))).append("\r\n");
        }
        return out.toString();
    }

    public ImportResultResponse importCsv(String body) {
        List<List<String>> rows = parse(body);
        if (rows.isEmpty()) throw new IllegalArgumentException("The CSV file is empty");
        List<String> header = rows.get(0).stream().map(s -> s.trim().toLowerCase()).toList();
        List<Map<String, String>> dataRows = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            Map<String, String> fields = new LinkedHashMap<>();
            for (int col = 0; col < header.size() && col < row.size(); col++) {
                fields.put(header.get(col), row.get(col).trim());
            }
            dataRows.add(fields);
        }
        return TransactionImportRunner.run(dataRows, i -> i + 2, transactions);
    }

    // --- CSV text <-> rows-of-cells parsing ---

    static List<List<String>> parse(String input) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        String text = input.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else inQuotes = false;
                } else field.append(c);
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                current.add(field.toString());
                field.setLength(0);
                rows.add(current);
                current = new ArrayList<>();
            } else field.append(c);
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }

    private static String cell(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String nz(String value) { return value == null ? "" : value; }

    private static String plain(BigDecimal value) { return value == null ? "" : value.toPlainString(); }
}
