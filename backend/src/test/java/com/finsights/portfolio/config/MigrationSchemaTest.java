package com.finsights.portfolio.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Builds a fresh database purely from the Flyway migrations, then has Hibernate validate every entity
 * against it. Fails when an entity gains a column/table that no migration creates.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:migrations;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class MigrationSchemaTest {
    @Autowired private DataSource dataSource;

    @Test
    void migrationsBuildASchemaTheEntitiesValidateAgainst() {
        var info = Flyway.configure().dataSource(dataSource).load().info();
        assertThat(info.applied()).isNotEmpty();
        assertThat(info.pending()).isEmpty();
    }
}
