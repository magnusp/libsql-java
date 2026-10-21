package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LibsqlGeneratedKeysTest {

    private static StmtResult updateResult(long affectedRows, Long lastInsertRowid) {
        return new StmtResult(List.of(), List.of(), affectedRows, lastInsertRowid);
    }

    private static StmtResult insertResult(long rowid) {
        return updateResult(1, rowid);
    }

    @Test
    void statementInsertReturnsGeneratedKey() throws SQLException {
        RecordingConnection connection = new RecordingConnection(insertResult(42L));

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into k (v) values ('a')");

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(42L);
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
    void executeQueryDoesNotClearPreviousGeneratedKey() throws SQLException {
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
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(3L);
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

        private RecordingConnection(StmtResult... results) {
            super(null, null);
            this.results.addAll(List.of(results));
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            return results.size() == 1 ? results.peek() : results.remove();
        }
    }
}
