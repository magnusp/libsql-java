package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
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
    void withInsertReturningIsAQuery() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        try (LibsqlPreparedStatement statement = new LibsqlPreparedStatement(
                connection,
                "with cte as (select 1 as v) insert into t (v) select v from cte returning id")) {
            ResultSet resultSet = statement.executeQuery();

            assertThat(resultSet.next()).isTrue();
        }

        assertThat(connection.executeCalls).isEqualTo(1);
    }

    @Test
    void withInsertWithoutReturningIsAnUpdate() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement = new LibsqlPreparedStatement(
                connection, "with cte as (select 1 as v) insert into t (v) select v from cte")) {
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        assertThat(connection.executeCalls).isEqualTo(1);
    }

    @Test
    void pragmaWithoutAssignmentIsAQuery() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "pragma table_info('t')")) {
            assertThatThrownBy(statement::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("Query returns results");

            ResultSet resultSet = statement.executeQuery();
            assertThat(resultSet.next()).isTrue();
        }

        assertThat(connection.executeCalls).isEqualTo(1);
    }

    @Test
    void pragmaAssignmentIsAnUpdate() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "pragma journal_mode = wal")) {
            assertThatThrownBy(statement::executeQuery)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("Query does not return results");

            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        assertThat(connection.executeCalls).isEqualTo(1);
    }

    @Test
    void lineCommentBeforeSelectKeepsTheQueryKind() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "-- insert\nselect id from t")) {
            ResultSet resultSet = statement.executeQuery();

            assertThat(resultSet.next()).isTrue();
        }

        assertThat(connection.executeCalls).isEqualTo(1);
    }

    @Test
    void blockCommentBeforeUpdateKeepsTheUpdateKind() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "/* select */ update t set a = 1")) {
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        assertThat(connection.executeCalls).isEqualTo(1);
    }

    @Test
    void quotedIdentifierNamedLikeAKeywordDoesNotDecideTheStatementKind() throws SQLException {
        RecordingConnection connection = new RecordingConnection(ROWS);
        try (LibsqlPreparedStatement statement = new LibsqlPreparedStatement(
                connection, "with \"update\" as (select id from t) select id from \"update\"")) {
            ResultSet resultSet = statement.executeQuery();

            assertThat(resultSet.next()).isTrue();
        }

        assertThat(connection.executeCalls).isEqualTo(1);
    }

    @Test
    void malformedSqlIsTreatedAsAnUpdate() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select (1")) {
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        assertThat(connection.executeCalls).isEqualTo(1);
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

    @Test
    void parameterCountComesFromThePlaceholders() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select ?, ?, ?")) {
            assertThat(statement.getParameterMetaData().getParameterCount()).isEqualTo(3);
        }
        try (LibsqlPreparedStatement statement = new LibsqlPreparedStatement(
                connection, "select '?', \"?\", ? -- trailing ?\n")) {
            assertThat(statement.getParameterMetaData().getParameterCount()).isEqualTo(1);
        }
    }

    @Test
    void parameterTypesAreUnknownWithoutADescription() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "insert into t values (?)")) {
            ParameterMetaData meta = statement.getParameterMetaData();

            assertThat(meta.getParameterType(1)).isEqualTo(Types.OTHER);
            assertThat(meta.getParameterTypeName(1)).isEqualTo("OTHER");
            assertThat(meta.getParameterMode(1)).isEqualTo(ParameterMetaData.parameterModeIn);
        }
    }

    @Test
    void getParameterMetaDataReturnsAViewWithThePlaceholderCount() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement = new LibsqlPreparedStatement(
                connection, "select id from t where id = ? and name = ?")) {
            ParameterMetaData meta = statement.getParameterMetaData();

            assertThat(meta).isNotNull();
            assertThat(meta.getParameterCount()).isEqualTo(2);
        }
    }

    @Test
    void getParameterMetaDataAfterCloseThrows() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select ?");
        statement.close();

        assertThatThrownBy(statement::getParameterMetaData)
                .isInstanceOf(SQLException.class)
                .hasMessage("Statement is closed");
    }

    @Test
    void parameterMetadataViewTakenBeforeCloseStillThrowsAfterClose() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select ?");
        ParameterMetaData meta = statement.getParameterMetaData();
        statement.close();

        assertThatThrownBy(meta::getParameterCount)
                .isInstanceOf(SQLException.class)
                .hasMessage("Statement is closed");
        assertThatThrownBy(() -> meta.getParameterType(1))
                .isInstanceOf(SQLException.class)
                .hasMessage("Statement is closed");
    }

    @Test
    void parameterMetadataRejectsAnOutOfRangeIndex() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select ?")) {
            ParameterMetaData meta = statement.getParameterMetaData();

            assertThatThrownBy(() -> meta.getParameterType(0))
                    .isInstanceOf(SQLException.class)
                    .hasMessage("Parameter index out of range: 0");
        }
    }

    @Test
    void preparedStatementRemainsAParameterMetaData() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select ?")) {
            assertThat(statement).isInstanceOf(ParameterMetaData.class);
        }
    }

    @Test
    void getParameterMetaDataReturnsTheSameCachedView() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select ?")) {
            ParameterMetaData first = statement.getParameterMetaData();
            ParameterMetaData second = statement.getParameterMetaData();

            assertThat(first).isSameAs(second);
        }
    }

    @Test
    void parameterMetadataUnwrapReachesTheStatement() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "select ?")) {
            ParameterMetaData meta = statement.getParameterMetaData();

            assertThat(meta.isWrapperFor(ParameterMetaData.class)).isTrue();
            assertThat(meta.unwrap(ParameterMetaData.class)).isSameAs(statement);
        }
    }

    @Test
    void clearParametersBindsNullArguments() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "insert into t values (?)")) {
            statement.setString(1, "value");
            statement.clearParameters();

            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        assertThat(connection.executed.get(0).args()).containsExactly(Value.ofNull());
    }

    @Test
    void unsetParametersBindNullArguments() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "insert into t values (?, ?)")) {
            statement.setInt(1, 7);

            statement.executeUpdate();
        }

        assertThat(connection.executed.get(0).args())
                .containsExactly(Value.of(7L), Value.ofNull());
    }

    @Test
    void streamSettersBindLocalConversions() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        byte[] bytes = {1, 2, 3};
        String supplementary = "\uD840\uDC40 stream";
        try (LibsqlPreparedStatement statement = new LibsqlPreparedStatement(
                connection, "insert into t values (?, ?, ?, ?, ?)")) {
            statement.setClob(1, new StringReader("clob"));
            statement.setBlob(2, new ByteArrayInputStream(bytes));
            statement.setBinaryStream(3, new ByteArrayInputStream(bytes), bytes.length);
            statement.setAsciiStream(
                    4,
                    new ByteArrayInputStream("ascii".getBytes(StandardCharsets.US_ASCII)),
                    "ascii".length());
            statement.setUnicodeStream(
                    5,
                    new ByteArrayInputStream(supplementary.getBytes(StandardCharsets.UTF_8)),
                    supplementary.getBytes(StandardCharsets.UTF_8).length);

            statement.executeUpdate();
        }

        assertThat(connection.executed.get(0).args())
                .containsExactly(
                        Value.of("clob"),
                        Value.of(bytes),
                        Value.of(bytes),
                        Value.of("ascii"),
                        Value.of(supplementary));
    }

    @Test
    void streamSettersWithoutALengthReadToEndOfStream() throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        byte[] bytes = {4, 5, 6};
        try (LibsqlPreparedStatement statement = new LibsqlPreparedStatement(
                connection, "insert into t values (?, ?, ?, ?)")) {
            statement.setClob(1, new StringReader("clob"));
            statement.setBlob(2, new ByteArrayInputStream(bytes));
            statement.setBinaryStream(3, new ByteArrayInputStream(bytes));
            statement.setAsciiStream(
                    4, new ByteArrayInputStream("ascii".getBytes(StandardCharsets.US_ASCII)));

            statement.executeUpdate();
        }

        assertThat(connection.executed.get(0).args())
                .containsExactly(Value.of("clob"), Value.of(bytes), Value.of(bytes),
                        Value.of("ascii"));
    }

    private static final class RecordingConnection extends LibsqlConnection {
        private final StmtResult result;
        private final List<String> executedSql = new ArrayList<>();
        private final List<Stmt> executed = new ArrayList<>();
        private int executeCalls;

        private RecordingConnection(StmtResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            executeCalls++;
            executedSql.add(stmt.sql());
            executed.add(stmt);
            return result;
        }
    }
}
