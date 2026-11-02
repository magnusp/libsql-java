package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.client.LibsqlClient;
import io.github.magnusp.libsql.client.LibsqlClientConfig;
import io.github.magnusp.libsql.hrana.codec.Batch;
import io.github.magnusp.libsql.hrana.codec.BatchResult;
import io.github.magnusp.libsql.hrana.codec.Stmt;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LibsqlConnectionTransactionModeTest {

    private static final LibsqlClientConfig CONFIG = new LibsqlClientConfig("http://localhost:1", null);

    @Test
    void immediateConnectionBeginsItsFirstTransactionImmediately() throws SQLException {
        RecordingClient client = new RecordingClient();
        LibsqlConnection connection = new LibsqlConnection(
                CONFIG, client, null, LibsqlConnection.TransactionMode.IMMEDIATE);

        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("INSERT INTO t (id) VALUES (1)"));

        assertThat(client.sent).first().isEqualTo("BEGIN IMMEDIATE");
        assertThat(connection.getTransactionMode()).isEqualTo(LibsqlConnection.TransactionMode.IMMEDIATE);
    }

    @Test
    void exclusiveConnectionBeginsItsFirstTransactionExclusively() throws SQLException {
        RecordingClient client = new RecordingClient();
        LibsqlConnection connection = new LibsqlConnection(
                CONFIG, client, null, LibsqlConnection.TransactionMode.EXCLUSIVE);

        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("INSERT INTO t (id) VALUES (1)"));

        assertThat(client.sent).first().isEqualTo("BEGIN EXCLUSIVE");
    }

    @Test
    void connectionsWithoutAnExplicitModeBeginDeferred() throws SQLException {
        RecordingClient client = new RecordingClient();
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);

        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("INSERT INTO t (id) VALUES (1)"));

        assertThat(client.sent).first().isEqualTo("BEGIN");
        assertThat(connection.getTransactionMode()).isEqualTo(LibsqlConnection.TransactionMode.DEFERRED);
    }

    private static final class RecordingClient implements LibsqlClient {

        private final List<String> sent = new ArrayList<>();
        private boolean openStream;

        @Override
        public StmtResult executeOneShot(Stmt stmt) {
            return emptyResult();
        }

        @Override
        public StmtResult executeOnStream(Stmt stmt) {
            sent.add(stmt.sql());
            openStream = true;
            return emptyResult();
        }

        @Override
        public BatchResult executeBatchOneShot(Batch batch) {
            return emptyBatch(batch);
        }

        @Override
        public BatchResult executeBatchOnStream(Batch batch) {
            openStream = true;
            return emptyBatch(batch);
        }

        @Override
        public boolean hasOpenStream() {
            return openStream;
        }

        @Override
        public void closeStream() {
            openStream = false;
        }

        @Override
        public void close() {
            openStream = false;
        }

        private static StmtResult emptyResult() {
            return new StmtResult(List.of(), List.of(), 0, null);
        }

        private static BatchResult emptyBatch(Batch batch) {
            Map<Integer, StmtResult> results = new LinkedHashMap<>();
            for (int i = 0; i < batch.steps().size(); i++) {
                if (batch.steps().get(i).stmt() != null) {
                    results.put(i, emptyResult());
                }
            }
            return new BatchResult(results, Map.of());
        }
    }
}
