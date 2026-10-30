package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.CurrentUserService;
import com.finsights.portfolio.service.GokuUploadStore;
import com.finsights.portfolio.service.StatementImportService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Platform-owned write-adjacent tool — this is the same bulk-ingestion flow the Sources page's
 * statement upload uses ({@link StatementImportService#extract}), one step earlier than a write:
 * it only ever reads the statement and reports back candidate holdings/transactions, exactly as
 * {@code StatementImportService}'s own class comment describes. Nothing is saved until Goku (with
 * the user's go-ahead) calls {@code create_holding}/{@code create_transaction} for the rows that
 * were confirmed — same "never trust an auto-populated row more than a typed one" posture as the
 * page, just mediated by conversation instead of a review table.
 *
 * <p>The file itself never reaches the model or this tool's input — only an {@code upload_id} a
 * prior {@code POST /api/goku/uploads} call produced, which {@link GokuUploadStore#take} resolves
 * scoped to the signed-in user (and consumes, so it can't be replayed).
 */
@Component
class ImportStatementTool implements GokuTool {
    private final StatementImportService statementImport;
    private final GokuUploadStore uploads;
    private final CurrentUserService currentUser;

    ImportStatementTool(StatementImportService statementImport, GokuUploadStore uploads, CurrentUserService currentUser) {
        this.statementImport = statementImport;
        this.uploads = uploads;
        this.currentUser = currentUser;
    }

    @Override public String name() { return "import_statement"; }

    @Override public String description() {
        return "Reads a statement file (a CAS, or a broker's holding/transaction statement — PDF, "
                + "XLSX/XLS, or CSV/TSV/XML) the user has attached to this chat, and extracts candidate "
                + "holdings and/or transactions from it. This never creates anything by itself — present what "
                + "it found to the user, and only once they confirm a row, call create_holding or "
                + "create_transaction for it (checking get_holdings/get_categories first for an existing "
                + "match, so a statement already reflected in the account isn't duplicated). Only usable once, "
                + "right after the user attaches a file — the upload is consumed on the first read.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "upload_id", Map.of("type", "string",
                                "description", "From the attachment note in the user's message — never ask the user to type this themselves."),
                        "password", Map.of("type", "string",
                                "description", "The document's own password (usually the holder's PAN for a CAS), only if it's encrypted and the user has given you one.")),
                "required", List.of("upload_id"));
    }

    @Override public Object execute(JsonNode input) {
        String uploadId = GokuArgs.text(input, "upload_id");
        if (uploadId == null) throw new IllegalArgumentException("upload_id is required");
        String password = GokuArgs.text(input, "password");
        byte[] file = uploads.take(currentUser.currentUser().getId(), uploadId);
        return statementImport.extract(file, password);
    }
}
