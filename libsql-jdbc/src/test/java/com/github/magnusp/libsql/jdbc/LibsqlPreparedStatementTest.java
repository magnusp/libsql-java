package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlPreparedStatementTest {

    private static final StmtResult ROWS = new StmtResult(
            List.of(new Col("id", "INTEGER")),
            List.of(new Row(List.of(Value.of(1L)))),
            0,
            null);

    private static final StmtResult NO_ROWS = new StmtResult(List.of(), List.of(), 1, 1L);

    private static final StmtResult THREE_ROWS = new StmtResult(
            List.of(new Col("id", "INTEGER")),
            List.of(
                    new Row(List.of(Value.of(1L))),
                    new Row(List.of(Value.of(2L))),
                    new Row(List.of(Value.of(3L)))),
            0,
            null);

    @Test
    void executeQueryOnInsertThrowsWithoutExecuting() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "insert into t values (2)")) {
            assertThatThrownBy(statement::executeQuery)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("Query does not return results");
        }

        assertThat(connection.executeCalls).isZero();
    }

    @Test
    void executeUpdateOnSelectThrowsWithoutExecuting() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select id from t")) {
            assertThatThrownBy(statement::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("Query returns results");
        }

        assertThat(connection.executeCalls).isZero();
    }

    @Test
    void executeQueryOnSelectStillExecutes() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select id from t")) {
            ResultSet resultSet = statement.executeQuery();

            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(1L);
        }

        assertThat(connection.executeCalls).isEqualTo(1);
    }

    @Test
    void executeUpdateOnInsertStillExecutes() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "insert into t values (2)")) {
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        assertThat(connection.executeCalls).isEqualTo(1);
    }

    @Test
    void executeQueryOnInsertReturningStillExecutes() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        try (LibsqlPreparedStatement statement = new LibsqlPreparedStatement(
                connection, "insert into t values (2) returning id")) {
            ResultSet resultSet = statement.executeQuery();

            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(1L);
        }

        assertThat(connection.executeCalls).isEqualTo(1);
    }

    @Test
    void executeUpdateOnWithSelectThrowsWithoutExecuting() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        try (LibsqlPreparedStatement statement = new LibsqlPreparedStatement(
                connection, "with ids as (select id from t) select id from ids")) {
            assertThatThrownBy(statement::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("Query returns results");
        }

        assertThat(connection.executeCalls).isZero();
    }

    @Test
    void setMaxRowsLimitsPreparedQueryAndGetMaxRowsReadsItBack() throws SQLException {
        RecordingConnection connection = new RecordingConnection(THREE_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select id from t")) {
            statement.setMaxRows(2);

            assertThat(statement.getMaxRows()).isEqualTo(2);
            ResultSet resultSet = statement.executeQuery();
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(1L);
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(2L);
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void escapeProcessingAppliesToPreparedSql() throws SQLException {
        RecordingConnection connection = new RecordingConnection(THREE_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select {fn abs(-1)} from t")) {
            assertThatThrownBy(statement::executeQuery)
                    .isInstanceOf(SQLFeatureNotSupportedException.class)
                    .hasMessage("JDBC escape sequences are not supported");
            assertThat(connection.executeCalls).isZero();

            statement.setEscapeProcessing(false);
            statement.executeQuery();

            assertThat(connection.executedSql)
                    .containsExactly("select {fn abs(-1)} from t");
        }
    }

    private static final class RecordingConnection extends LibsqlConnection {
        private final StmtResult result;
        private final List<String> executedSql = new ArrayList<>();
        private int executeCalls;

        private RecordingConnection(StmtResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            executeCalls++;
            executedSql.add(stmt.sql());
            return result;
        }
    }
}
