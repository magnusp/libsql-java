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
 * Pins the Flyway-facing gaps the smoke test found.
 *
 * <p>These assertions describe current behavior, not desired behavior. If a test
 * starts failing, the driver improved and the workaround in the assertion message
 * can be removed.
 */
class FlywayKnownGapsIT extends FlywaySqldSupport {

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:flyway/migration")
                .cleanDisabled(false)
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
    void cleanIsBlockedUntilMultiStatementSupportLands() throws Exception {
        Flyway flyway = flyway();
        flyway.migrate();

        assertThat(scalarInt("PRAGMA foreign_keys"))
                .as("sqld enables foreign keys on a fresh connection, unlike vanilla SQLite")
                .isEqualTo(1);

        // The decision is to support clean(); see #258. This pin flips when
        // multi-statement PRAGMA/DROP scripts are supported.
        assertThatThrownBy(flyway::clean)
                .as("Flyway sees foreign keys enabled and sends"
                        + " 'PRAGMA foreign_keys = OFF; DROP TABLE ...; PRAGMA foreign_keys = ON'"
                        + " as one statement. sqld rejects that string, so clean cannot finish."
                        + " Supporting clean() is tracked in #258.")
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("Unable to drop")
                .hasStackTraceContaining("SQL string contains more than one statement");

        assertThat(userTables())
                .as("clean stopped at the first table")
                .contains("author");
    }

    @Test
    void autoCommitStatementsDoNotShareSessionState() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");

            try (ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1))
                        .as("each auto-commit statement runs as a one-shot Hrana stream, so"
                                + " connection-scoped state set by PRAGMA is gone by the next"
                                + " statement. Wrap related statements in a transaction.")
                        .isEqualTo(1);
            }
        }
    }
}
