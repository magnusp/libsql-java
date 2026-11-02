package io.github.magnusp.libsql.itest;

import io.github.magnusp.libsql.jdbc.LibsqlConnection;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.magnusp.libsql.itest.SqldTransactionSupport.assertWriteIsBlockedUntilReleased;
import static io.github.magnusp.libsql.itest.SqldTransactionSupport.createTable;
import static io.github.magnusp.libsql.itest.SqldTransactionSupport.insertRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TransactionModeIT extends SqldContainerBase {

    @Test
    void urlImmediateTransactionModeHoldsTheWriteLockFromTheFirstStatement() throws Exception {
        String url = "jdbc:libsql:" + getHttpUrl() + "?transactionMode=immediate";
        try (Connection lockHolder = DriverManager.getConnection(url);
             Statement statement = lockHolder.createStatement()) {
            createTable(lockHolder, "url_immediate_lock");
            assertThat(lockHolder.unwrap(LibsqlConnection.class).getTransactionMode())
                    .isEqualTo(LibsqlConnection.TransactionMode.IMMEDIATE);

            lockHolder.setAutoCommit(false);
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM url_immediate_lock")) {
                assertThat(rows.next()).isTrue();
            }

            assertWriteIsBlockedUntilReleased(url, lockHolder, "url_immediate_lock", 1);
        }
    }

    @Test
    void urlImmediateTransactionModeUpgradesAReadToAWriteAndCommitsWhileAWriterWaits()
            throws Exception {
        String url = "jdbc:libsql:" + getHttpUrl() + "?transactionMode=immediate";
        try (Connection transaction = DriverManager.getConnection(url);
             Statement statement = transaction.createStatement()) {
            createTable(transaction, "url_immediate_read_write");
            transaction.setAutoCommit(false);
            try (ResultSet rows =
                    statement.executeQuery("SELECT COUNT(*) FROM url_immediate_read_write")) {
                assertThat(rows.next()).isTrue();
            }

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> waitingWriter = executor.submit(() -> {
                    try (Connection writer = DriverManager.getConnection(url)) {
                        return insertRow(writer, "url_immediate_read_write", 1);
                    }
                });
                assertThatThrownBy(() -> waitingWriter.get(300, TimeUnit.MILLISECONDS))
                        .as("immediate takes the write lock at the read, so the writer waits")
                        .isInstanceOf(TimeoutException.class);

                assertThat(statement.executeUpdate(
                                "INSERT INTO url_immediate_read_write (id) VALUES (2)"))
                        .isEqualTo(1);
                transaction.commit();

                assertThat(waitingWriter.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }

        try (Connection verifier = DriverManager.getConnection(url);
             Statement statement = verifier.createStatement();
             ResultSet rows =
                     statement.executeQuery("SELECT COUNT(*) FROM url_immediate_read_write")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void urlDeferredTransactionModeDoesNotHoldTheWriteLockFromTheFirstStatement() throws Exception {
        String url = "jdbc:libsql:" + getHttpUrl() + "?transactionMode=deferred";
        try (Connection lockHolder = DriverManager.getConnection(url);
             Statement statement = lockHolder.createStatement();
             Connection writer = DriverManager.getConnection(url)) {
            createTable(lockHolder, "url_deferred_lock");
            assertThat(lockHolder.unwrap(LibsqlConnection.class).getTransactionMode())
                    .isEqualTo(LibsqlConnection.TransactionMode.DEFERRED);

            lockHolder.setAutoCommit(false);
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM url_deferred_lock")) {
                assertThat(rows.next()).isTrue();
            }

            assertThat(insertRow(writer, "url_deferred_lock", 1)).isEqualTo(1);
            lockHolder.rollback();
        }
    }
}
