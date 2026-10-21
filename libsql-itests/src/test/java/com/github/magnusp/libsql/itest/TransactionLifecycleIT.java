package com.github.magnusp.libsql.itest;

import com.github.magnusp.libsql.jdbc.LibsqlConnection;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

public class TransactionLifecycleIT extends SqldContainerBase {

    private String getDriverJdbcUrl() {
        return "jdbc:libsql:" + getHttpUrl();
    }

    @Test
    void deferredTransactionBeginsEagerlyWithoutHoldingAWriteLock() throws Exception {
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
    void immediateTransactionBeginsEagerlyAndHoldsTheWriteLock() throws Exception {
        String url = getDriverJdbcUrl();
        try (Connection lockHolder = DriverManager.getConnection(url)) {
            createTable(lockHolder, "immediate_lock");
            LibsqlConnection libsql = lockHolder.unwrap(LibsqlConnection.class);
            libsql.setTransactionMode(LibsqlConnection.TransactionMode.IMMEDIATE);

            lockHolder.setAutoCommit(false);

            assertWriteIsBlockedUntilReleased(url, lockHolder, "immediate_lock", 1);
        }
    }

    @Test
    void commitBeginsTheNextTransactionAndKeepsTheConfiguredLock() throws Exception {
        String url = getDriverJdbcUrl();
        try (Connection lockHolder = DriverManager.getConnection(url);
             Statement statement = lockHolder.createStatement()) {
            createTable(lockHolder, "commit_rebegin");
            LibsqlConnection libsql = lockHolder.unwrap(LibsqlConnection.class);
            libsql.setTransactionMode(LibsqlConnection.TransactionMode.IMMEDIATE);
            lockHolder.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO commit_rebegin (id) VALUES (100)");

            lockHolder.commit();

            assertWriteIsBlockedUntilReleased(url, lockHolder, "commit_rebegin", 1);
        }
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
