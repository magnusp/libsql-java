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
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlStatementLifecycleTest {

    private static final StmtResult ROWS = new StmtResult(
            List.of(new Col("value", null)),
            List.of(new Row(List.of(Value.of(1L)))),
            0,
            null);

    @Test
    void getResultSetAfterExecuteQueryThrowsResultSetAlreadyRequested() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);

        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("select value from t");

            assertThatThrownBy(statement::getResultSet)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("ResultSet already requested");
        }
    }

    @Test
    void preparedGetResultSetAfterExecuteQueryThrowsResultSetAlreadyRequested()
            throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select value from t");

        try (statement) {
            statement.executeQuery();

            assertThatThrownBy(statement::getResultSet)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("ResultSet already requested");
        }
    }

    @Test
    void getResultSetAfterExecuteReturnsTheResultOnceThenThrows() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);

        try (Statement statement = connection.createStatement()) {
            assertThat(statement.execute("select value from t")).isTrue();

            ResultSet resultSet = statement.getResultSet();
            assertThat(resultSet).isNotNull();
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(1L);

            assertThatThrownBy(statement::getResultSet)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("ResultSet already requested");
        }
    }

    @Test
    void getMoreResultsRejectsKeepCurrentResult() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);

        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("select value from t");

            assertThatThrownBy(() -> statement.getMoreResults(Statement.KEEP_CURRENT_RESULT))
                    .isInstanceOf(SQLFeatureNotSupportedException.class)
                    .hasMessageContaining("KEEP_CURRENT_RESULT");
        }
    }

    @Test
    void getMoreResultsRejectsCloseAllResults() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);

        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("select value from t");

            assertThatThrownBy(() -> statement.getMoreResults(Statement.CLOSE_ALL_RESULTS))
                    .isInstanceOf(SQLFeatureNotSupportedException.class)
                    .hasMessageContaining("CLOSE_ALL_RESULTS");
        }
    }

    @Test
    void getMoreResultsAcceptsCloseCurrentResult() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);

        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("select value from t");

            assertThat(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).isFalse();
            assertThat(statement.getResultSet()).isNull();
        }
    }

    @Test
    void closeOnCompletionAndIsCloseOnCompletionThrowAfterClose() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);
        statement.closeOnCompletion();
        assertThat(statement.isCloseOnCompletion()).isTrue();

        statement.close();

        assertThatThrownBy(statement::closeOnCompletion)
                .isInstanceOf(SQLException.class)
                .hasMessage("statement is closed");
        assertThatThrownBy(statement::isCloseOnCompletion)
                .isInstanceOf(SQLException.class)
                .hasMessage("statement is closed");
    }

    @Test
    void closingQueryResultSetClosesCloseOnCompletionStatement() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        Statement statement = connection.createStatement();
        statement.closeOnCompletion();

        ResultSet resultSet = statement.executeQuery("select value from t");
        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isTrue();
    }

    @Test
    void closingExecuteResultSetClosesCloseOnCompletionStatement() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        Statement statement = connection.createStatement();
        statement.closeOnCompletion();

        assertThat(statement.execute("select value from t")).isTrue();
        ResultSet resultSet = statement.getResultSet();
        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isTrue();
    }

    @Test
    void closingPreparedResultSetClosesCloseOnCompletionStatement() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select value from t");
        statement.closeOnCompletion();

        ResultSet resultSet = statement.executeQuery();
        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isTrue();
    }

    @Test
    void closingResultSetLeavesStatementWithoutCloseOnCompletionOpen() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        Statement statement = connection.createStatement();

        ResultSet resultSet = statement.executeQuery("select value from t");
        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isFalse();
    }

    @Test
    void metadataStatementClosesWithoutCloseOnCompletion() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);

        ResultSet resultSet = connection.getMetaData().getTableTypes();
        LibsqlStatement statement = (LibsqlStatement) resultSet.getStatement();
        assertThat(statement.isCloseOnCompletion()).isFalse();

        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isTrue();
    }

    @Test
    void executeAndGetConnectionStillWorkAfterClose() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        Statement statement = connection.createStatement();
        statement.close();

        assertThat(statement.isClosed()).isTrue();
        assertThat(statement.execute("select value from t")).isTrue();
        assertThat(statement.getConnection()).isSameAs(connection);
        assertThat(connection.executeCalls).isEqualTo(1);

        statement.close();
    }

    @Test
    void preparedStatementExecuteAfterCloseThrows() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select value from t");
        statement.close();

        assertThatThrownBy(statement::execute)
                .isInstanceOf(SQLException.class)
                .hasMessage("Statement is closed");
        assertThat(connection.executeCalls).isZero();
    }

    private static final class RecordingConnection extends LibsqlConnection {
        private final StmtResult result;
        private int executeCalls;

        private RecordingConnection(StmtResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            executeCalls++;
            return result;
        }
    }
}
