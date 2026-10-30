package com.finsights.portfolio.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated probes for uptime monitors / keep-alive schedulers (see SecurityConfig).
 *
 * <ul>
 *   <li>{@code /api/health/live} — liveness: the process is up and serving HTTP. Touches nothing
 *       else, so it is the one to ping on a schedule to stop a free-tier host spinning the
 *       container down — it never wakes (or bills) the database.</li>
 *   <li>{@code /api/health} — health: additionally proves the database answers, returning 503
 *       when it doesn't. Pinging this on a schedule also keeps a serverless Postgres from
 *       suspending, which is usually not what you want.</li>
 * </ul>
 *
 * Responses are never cached, so an intermediary can't answer a probe on the app's behalf.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 3;

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/live")
    ResponseEntity<Map<String, String>> live() {
        return noStore(HttpStatus.OK, Map.of("status", "ok"));
    }

    @GetMapping
    ResponseEntity<Map<String, String>> health() {
        if (databaseIsUp()) {
            return noStore(HttpStatus.OK, Map.of("status", "ok", "database", "up"));
        }
        return noStore(HttpStatus.SERVICE_UNAVAILABLE, Map.of("status", "down", "database", "down"));
    }

    private boolean databaseIsUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            log.warn("Health check: database unreachable: {}", e.getMessage());
            return false;
        }
    }

    private static ResponseEntity<Map<String, String>> noStore(HttpStatus status, Map<String, String> body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
