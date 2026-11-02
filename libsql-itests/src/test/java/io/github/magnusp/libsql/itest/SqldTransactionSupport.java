package io.github.magnusp.libsql.itest;

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

final class SqldTransactionSupport {

    private SqldTransactionSupport() {}

    static void createTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY)");
        }
    }

    static int insertRow(Connection connection, String table, int id) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("INSERT INTO " + table + " (id) VALUES (" + id + ")");
        }
    }

    static void assertWriteIsBlockedUntilReleased(
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
                    try (Connection writer = java.sql.DriverManager.getConnection(url)) {
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
}
