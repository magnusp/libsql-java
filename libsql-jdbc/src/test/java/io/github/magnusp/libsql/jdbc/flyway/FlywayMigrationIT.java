package io.github.magnusp.libsql.jdbc.flyway;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end Flyway probes. Every migration below travels through the libSQL JDBC
 * driver to a real sqld container.
 *
 * <p>Flyway owns the schema, so these tests cover the driver paths Flyway depends
 * on: multi-statement migration scripts, DDL inside a transaction, the schema
 * history table and its timestamp column, baseline, and repeatable migrations.
 *
 * <p>Two Flyway-facing limitations are pinned in {@link FlywayKnownGapsIT}.
 */
class FlywayMigrationIT extends FlywaySqldSupport {

    private static Flyway flyway(String... locations) {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations(locations.length == 0 ? new String[] {"classpath:flyway/migration"} : locations)
                .cleanDisabled(false)
                .load();
    }

    @BeforeEach
    void emptyDatabase() throws Exception {
        resetDatabase();
    }

    @Test
    void migratesThroughADataSource() throws Exception {
        Flyway flyway = flyway();

        MigrateResult result = flyway.migrate();

        assertThat(result.migrationsExecuted)
                .as("V1, V2, V3 and the repeatable view")
                .isEqualTo(4);
        assertThat(userTables()).contains("author", "book", "flyway_schema_history");

        assertThat(scalarInt("SELECT count(*) FROM author")).isEqualTo(2);
        assertThat(scalarInt("SELECT count(*) FROM book")).isEqualTo(2);
        assertThat(scalarInt("SELECT book_count FROM author_summary WHERE name = 'Ursula K. Le Guin'"))
                .isEqualTo(1);

        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion() == null ? "repeatable" : info.getVersion().toString())
                .containsExactly("1", "2", "3", "repeatable");
        assertThat(flyway.info().current().getState().isApplied()).isTrue();
        assertThat(flyway.info().pending()).isEmpty();

        flyway.validate();
    }

    @Test
    void secondMigrateIsANoOp() {
        Flyway flyway = flyway();
        flyway.migrate();

        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void schemaHistoryIsReadableThroughTheDriver() throws Exception {
        flyway().migrate();

        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT installed_rank, version, description, type, success, installed_on"
                                + " FROM flyway_schema_history ORDER BY installed_rank")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("installed_rank")).isEqualTo(1);
            assertThat(resultSet.getString("version")).isEqualTo("1");
            assertThat(resultSet.getString("description")).isEqualTo("create author");
            assertThat(resultSet.getString("type")).isEqualTo("SQL");
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.getTimestamp("installed_on"))
                    .as("installed_on is TEXT with a strftime() default")
                    .isNotNull();
            assertThat(resultSet.wasNull()).isFalse();
        }
    }

    @Test
    void migrateWithASqliteAliasUrlAndADataSource() throws Exception {
        // jdbc:sqlite: is the only prefix Flyway's SQLite plugin accepts. Passing a
        // DataSource still avoids its URL-based driver pick, which hardcodes
        // org.sqlite.JDBC. This proves the alias itself works through the driver.
        Flyway flyway = Flyway.configure()
                .dataSource(new FlywaySqldSupport.DriverManagerDataSource(
                        "jdbc:sqlite:" + httpUrl()))
                .locations("classpath:flyway/migration")
                .load();

        MigrateResult result = flyway.migrate();

        assertThat(result.migrationsExecuted).isEqualTo(4);
        assertThat(scalarInt("SELECT count(*) FROM author")).isEqualTo(2);
    }

    @Test
    void migrateWithUrlConfigurationAndExplicitDriver() throws Exception {
        // This mirrors the Maven plugin / flyway.properties path: url(...) plus an
        // explicit driver is honored, because unlike dataSource(url) it does not
        // build the DriverDataSource eagerly with a null driver class. The URL
        // still has to use the jdbc:sqlite: alias that Flyway's plugin claims.
        ClassicConfiguration configuration = new ClassicConfiguration();
        configuration.setUrl("jdbc:sqlite:" + httpUrl());
        configuration.setDriver("io.github.magnusp.libsql.jdbc.LibsqlDriver");
        configuration.setLocations(new Location("classpath:flyway/migration"));
        Flyway flyway = new Flyway(configuration);

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(scalarInt("SELECT count(*) FROM author")).isEqualTo(2);
    }

    @Test
    void failedMigrationRollsBackItsDdl() throws Exception {
        Flyway flyway = flyway("classpath:flyway/broken");

        assertThatThrownBy(flyway::migrate)
                .as("V2 inserts into a table that does not exist")
                .hasStackTraceContaining("table_that_does_not_exist");

        assertThat(tableExists("migration_probe"))
                .as("V1 committed")
                .isTrue();
        assertThat(tableExists("partial_table"))
                .as("V2's CREATE TABLE must roll back with the failed insert")
                .isFalse();

        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().toString())
                .containsExactly("1");
    }

    @Test
    void baselineAnExistingSchemaThenMigrate() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE author ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " name TEXT NOT NULL,"
                    + " country TEXT)");
            statement.execute("CREATE INDEX author_name_idx ON author (name)");
        }

        Flyway flyway = flyway();
        flyway.baseline();

        MigrateResult result = flyway.migrate();

        assertThat(result.migrationsExecuted)
                .as("V1 is skipped by the baseline; V2, V3 and the repeatable view run")
                .isEqualTo(3);
        assertThat(userTables()).contains("author", "book", "flyway_schema_history");
        assertThat(scalarInt("SELECT count(*) FROM author")).isEqualTo(2);

        MigrationInfo baseline = flyway.info().applied()[0];
        assertThat(baseline.getVersion().toString()).isEqualTo("1");
        assertThat(baseline.getType().name()).isEqualTo("BASELINE");
    }
}
