package com.finsights.portfolio.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DemoModeGuardTest {
    private static final String POSTGRES = "jdbc:postgresql://db.example/finsights";

    @Test
    void googleModeStartsAgainstPostgres() {
        assertThatCode(() -> new DemoModeGuard("google", POSTGRES, false).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:file:./data/finsights;MODE=PostgreSQL", "JDBC:H2:mem:test", "  jdbc:h2:mem:x"})
    void demoModeStartsAgainstH2(String url) {
        assertThatCode(() -> new DemoModeGuard("demo", url, false).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void demoModeStartsWithNoDatasourceUrl(String url) {
        assertThatCode(() -> new DemoModeGuard("demo", url, false).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"demo", "DEMO", " demo "})
    void demoOrBlankModeAgainstPostgresIsRefused(String mode) {
        DemoModeGuard guard = new DemoModeGuard(mode, POSTGRES, false);
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AUTH_MODE=google");
    }

    @Test
    void escapeHatchAllowsDemoAgainstPostgres() {
        assertThatCode(() -> new DemoModeGuard("demo", POSTGRES, true).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
