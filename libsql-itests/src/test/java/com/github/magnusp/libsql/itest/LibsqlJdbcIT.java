package com.github.magnusp.libsql.itest;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class LibsqlJdbcIT extends SqldContainerBase {

    private String getDriverJdbcUrl() {
        return "jdbc:libsql:" + getHttpUrl();
    }

    @Test
    void testBasicStatementExecution() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getDriverJdbcUrl())) {
            assertThat(conn.isValid(5)).isTrue();
            assertThat(conn.getAutoCommit()).isTrue();

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE test_simple (id INTEGER PRIMARY KEY, name TEXT)");
                int inserted = stmt.executeUpdate("INSERT INTO test_simple VALUES (1, 'libsql')");
                assertThat(inserted).isEqualTo(1);

                try (ResultSet rs = stmt.executeQuery("SELECT id, name FROM test_simple WHERE id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("id")).isEqualTo(1);
                    assertThat(rs.getString("name")).isEqualTo("libsql");
                    assertThat(rs.next()).isFalse();
                }
            }
        }
    }

    @Test
    void testPreparedStatementAndBatching() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getDriverJdbcUrl())) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE test_batch (id INTEGER PRIMARY KEY, val TEXT, price REAL)");
            }

            String sql = "INSERT INTO test_batch VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 1; i <= 50; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "item_" + i);
                    ps.setDouble(3, i * 1.5);
                    ps.addBatch();
                }
                int[] results = ps.executeBatch();
                assertThat(results).hasSize(50);
                for (int count : results) {
                    assertThat(count).isEqualTo(1);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("SELECT val, price FROM test_batch WHERE id = ?")) {
                ps.setInt(1, 25);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("val")).isEqualTo("item_25");
                    assertThat(rs.getDouble("price")).isEqualTo(37.5);
                }
            }
        }
    }

    @Test
    void testTransactionCommitAndRollback() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getDriverJdbcUrl())) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE test_tx (id INTEGER PRIMARY KEY, status TEXT)");
            }

            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO test_tx VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "committed");
                ps.executeUpdate();
            }
            conn.commit();

            // Next transaction that rolls back
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO test_tx VALUES (?, ?)")) {
                ps.setInt(1, 2);
                ps.setString(2, "aborted");
                ps.executeUpdate();
            }
            conn.rollback();

            conn.setAutoCommit(true);
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM test_tx")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void testDatabaseMetaData() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getDriverJdbcUrl())) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE meta_table (col_a INTEGER PRIMARY KEY, col_b TEXT NOT NULL, col_c REAL)");
            }

            DatabaseMetaData meta = conn.getMetaData();
            assertThat(meta.getDatabaseProductName()).contains("libSQL");

            try (ResultSet rs = meta.getTables(null, null, "meta_table", null)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("TABLE_NAME")).isEqualTo("meta_table");
            }

            try (ResultSet rs = meta.getColumns(null, null, "meta_table", null)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualTo("col_a");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualTo("col_b");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualTo("col_c");
            }

            try (ResultSet rs = meta.getPrimaryKeys(null, null, "meta_table")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualTo("col_a");
            }
        }
    }
}
