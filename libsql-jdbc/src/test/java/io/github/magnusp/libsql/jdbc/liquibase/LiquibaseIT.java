package io.github.magnusp.libsql.jdbc.liquibase;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.core.SQLiteDatabase;
import liquibase.database.core.UnsupportedDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the in-tree driver through Liquibase's update, rollback, sync and
 * snapshot paths against a real sqld container.
 *
 * <p>Liquibase selects its SQLite implementation with
 * {@code "SQLite".equalsIgnoreCase(getDatabaseProductName())}. This driver
 * reports {@code "SQLite (libSQL)"}, so the tests that need Liquibase's SQLite
 * SQL build a {@link SQLiteDatabase} directly. The gap is pinned in
 * {@link #databaseFactoryFallsBackToUnsupportedDatabase()}; when the product
 * name becomes exactly {@code SQLite}, switch {@link #liquibase} back to
 * {@link DatabaseFactory#findCorrectDatabaseImplementation} and delete the pin.
 */
class LiquibaseIT extends LiquibaseSqldSupport {

    @Test
    void updateAppliesChangelogAndIsIdempotent() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl())) {
            Liquibase liquibase = liquibase(conn, "liquibase/changelog-update.xml");

            liquibase.validate();
            liquibase.update(new Contexts());
            liquibase.update(new Contexts());

            assertThat(tableExists(conn, "lb_person")).isTrue();
            assertThat(columnExists(conn, "lb_person", "email")).isTrue();
            assertThat(count(conn, "SELECT COUNT(*) FROM lb_person")).isEqualTo(1);
            assertThat(count(conn, "SELECT COUNT(*) FROM databasechangelog WHERE id IN ('update-1', 'update-2')"))
                    .isEqualTo(2);
            assertThat(queryString(conn, "SELECT email FROM lb_person")).isEqualTo("ada@example.com");
        }
    }

    @Test
    void alterTableChangesRoundTripThroughLiquibaseSnapshots() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl())) {
            Liquibase liquibase = liquibase(conn, "liquibase/changelog-alter.xml");

            liquibase.update(new Contexts());

            assertThat(columnNames(conn, "lb_alter_person"))
                    .as("renameColumn rebuilds the table from a Liquibase snapshot")
                    .containsExactly("id", "given_name", "nickname", "email");
            assertThat(indexNames(conn, "lb_alter_person"))
                    .contains("idx_lb_alter_given", "uq_lb_alter_email");
            assertThat(uniqueIndexNames(conn, "lb_alter_person")).contains("uq_lb_alter_email");
        }
    }

    @Test
    void foreignKeyCreatedByLiquibaseIsVisibleThroughMetadata() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl())) {
            Liquibase liquibase = liquibase(conn, "liquibase/changelog-fk.xml");

            liquibase.update(new Contexts());

            try (ResultSet rs = conn.getMetaData().getImportedKeys(null, null, "lb_fk_child")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("PKTABLE_NAME")).isEqualTo("lb_fk_parent");
                assertThat(rs.getString("FKCOLUMN_NAME")).isEqualTo("parent_id");
                assertThat(rs.getString("FK_NAME")).isEqualTo("fk_lb_child_parent");
                assertThat(rs.getInt("DELETE_RULE")).isEqualTo(DatabaseMetaData.importedKeyCascade);
            }
        }
    }

    @Test
    void rollbackCountRemovesChangesetsInReverse() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl())) {
            Liquibase liquibase = liquibase(conn, "liquibase/changelog-rollback.xml");

            liquibase.update(new Contexts());
            assertThat(tableExists(conn, "lb_rb_widget")).isTrue();
            assertThat(indexNames(conn, "lb_rb_widget")).contains("idx_lb_rb_label");

            liquibase.rollback(1, new Contexts(), new LabelExpression());
            assertThat(tableExists(conn, "lb_rb_widget")).isTrue();
            assertThat(indexNames(conn, "lb_rb_widget")).doesNotContain("idx_lb_rb_label");
            assertThat(count(conn, "SELECT COUNT(*) FROM databasechangelog WHERE id = 'rollback-2'"))
                    .isZero();

            liquibase.rollback(1, new Contexts(), new LabelExpression());
            assertThat(tableExists(conn, "lb_rb_widget")).isFalse();
            assertThat(count(conn, "SELECT COUNT(*) FROM databasechangelog WHERE id = 'rollback-1'"))
                    .isZero();
        }
    }

    @Test
    void dataTypesRoundTripThroughLiquibaseInsert() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl())) {
            Liquibase liquibase = liquibase(conn, "liquibase/changelog-types.xml");

            liquibase.update(new Contexts());

            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT flag, amount, ratio, counter, happened_at,"
                         + " happened_on, happened_time, notes FROM lb_types WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("flag")).isTrue();
                assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo(new BigDecimal("1234.50"));
                assertThat(rs.getDouble("ratio")).isEqualTo(0.25d);
                assertThat(rs.getLong("counter")).isEqualTo(9007199254740993L);
                assertThat(rs.getTimestamp("happened_at")).isEqualTo(Timestamp.valueOf("2024-01-02 03:04:05"));
                assertThat(rs.getDate("happened_on").toLocalDate().toString()).isEqualTo("2024-01-02");
                assertThat(rs.getString("notes")).isEqualTo("hello clob");
            }
        }
    }

    @Test
    void changeLogSyncMarksChangesAsRunWithoutExecutingThem() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl())) {
            Liquibase liquibase = liquibase(conn, "liquibase/changelog-sync.xml");

            liquibase.changeLogSync(new Contexts());

            assertThat(tableExists(conn, "lb_sync_table")).isFalse();
            assertThat(count(conn, "SELECT COUNT(*) FROM databasechangelog WHERE id = 'sync-1'"))
                    .isEqualTo(1);
        }
    }

    /**
     * Pins the driver gap that stops Liquibase from selecting its SQLite
     * dialect when an application lets Liquibase inspect the connection.
     *
     * <p>Liquibase's {@code SQLiteDatabase.isCorrectDatabaseImplementation}
     * compares the product name with {@code equalsIgnoreCase("SQLite")}. The
     * driver reports {@code "SQLite (libSQL)"}, so Liquibase silently falls
     * back to {@link UnsupportedDatabase} and emits generic DDL such as
     * {@code INT GENERATED BY DEFAULT AS IDENTITY}, which sqld rejects.
     * Returning exactly {@code "SQLite"} and keeping the libSQL identity in
     * {@code getDriverName()} would close the gap.
     *
     * <p>This assertion describes current behavior, not desired behavior. If
     * it starts failing because the product name changed, remove this test and
     * the explicit {@link SQLiteDatabase} workaround in {@link #liquibase}.
     */
    @Test
    void databaseFactoryFallsBackToUnsupportedDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl())) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualTo("SQLite (libSQL)");

            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));

            assertThat(database).isInstanceOf(UnsupportedDatabase.class);
        }
    }

    /**
     * Pins the second bootstrapping gap: Liquibase resolves a JDBC driver class
     * from the URL before it opens a connection, and its SQLite implementation
     * only claims the {@code jdbc:sqlite:} prefix. A {@code jdbc:libsql:} URL
     * therefore needs an explicit driver class (or a {@code DataSource}).
     *
     * <p>This assertion describes current behavior. It is not a driver bug on
     * its own, but it is the reason a Liquibase configuration needs
     * {@code driver=io.github.magnusp.libsql.jdbc.LibsqlDriver} in addition to
     * the URL.
     */
    @Test
    void databaseFactoryCannotResolveALibsqlUrlToADriver() {
        assertThatThrownBy(() -> DatabaseFactory.getInstance()
                        .openDatabase(jdbcUrl(), null, null, null, new ClassLoaderResourceAccessor()))
                .hasMessageContaining("Driver class was not specified")
                .hasMessageContaining("jdbc:libsql:");
    }

    private static Liquibase liquibase(Connection conn, String changelog) throws Exception {
        Database database = new SQLiteDatabase();
        database.setConnection(new JdbcConnection(conn));
        return new Liquibase(changelog, new ClassLoaderResourceAccessor(), database);
    }

    private static boolean tableExists(Connection conn, String name) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, name, new String[] {"TABLE"})) {
            return rs.next();
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    private static List<String> columnNames(Connection conn, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME"));
            }
        }
        return names;
    }

    private static List<String> indexNames(Connection conn, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                names.add(rs.getString("INDEX_NAME"));
            }
        }
        return names;
    }

    private static List<String> uniqueIndexNames(Connection conn, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getIndexInfo(null, null, table, true, false)) {
            while (rs.next()) {
                names.add(rs.getString("INDEX_NAME"));
            }
        }
        return names;
    }

    private static int count(Connection conn, String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static String queryString(Connection conn, String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
