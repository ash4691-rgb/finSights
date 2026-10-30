package com.finsights.portfolio.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock DataSource dataSource;
    @Mock Connection connection;

    @Test
    void livenessAnswersOkWithoutTouchingTheDatabase() {
        ResponseEntity<Map<String, String>> response = new HealthController(dataSource).live();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(Map.entry("status", "ok"));
        verifyNoInteractions(dataSource);
    }

    @Test
    void healthIsOkWhenTheDatabaseAnswers() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);

        ResponseEntity<Map<String, String>> response = new HealthController(dataSource).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "ok").containsEntry("database", "up");
        verify(connection).close();
    }

    @Test
    void healthIs503WhenTheConnectionIsNotValid() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(false);

        ResponseEntity<Map<String, String>> response = new HealthController(dataSource).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "down").containsEntry("database", "down");
        verify(connection).close();
    }

    @Test
    void healthIs503WhenTheDatabaseCannotBeReached() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        ResponseEntity<Map<String, String>> response = new HealthController(dataSource).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "down");
    }

    @Test
    void neitherProbeIsCacheable() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        HealthController controller = new HealthController(dataSource);

        assertThat(controller.live().getHeaders().getCacheControl()).contains("no-store");
        assertThat(controller.health().getHeaders().getCacheControl()).contains("no-store");
    }
}
