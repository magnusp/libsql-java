package io.github.magnusp.libsql.itest;

import io.github.magnusp.libsql.jdbc.LibsqlConnection;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static io.github.magnusp.libsql.itest.SqldTransactionSupport.assertWriteIsBlockedUntilReleased;
import static io.github.magnusp.libsql.itest.SqldTransactionSupport.createTable;
import static io.github.magnusp.libsql.itest.SqldTransactionSupport.insertRow;
import static org.assertj.core.api.Assertions.assertThat;

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
