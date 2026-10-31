package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.Row;
import io.github.magnusp.libsql.hrana.codec.Stmt;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlStatementTest {

    private static final StmtResult THREE_ROWS = new StmtResult(
            List.of(new Col("value", null)),
            List.of(
                    new Row(List.of(Value.of(1L))),
                    new Row(List.of(Value.of(2L))),
                    new Row(List.of(Value.of(3L)))),
            0,
            null);

    @Test
    void setMaxRowsTruncatesResultRowsAndGetMaxRowsReadsItBack() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        statement.setMaxRows(2);

        assertThat(statement.getMaxRows()).isEqualTo(2);
        try (ResultSet resultSet = statement.executeQuery("select value from t")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(1L);
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(2L);
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void setMaxRowsZeroRemovesTheLimit() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));
        statement.setMaxRows(1);

        statement.setMaxRows(0);

        assertThat(statement.getMaxRows()).isZero();
        int rows = 0;
        try (ResultSet resultSet = statement.executeQuery("select value from t")) {
            while (resultSet.next()) {
                rows++;
            }
        }
        assertThat(rows).isEqualTo(3);
    }

    @Test
    void setMaxRowsRejectsANegativeLimit() {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        assertThatThrownBy(() -> statement.setMaxRows(-1))
                .isInstanceOf(SQLException.class)
                .hasMessage("max row count must be >= 0");
    }

    @Test
    void setQueryTimeoutAcceptsNoLimitAndRejectsAPositiveTimeout() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        statement.setQueryTimeout(0);

        assertThat(statement.getQueryTimeout()).isZero();
        assertThatThrownBy(() -> statement.setQueryTimeout(1))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("Query timeouts are not supported");
        assertThatThrownBy(() -> statement.setQueryTimeout(-1))
                .isInstanceOf(SQLException.class)
                .hasMessage("query timeout must be >= 0");
    }

    @Test
    void setMaxFieldSizeAcceptsNoLimitAndRejectsAPositiveLimit() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        statement.setMaxFieldSize(0);

        assertThat(statement.getMaxFieldSize()).isZero();
        assertThatThrownBy(() -> statement.setMaxFieldSize(8))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("Column size limits are not supported");
        assertThatThrownBy(() -> statement.setMaxFieldSize(-1))
                .isInstanceOf(SQLException.class)
                .hasMessage("max field size must be >= 0");
    }

    @Test
    void escapeProcessingStartsEnabledAndRejectsEscapeSequences() {
        RecordingConnection connection = new RecordingConnection(THREE_ROWS);
        LibsqlStatement statement = new LibsqlStatement(connection);

        assertThatThrownBy(() -> statement.executeQuery("select {fn abs(-1)}"))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("JDBC escape sequences are not supported");
        assertThat(connection.executedSql).isEmpty();
    }

    @Test
    void setEscapeProcessingDisabledSendsEscapeLookingSqlVerbatim() throws SQLException {
        RecordingConnection connection = new RecordingConnection(THREE_ROWS);
        LibsqlStatement statement = new LibsqlStatement(connection);

        statement.setEscapeProcessing(false);
        statement.executeQuery("select '{fn abs(-1)}' as text");

        assertThat(connection.executedSql)
                .containsExactly("select '{fn abs(-1)}' as text");
    }

    @Test
    void escapeProcessingIgnoresBracesInsideStringLiteralsAndComments() throws SQLException {
        RecordingConnection connection = new RecordingConnection(THREE_ROWS);
        LibsqlStatement statement = new LibsqlStatement(connection);

        statement.executeQuery("select '{\"a\": 1}' as doc -- {fn abs(-1)}");

        assertThat(connection.executedSql).hasSize(1);
    }

    @Test
    void setCursorNameIsNotSupported() {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        assertThatThrownBy(() -> statement.setCursorName("c1"))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("Cursor names are not supported");
    }

    @Test
    void setFetchDirectionAcceptsTheJdbcDirections() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        statement.setFetchDirection(ResultSet.FETCH_REVERSE);
        statement.setFetchDirection(ResultSet.FETCH_UNKNOWN);
    }

    @Test
    void setFetchDirectionRejectsAnUnknownDirection() {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        assertThatThrownBy(() -> statement.setFetchDirection(999))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("999");
    }

    @Test
    void batchThatReturnsResultsThrowsBatchUpdateException() throws SQLException {
        StmtResult select = new StmtResult(
                List.of(new Col("value", null)),
                List.of(new Row(List.of(Value.ofNull()))),
                0,
                null);
        StmtResult insert = new StmtResult(List.of(), List.of(), 1, 1L);
        LibsqlStatement statement =
                new LibsqlStatement(new RecordingConnection(THREE_ROWS, List.of(insert, select)));

        statement.addBatch("insert into t(value) values (1)");
        statement.addBatch("select null");

        assertThatThrownBy(statement::executeBatch)
                .isInstanceOf(BatchUpdateException.class)
                .hasMessageContaining("query returns results")
                .satisfies(thrown -> assertThat(((BatchUpdateException) thrown).getUpdateCounts())
                        .containsExactly(1, 0));
    }

    @Test
    void plainStatementBindsNullForUnboundPlaceholders() throws SQLException {
        RecordingConnection connection = new RecordingConnection(THREE_ROWS);
        LibsqlStatement statement = new LibsqlStatement(connection);

        statement.execute("insert into t values (?)");

        assertThat(connection.executedStatements.get(0).args()).containsExactly(Value.ofNull());
    }

    @Test
    void multiStatementUpdateSumsAffectedRowsAndSendsEachStatementInOrder() throws SQLException {
        ScriptedConnection connection = new ScriptedConnection(
                List.of(updateResult(1), updateResult(1)));
        LibsqlStatement statement = new LibsqlStatement(connection);

        assertThat(statement.executeUpdate(
                        "insert into t values (1); insert into t values (2)"))
                .isEqualTo(2);

        assertThat(connection.executedStatements)
                .containsExactly("insert into t values (1)", "insert into t values (2)");
        assertThat(statement.getUpdateCount()).isEqualTo(2);
    }

    @Test
    void multiStatementExecuteExposesTheFinalResultSet() throws SQLException {
        StmtResult select = new StmtResult(
                List.of(new Col("value", null)),
                List.of(new Row(List.of(Value.of(2L)))),
                0,
                null);
        ScriptedConnection connection = new ScriptedConnection(
                List.of(updateResult(1), select));
        LibsqlStatement statement = new LibsqlStatement(connection);

        assertThat(statement.execute("insert into t values (1); select 2")).isTrue();

        try (ResultSet resultSet = statement.getResultSet()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(2L);
        }
    }

    @Test
    void multiStatementStopsAtTheFirstFailureAndRunsNoLaterStatement() {
        ScriptedConnection connection = new ScriptedConnection(List.of(updateResult(1)));
        connection.failOnStatement = 2;
        LibsqlStatement statement = new LibsqlStatement(connection);

        assertThatThrownBy(() -> statement.execute(
                        "insert into t values (1); insert into t values (2); insert into t values (3)"))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .hasMessageContaining("constraint failed");

        assertThat(connection.executedStatements)
                .containsExactly("insert into t values (1)", "insert into t values (2)");
    }

    @Test
    void multiStatementWithPlaceholdersIsRejectedBeforeAnythingRuns() {
        ScriptedConnection connection = new ScriptedConnection(List.of(updateResult(1)));
        LibsqlStatement statement = new LibsqlStatement(connection);

        assertThatThrownBy(() -> statement.execute(
                        "insert into t values (?); insert into t values (2)"))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessageContaining("multi-statement");

        assertThat(connection.executedStatements).isEmpty();
    }

    @Test
    void preparedMultiStatementWithBoundArgumentsIsRejectedBeforeAnythingRuns() throws SQLException {
        ScriptedConnection connection = new ScriptedConnection(List.of(updateResult(1)));
        LibsqlPreparedStatement statement = new LibsqlPreparedStatement(
                connection, "insert into t values (1); insert into t values (2)");
        statement.setInt(1, 1);

        assertThatThrownBy(statement::executeUpdate)
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessageContaining("multi-statement");

        assertThat(connection.executedStatements).isEmpty();
    }

    private static StmtResult updateResult(long affectedRows) {
        return new StmtResult(List.of(), List.of(), affectedRows, null);
    }

    private static final class ScriptedConnection extends LibsqlConnection {
        private final List<StmtResult> results;
        private final List<String> executedStatements = new ArrayList<>();
        private int failOnStatement = -1;

        private ScriptedConnection(List<StmtResult> results) {
            super(null, null);
            this.results = results;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) throws SQLException {
            executedStatements.add(stmt.sql());
            if (executedStatements.size() == failOnStatement) {
                throw new SQLIntegrityConstraintViolationException("constraint failed");
            }
            return results.get(executedStatements.size() - 1);
        }
    }

    private static final class RecordingConnection extends LibsqlConnection {
        private final StmtResult result;
        private final List<StmtResult> batchResults;
        private final List<String> executedSql = new ArrayList<>();
        private final List<Stmt> executedStatements = new ArrayList<>();

        private RecordingConnection(StmtResult result) {
            this(result, List.of());
        }

        private RecordingConnection(StmtResult result, List<StmtResult> batchResults) {
            super(null, null);
            this.result = result;
            this.batchResults = batchResults;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            executedSql.add(stmt.sql());
            executedStatements.add(stmt);
            return result;
        }

        @Override
        public List<StmtResult> executeBatchResults(List<Stmt> statements) {
            return batchResults;
        }
    }
}
