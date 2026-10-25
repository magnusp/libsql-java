package io.github.magnusp.libsql.itest;

import io.github.magnusp.libsql.jdbc.LibsqlConnection;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TransactionLifecycleIT extends SqldContainerBase {

    /** sqld expires an idle Hrana stream after ten seconds. */
    private static final long STREAM_IDLE_TIMEOUT_MILLIS = 12_000;

    private String getDriverJdbcUrl() {
        return "jdbc:libsql:" + getHttpUrl();
    }

    @Test
    void deferredTransactionDoesNotHoldAWriteLock() throws Exception {
        String url = getDriverJdbcUrl();
        try (Connection lockHolder = DriverManager.getConnection(url);
             Connection writer = DriverManager.getConnection(url)) {
            createTable(lockHolder, "deferred_lock");

            lockHolder.setAutoCommit(false);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> insert = executor.submit(() -> insertRow(writer, "deferred_lock", 1));
                assertThat(insert.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void immediateTransactionHoldsTheWriteLockFromTheFirstStatement() throws Exception {
        String url = getDriverJdbcUrl();
        try (Connection lockHolder = DriverManager.getConnection(url);
             Statement statement = lockHolder.createStatement()) {
            createTable(lockHolder, "immediate_lock");
            LibsqlConnection libsql = lockHolder.unwrap(LibsqlConnection.class);
            libsql.setTransactionMode(LibsqlConnection.TransactionMode.IMMEDIATE);

            lockHolder.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO immediate_lock (id) VALUES (100)");

            assertWriteIsBlockedUntilReleased(url, lockHolder, "immediate_lock", 1);
        }
    }

    @Test
    void commitEndsTheTransactionAndTheNextStatementReacquiresTheConfiguredLock() throws Exception {
        String url = getDriverJdbcUrl();
        try (Connection lockHolder = DriverManager.getConnection(url);
             Statement statement = lockHolder.createStatement()) {
            createTable(lockHolder, "commit_rebegin");
            LibsqlConnection libsql = lockHolder.unwrap(LibsqlConnection.class);
            libsql.setTransactionMode(LibsqlConnection.TransactionMode.IMMEDIATE);
            lockHolder.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO commit_rebegin (id) VALUES (100)");

            lockHolder.commit();

            // The next transaction, and its IMMEDIATE lock, begins on the next statement.
            statement.executeUpdate("INSERT INTO commit_rebegin (id) VALUES (101)");

            assertWriteIsBlockedUntilReleased(url, lockHolder, "commit_rebegin", 1);
        }
    }

    @Test
    void aStatementAndCommitAfterAnIdlePeriodLongerThanTheStreamTimeoutSucceed() throws Exception {
        String url = getDriverJdbcUrl();
        try (Connection connection = DriverManager.getConnection(url)) {
            createTable(connection, "idle_pending_begin");

            connection.setAutoCommit(false);
            connection.commit();
            Thread.sleep(STREAM_IDLE_TIMEOUT_MILLIS);

            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate("INSERT INTO idle_pending_begin (id) VALUES (1)"))
                        .isEqualTo(1);
            }
            connection.commit();

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM idle_pending_begin")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void anIdleConnectionHoldsNoStreamAfterCommit() throws Exception {
        String url = getDriverJdbcUrl();
        try (Connection connection = DriverManager.getConnection(url)) {
            createTable(connection, "idle_after_commit");

            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO idle_after_commit (id) VALUES (1)");
            }
            connection.commit();

            Thread.sleep(STREAM_IDLE_TIMEOUT_MILLIS);

            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate("INSERT INTO idle_after_commit (id) VALUES (2)"))
                        .isEqualTo(1);
                assertThat(statement.executeUpdate("INSERT INTO idle_after_commit (id) VALUES (3)"))
                        .isEqualTo(1);
            }
            connection.rollback();
            connection.commit();

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM idle_after_commit")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void failedCommitCanBeRolledBackAndTheConnectionStaysUsable() throws Exception {
        String url = getDriverJdbcUrl();
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("CREATE TABLE p (id INTEGER PRIMARY KEY)");
                statement.execute("CREATE TABLE c (pid INTEGER REFERENCES p(id)"
                        + " DEFERRABLE INITIALLY DEFERRED)");
                statement.executeUpdate("INSERT INTO c (pid) VALUES (1)");
            }

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("FOREIGN KEY");

            connection.rollback();

            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE usable (id INTEGER PRIMARY KEY)");
                assertThat(statement.executeUpdate("INSERT INTO usable (id) VALUES (1)"))
                        .isEqualTo(1);
            }
            connection.commit();

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM usable")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void uniqueViolationKeepsItsMappedSqlStateInBothModes() throws Exception {
        String url = getDriverJdbcUrl();
        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE unique_state (id INTEGER PRIMARY KEY)");
                statement.executeUpdate("INSERT INTO unique_state (id) VALUES (1)");
            }

            assertMappedUniqueViolation(() -> insertRow(connection, "unique_state", 1));

            connection.setAutoCommit(false);
            assertMappedUniqueViolation(() -> insertRow(connection, "unique_state", 1));
            connection.rollback();
        }
    }

    private static void assertMappedUniqueViolation(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .hasMessageContaining("UNIQUE constraint failed")
                .satisfies(thrown -> assertThat(((SQLException) thrown).getSQLState()).isEqualTo("23000"));
    }

    private static void assertWriteIsBlockedUntilReleased(
            String url, Connection lockHolder, String table, int id) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> insert = executor.submit(() -> {
                try (Connection writer = DriverManager.getConnection(url)) {
                    return insertRow(writer, table, id);
                } catch (SQLException e) {
                    return e;
                }
            });

            Object resultWhileLocked;
            try {
                resultWhileLocked = insert.get(300, TimeUnit.MILLISECONDS);
                assertThat(resultWhileLocked)
                        .as("writer must not succeed while the write lock is held")
                        .isNotEqualTo(1);
            } catch (TimeoutException blocked) {
                resultWhileLocked = null;
            }

            lockHolder.setAutoCommit(true);

            if (insert.isDone()) {
                Object completed = insert.get();
                if (completed instanceof SQLException) {
                    try (Connection writer = DriverManager.getConnection(url)) {
                        assertThat(insertRow(writer, table, id)).isEqualTo(1);
                    }
                } else {
                    assertThat(completed).isEqualTo(1);
                }
            } else {
                assertThat(insert.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void createTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY)");
        }
    }

    private static int insertRow(Connection connection, String table, int id) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("INSERT INTO " + table + " (id) VALUES (" + id + ")");
        }
    }
}
