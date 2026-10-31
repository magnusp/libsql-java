package io.github.magnusp.libsql.jdbc.flyway;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the Flyway-facing behavior the smoke test found.
 *
 * <p>URL-based Flyway configuration cannot select the driver from a
 * {@code jdbc:libsql:} URL; the supported configurations are documented in the
 * README. {@code clean()} works when foreign keys are disabled on the
 * connection before Flyway starts its transaction, as SQLite ignores
 * {@code PRAGMA foreign_keys} inside a transaction.
 */
class FlywayKnownGapsIT extends FlywaySqldSupport {

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:flyway/migration")
                .cleanDisabled(false)
                .load();
    }

    /**
     * Flyway wraps {@code clean()} in a transaction. SQLite ignores
     * {@code PRAGMA foreign_keys} inside a transaction, so the pragma must run when the
     * connection is initialized, before Flyway begins its transaction. The driver's
     * connection-scoped auto-commit stream keeps that session state for the later statements.
     */
    private static Flyway flywayWithForeignKeysDisabled() {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:flyway/migration")
                .cleanDisabled(false)
                .initSql("PRAGMA foreign_keys = OFF")
                .load();
    }

    @BeforeEach
    void emptyDatabase() throws Exception {
        resetDatabase();
    }

    @Test
    void libsqlUrlIsNotRecognizedByFlyway() {
        assertThatThrownBy(() -> Flyway.configure()
                        .dataSource(jdbcUrl(), "", "")
                        .load()
                        .migrate())
                .as("Flyway 13's SQLite plugin claims only jdbc:sqlite:, jdbc:sqldroid: and"
                        + " p6spy prefixes. Use the jdbc:sqlite: alias or pass a DataSource.")
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("No Flyway database plugin found to handle")
                .hasMessageContaining("jdbc:libsql:");
    }

    @Test
    void sqliteAliasUrlCannotUseTheLibsqlDriver() {
        assertThatThrownBy(() -> Flyway.configure()
                        .driver("io.github.magnusp.libsql.jdbc.LibsqlDriver")
                        .dataSource("jdbc:sqlite:" + httpUrl(), "", "")
                        .load()
                        .migrate())
                .as("Flyway 13.12 builds its URL-based DriverDataSource eagerly and passes a"
                        + " null driver class, so driver(...) is ignored for this path. Flyway"
                        + " falls back to org.sqlite.JDBC, which is not on the classpath."
                        + " Pass a DataSource instead.")
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("No JDBC driver found to handle");
    }

    @Test
    void productNameStartsWithSqliteSoProductDetectionWorks() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(connection.getMetaData().getDatabaseProductName())
                    .as("Flyway matches SQLite with startsWith(\"SQLite\"), so the suffix is safe")
                    .startsWith("SQLite");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isNotZero();
        }
    }

    @Test
    void cleanDropsEveryUserTableAndTheSchemaHistory() throws Exception {
        Flyway flyway = flywayWithForeignKeysDisabled();
        flyway.migrate();
        assertThat(userTables()).contains("author", "book", "flyway_schema_history");

        flyway.clean();

        assertThat(userTables())
                .as("Flyway sends a PRAGMA/DROP script per table; the driver splits it and"
                        + " runs every statement on the connection's stream")
                .isEmpty();
    }

    @Test
    void autoCommitStatementsShareSessionState() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");

            try (ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1))
                        .as("auto-commit statements run on the connection's session stream, so"
                                + " connection-scoped state set by PRAGMA survives")
                        .isZero();
            }
        }
    }
}
