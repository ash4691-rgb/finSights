package com.finsights.portfolio.goku.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.StatementExtractionResponse;
import com.finsights.portfolio.service.CurrentUserService;
import com.finsights.portfolio.service.GokuUploadStore;
import com.finsights.portfolio.service.StatementImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImportStatementToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock StatementImportService statementImport;
    @Mock GokuUploadStore uploads;
    @Mock CurrentUserService currentUser;

    private void userIs(String userId) {
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(currentUser.currentUser()).thenReturn(user);
    }

    @Test
    void readsTheUploadScopedToTheSignedInUserAndExtracts() throws Exception {
        ImportStatementTool tool = new ImportStatementTool(statementImport, uploads, currentUser);
        userIs("u-1");
        JsonNode input = mapper.readTree("{\"upload_id\":\"up-1\"}");
        byte[] file = "fake pdf bytes".getBytes();
        when(uploads.take("u-1", "up-1")).thenReturn(file);
        StatementExtractionResponse extracted = mock(StatementExtractionResponse.class);
        when(statementImport.extract(file, null)).thenReturn(extracted);

        Object result = tool.execute(input);

        assertThat(result).isEqualTo(extracted);
        verify(uploads).take("u-1", "up-1");
    }

    @Test
    void passesThroughAPasswordWhenGiven() throws Exception {
        ImportStatementTool tool = new ImportStatementTool(statementImport, uploads, currentUser);
        userIs("u-1");
        JsonNode input = mapper.readTree("{\"upload_id\":\"up-1\",\"password\":\"ABCDE1234F\"}");
        byte[] file = "fake pdf bytes".getBytes();
        when(uploads.take("u-1", "up-1")).thenReturn(file);
        when(statementImport.extract(file, "ABCDE1234F")).thenReturn(mock(StatementExtractionResponse.class));

        tool.execute(input);

        verify(statementImport).extract(file, "ABCDE1234F");
    }

    @Test
    void rejectsAMissingUploadId() throws Exception {
        ImportStatementTool tool = new ImportStatementTool(statementImport, uploads, currentUser);
        JsonNode input = mapper.readTree("{}");

        assertThatThrownBy(() -> tool.execute(input)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(uploads, statementImport);
    }

    @Test
    void neverPassesAnotherUsersUploadThroughAnyLocalShortcut() throws Exception {
        // GokuUploadStore itself enforces the per-user scoping (see GokuUploadStoreTest); this
        // just pins that the tool always calls it with the CALLING user's id, not one from the
        // model's input — there is no "user_id" field in this tool's schema at all.
        ImportStatementTool tool = new ImportStatementTool(statementImport, uploads, currentUser);
        userIs("u-1");
        JsonNode input = mapper.readTree("{\"upload_id\":\"up-1\",\"user_id\":\"someone-else\"}");
        when(uploads.take("u-1", "up-1")).thenReturn("x".getBytes());
        when(statementImport.extract("x".getBytes(), null)).thenReturn(mock(StatementExtractionResponse.class));

        tool.execute(input);

        verify(uploads).take("u-1", "up-1");
    }
}
