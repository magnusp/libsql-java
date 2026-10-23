package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlGeneratedKeysTest {

    private static StmtResult updateResult(long affectedRows, Long lastInsertRowid) {
        return new StmtResult(List.of(), List.of(), affectedRows, lastInsertRowid);
    }

    private static StmtResult insertResult(long rowid) {
        return updateResult(1, rowid);
    }

    @Test
    void autoCommitInsertReturnsLastInsertRowidFromStmtResult() throws SQLException {
        // Auto-commit runs every statement one-shot, so the only rowid the driver can report is
        // the one the server sent on this statement's result.
        StmtResult insert = insertResult(42L);
        RecordingConnection connection = new RecordingConnection(insert);
        assertThat(connection.getAutoCommit()).isTrue();

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into k (v) values ('a')");

            assertThat(insert.lastInsertRowid()).isEqualTo(42L);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
                assertThat(keys.getMetaData().getColumnName(1)).isEqualTo("last_insert_rowid()");
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(insert.lastInsertRowid());
                assertThat(keys.next()).isFalse();
            }
        }
    }

    @Test
    void statementReplaceReturnsGeneratedKey() throws SQLException {
        RecordingConnection connection = new RecordingConnection(insertResult(7L));

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("replace into k (id, v) values (7, 'a')");

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(7L);
            }
        }
    }

    @Test
    void cteInsertReturnsGeneratedKey() throws SQLException {
        RecordingConnection connection = new RecordingConnection(insertResult(13L));

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "with cte as (select lower('INSERT') as v) insert into k (v) select v from cte");

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(13L);
            }
        }
    }

    @Test
    void cteSelectReturnsEmptyGeneratedKeys() throws SQLException {
        RecordingConnection connection = new RecordingConnection(updateResult(1, 99L));

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("with cte as (select 'insert' as v) select v from cte");

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isFalse();
            }
        }
    }

    @Test
    void preparedStatementInsertReturnsGeneratedKey() throws SQLException {
        RecordingConnection connection = new RecordingConnection(insertResult(5L));

        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "insert into k (v) values (?)")) {
            statement.setString(1, "a");
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(5L);
                assertThat(keys.next()).isFalse();
            }
        }
    }

    @Test
    void preparedStatementInsertReturningReturnsGeneratedKey() throws SQLException {
        RecordingConnection connection = new RecordingConnection(
                new StmtResult(
                        List.of(new Col("id", "INTEGER")),
                        List.of(new Row(List.of(Value.of(9L)))),
                        1,
                        9L));

        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "insert into k (v) values (?) returning id")) {
            statement.setString(1, "a");
            statement.execute();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(9L);
            }
        }
    }

    @Test
    void getGeneratedKeysWithoutExecuteIsEmpty() throws SQLException {
        RecordingConnection connection = new RecordingConnection(insertResult(1L));

        try (Statement statement = connection.createStatement();
                ResultSet keys = statement.getGeneratedKeys()) {
            assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
            assertThat(keys.next()).isFalse();
        }
    }

    @Test
    void nonInsertExecuteReturnsEmptyGeneratedKeys() throws SQLException {
        RecordingConnection connection = new RecordingConnection(updateResult(1, 99L));

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("update k set v = 'b'");

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
                assertThat(keys.next()).isFalse();
            }
        }
    }

    @Test
    void laterNonInsertClearsPreviousGeneratedKey() throws SQLException {
        RecordingConnection connection = new RecordingConnection(insertResult(11L), updateResult(1, 99L));

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into k (v) values ('a')");
            statement.executeUpdate("delete from k");

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isFalse();
            }
        }
    }

    @Test
    void executeQuerySelectClearsPreviousGeneratedKey() throws SQLException {
        StmtResult rows = new StmtResult(
                List.of(new Col("id", "INTEGER")),
                List.of(new Row(List.of(Value.of(3L)))),
                0,
                null);
        RecordingConnection connection = new RecordingConnection(insertResult(3L), rows);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into k (v) values ('a')");
            try (ResultSet resultSet = statement.executeQuery("select id from k")) {
                assertThat(resultSet.next()).isTrue();
            }

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isFalse();
            }
        }
    }

    @Test
    void executeQueryReturningReportsThisStatementsKeyNotThePreviousOne() throws SQLException {
        StmtResult returning = new StmtResult(
                List.of(new Col("id", "INTEGER")),
                List.of(new Row(List.of(Value.of(9L)))),
                1,
                9L);
        RecordingConnection connection = new RecordingConnection(insertResult(3L), returning);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into k (v) values ('a')");
            try (ResultSet resultSet =
                    statement.executeQuery("insert into k (v) values ('b') returning id")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong(1)).isEqualTo(9L);
            }

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(9L);
                assertThat(keys.next()).isFalse();
            }
        }
    }

    @Test
    void executeThenBatchReportsTheBatchKeysAndUpdateCount() throws SQLException {
        RecordingConnection connection = new RecordingConnection(insertResult(5L));
        connection.batchResults(insertResult(6L), insertResult(7L));

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into k (v) values ('a')");
            statement.addBatch("insert into k (v) values ('b')");
            statement.addBatch("insert into k (v) values ('c')");

            assertThat(statement.executeBatch()).containsExactly(1, 1);
            assertThat(statement.getUpdateCount()).isEqualTo(2);

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
                assertThat(keys.getMetaData().getColumnName(1)).isEqualTo("last_insert_rowid()");
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(6L);
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(7L);
                assertThat(keys.next()).isFalse();
            }
        }
    }

    @Test
    void returnGeneratedKeysBatchReportsEveryStepKey() throws SQLException {
        RecordingConnection connection = new RecordingConnection();
        connection.batchResults(insertResult(11L), insertResult(12L));

        try (LibsqlPreparedStatement statement = (LibsqlPreparedStatement) connection.prepareStatement(
                "insert into k (v) values (?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "a");
            statement.addBatch();
            statement.setString(1, "b");
            statement.addBatch();

            assertThat(statement.executeBatch()).containsExactly(1, 1);
            assertThat(statement.getUpdateCount()).isEqualTo(2);

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(11L);
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(12L);
                assertThat(keys.next()).isFalse();
            }
        }
    }

    @Test
    void executeBatchClosesThePreviousResultSet() throws SQLException {
        StmtResult rows = new StmtResult(
                List.of(new Col("id", "INTEGER")),
                List.of(new Row(List.of(Value.of(1L)))),
                0,
                null);
        RecordingConnection connection = new RecordingConnection(rows);
        connection.batchResults(insertResult(6L));

        try (Statement statement = connection.createStatement()) {
            ResultSet previous = statement.executeQuery("select id from k");
            assertThat(previous.isClosed()).isFalse();

            statement.addBatch("insert into k (v) values ('b')");
            statement.executeBatch();

            assertThat(previous.isClosed()).isTrue();
        }
    }

    @Test
    void failedBatchLeavesNoGeneratedKeys() throws SQLException {
        RecordingConnection connection = new RecordingConnection(insertResult(5L));
        connection.failingBatch(insertResult(6L), "batch entry 1: constraint failed");

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into k (v) values ('a')");
            statement.addBatch("insert into k (v) values ('b')");
            statement.addBatch("insert into k (v) values ('c')");

            assertThatThrownBy(statement::executeBatch)
                    .isInstanceOf(BatchUpdateException.class)
                    .hasMessageContaining("constraint failed")
                    .satisfies(thrown -> assertThat(
                                    ((BatchUpdateException) thrown).getUpdateCounts())
                            .containsExactly(1));

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isFalse();
            }
        }
    }

    @Test
    void supportsGetGeneratedKeysMatchesImplementedBehavior() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new RecordingConnection(insertResult(1L)));

        assertThat(metadata.supportsGetGeneratedKeys()).isTrue();
    }

    private static final class RecordingConnection extends LibsqlConnection {
        private final Deque<StmtResult> results = new ArrayDeque<>();
        private final Deque<List<StmtResult>> batchResults = new ArrayDeque<>();
        private String batchFailure;

        private RecordingConnection(StmtResult... results) {
            super(null, null);
            this.results.addAll(List.of(results));
        }

        private void batchResults(StmtResult... results) {
            batchResults.add(List.of(results));
        }

        private void failingBatch(StmtResult completed, String message) {
            batchResults.add(List.of(completed));
            batchFailure = message;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            return results.size() == 1 ? results.peek() : results.remove();
        }

        @Override
        public List<StmtResult> executeBatchResults(List<Stmt> statements) throws SQLException {
            if (batchFailure != null) {
                String message = batchFailure;
                batchFailure = null;
                List<StmtResult> completed = batchResults.remove();
                throw new BatchUpdateException(
                        message,
                        completed.stream().mapToInt(r -> (int) r.affectedRowCount()).toArray());
            }
            return batchResults.remove();
        }
    }
}
