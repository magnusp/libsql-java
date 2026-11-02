package io.github.magnusp.libsql.jdbc.flyway;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A generalized sample of a downstream report: a seeding migration appeared to lose
 * a child row while Flyway reported success, with {@code PRAGMA foreign_keys = ON}
 * on every connection.
 *
 * <p>Two shapes are separated here. Seeding a parent and then an
 * {@code INSERT OR IGNORE} child across migrations keeps every row. Rebuilding a
 * parent table later with the copy, drop, rename sequence does not: with foreign
 * keys enforced, {@code DROP TABLE} runs an implicit {@code DELETE FROM}, and
 * {@code ON DELETE CASCADE} removes the child rows. That second test pins the
 * behavior instead of treating it as row loss.
 *
 * <p>Foreign keys are off in SQLite unless the connection turns them on, which is
 * why {@link FlywaySqldSupport#dataSourceWithForeignKeysEnabled()} exists. The
 * migrations live in {@code flyway/foreign-keys}: V1 writes a parent in an earlier
 * migration, V2 seeds two job rows, and V3 rebuilds the customer table.
 */
class FlywayForeignKeySeedIT extends FlywaySqldSupport {

    private static Flyway flyway() {
        return flyway(null);
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSourceWithForeignKeysEnabled())
                .locations("classpath:flyway/foreign-keys")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    @BeforeEach
    void emptyDatabase() throws Exception {
        resetDatabase();
    }

    @Test
    void seedingParentsAndChildrenAcrossMigrationsKeepsEveryRow() throws Exception {
        MigrateResult result = flyway("2").migrate();

        assertThat(result.migrationsExecuted).isEqualTo(2);
        assertThat(scalarInt("SELECT count(*) FROM reseller")).isEqualTo(1);
        assertThat(scalarInt("SELECT count(*) FROM job"))
                .as("INSERT OR IGNORE and INSERT are both visible to a foreign-key-enabled migration")
                .isEqualTo(2);
        assertThat(scalarInt("SELECT count(*) FROM job WHERE id = 'job-ignored'"))
                .as("the ignored insert references three parents, one from an earlier migration")
                .isEqualTo(1);
        assertThat(scalarInt("SELECT count(*) FROM job WHERE id = 'job-plain'")).isEqualTo(1);
    }

    @Test
    void rebuildingAParentTableCascadesChildRows() throws Exception {
        MigrateResult result = flyway().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(3);
        assertThat(scalarInt("SELECT count(*) FROM customer"))
                .as("the rebuild copies the parent rows into the replacement table")
                .isEqualTo(1);
        assertThat(scalarInt("SELECT count(*) FROM job"))
                .as("DROP TABLE runs an implicit DELETE FROM and ON DELETE CASCADE removes both jobs")
                .isZero();
        assertThat(scalarInt("SELECT count(*) FROM operator")).isEqualTo(1);
    }

    @Test
    void foreignKeysAreOnForTheMigrationConnection() throws Exception {
        flyway().migrate();

        try (var connection = dataSourceWithForeignKeysEnabled().getConnection();
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1))
                    .as("the DataSource enables foreign keys before Flyway takes the connection")
                    .isEqualTo(1);
        }
    }
}
