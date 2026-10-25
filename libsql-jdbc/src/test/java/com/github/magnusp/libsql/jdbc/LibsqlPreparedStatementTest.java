package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

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

    // The deprecated Unicode stream setter is part of the local-conversion contract.
    @SuppressWarnings("deprecation")
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

    @Test
    void integralTargetsBindHranaIntegers() throws SQLException {
        assertThat(bind("42", Types.TINYINT)).isEqualTo(Value.of(42L));
        assertThat(bind((short) 42, Types.SMALLINT)).isEqualTo(Value.of(42L));
        assertThat(bind(42L, Types.INTEGER)).isEqualTo(Value.of(42L));
        assertThat(bind(BigInteger.valueOf(42), Types.BIGINT)).isEqualTo(Value.of(42L));
        // Java narrowing applies to an integral target.
        assertThat(bind(258, Types.TINYINT)).isEqualTo(Value.of(2L));
    }

    @Test
    void realTargetsBindHranaFloats() throws SQLException {
        assertThat(bind("42", Types.REAL)).isEqualTo(Value.of(42.0));
        assertThat(bind(42L, Types.FLOAT)).isEqualTo(Value.of(42.0));
        assertThat(bind(42L, Types.DOUBLE)).isEqualTo(Value.of(42.0));
    }

    @Test
    void decimalTargetsBindDecimalText() throws SQLException {
        assertThat(bind(new BigDecimal("7"), Types.DECIMAL)).isEqualTo(Value.of("7"));
        assertThat(bind(7, Types.NUMERIC)).isEqualTo(Value.of("7"));
    }

    @Test
    void booleanTargetsBindZeroOrOneIntegers() throws SQLException {
        assertThat(bind(true, Types.BIT)).isEqualTo(Value.of(1L));
        assertThat(bind("false", Types.BOOLEAN)).isEqualTo(Value.of(0L));
        assertThat(bind(new BigDecimal("0.5"), Types.BOOLEAN)).isEqualTo(Value.of(1L));
    }

    @Test
    void textTargetsBindTheJavaTextForm() throws SQLException {
        assertThat(bind(7, Types.VARCHAR)).isEqualTo(Value.of("7"));
        assertThat(bind("x", Types.NCHAR)).isEqualTo(Value.of("x"));
        assertThat(bind("x", Types.LONGNVARCHAR)).isEqualTo(Value.of("x"));
        // The documented Boolean text format is "true" / "false", not "TRUE" / "FALSE".
        assertThat(bind(true, Types.CHAR)).isEqualTo(Value.of("true"));
        assertThat(bind(false, Types.VARCHAR)).isEqualTo(Value.of("false"));
    }

    @Test
    void binaryTargetsBindHranaBlobs() throws SQLException {
        byte[] bytes = {1, 2, 3};
        assertThat(bind(bytes, Types.VARBINARY)).isEqualTo(Value.of(bytes));
        assertThat(bind("abc", Types.BINARY))
                .isEqualTo(Value.of("abc".getBytes(StandardCharsets.UTF_8)));
        assertThat(bind(new LibsqlBlob(bytes), Types.BLOB)).isEqualTo(Value.of(bytes));
    }

    @Test
    void temporalTargetsBindSqliteText() throws SQLException {
        Calendar calendar = calendar2024();
        java.util.Date utilDate = new java.util.Date(calendar.getTimeInMillis());
        String expectedTimestamp = timestampText(LocalDateTime.ofInstant(
                calendar.toInstant(), ZoneId.systemDefault()));

        assertThat(bind(Date.valueOf("2024-01-02"), Types.DATE))
                .isEqualTo(Value.of("2024-01-02"));
        assertThat(bind(Time.valueOf("03:04:05"), Types.TIME))
                .isEqualTo(Value.of("03:04:05"));
        assertThat(bind(Timestamp.valueOf("2024-01-02 03:04:05"), Types.TIMESTAMP))
                .isEqualTo(Value.of("2024-01-02 03:04:05"));
        assertThat(bind(Timestamp.valueOf("2024-01-02 03:04:05"), Types.DATE))
                .isEqualTo(Value.of("2024-01-02"));
        assertThat(bind(LocalDateTime.of(2024, 1, 2, 3, 4, 5), Types.TIME))
                .isEqualTo(Value.of("03:04:05"));
        assertThat(bind(calendar, Types.TIMESTAMP)).isEqualTo(Value.of(expectedTimestamp));
        assertThat(bind(utilDate, Types.TIMESTAMP)).isEqualTo(Value.of(expectedTimestamp));
    }

    @Test
    void lobTargetsReadTheirStreams() throws SQLException {
        byte[] bytes = {1, 2, 3};
        assertThat(bind(new LibsqlClob("abc"), Types.CLOB)).isEqualTo(Value.of("abc"));
        assertThat(bind(new LibsqlBlob(bytes), Types.BLOB)).isEqualTo(Value.of(bytes));
        assertThat(bind(nclob("nclob"), Types.NCLOB)).isEqualTo(Value.of("nclob"));
    }

    @Test
    void unlistedTargetPairThrowsFeatureNotSupported() {
        assertThatThrownBy(() -> bind("42", Types.ARRAY))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessageContaining("ARRAY");
        assertThatThrownBy(() -> bind(7, Types.BLOB))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessageContaining("BLOB");
    }

    @Test
    void setObjectWithoutATargetFollowsTableB4() throws SQLException {
        assertThat(bind(BigInteger.valueOf(7))).isEqualTo(Value.of(7L));
        assertThat(bind((byte) 7)).isEqualTo(Value.of(7L));
        assertThat(bind((short) 7)).isEqualTo(Value.of(7L));
        assertThat(bind(true)).isEqualTo(Value.of(1L));
        assertThat(bind(LocalDate.of(2024, 1, 2))).isEqualTo(Value.of("2024-01-02"));
        assertThat(bind(LocalDateTime.of(2024, 1, 2, 3, 4, 5)))
                .isEqualTo(Value.of("2024-01-02 03:04:05"));
    }

    @Test
    void rowIdAndSqlXmlParametersBindTheirContent() throws SQLException {
        byte[] bytes = {1, 2, 3};
        assertThat(bindParameter(statement -> statement.setRowId(1, new LibsqlRowId(bytes))))
                .isEqualTo(Value.of(bytes));
        assertThat(bindParameter(statement -> statement.setRowId(1, null)))
                .isEqualTo(Value.ofNull());
        assertThat(bindParameter(statement -> statement.setSQLXML(1, new LibsqlSQLXML("<a/>"))))
                .isEqualTo(Value.of("<a/>"));
        assertThat(bindParameter(statement -> statement.setSQLXML(1, null)))
                .isEqualTo(Value.ofNull());
    }

    @Test
    void rowIdBoundAsBlobReadsBackTheSameBytes() throws SQLException {
        byte[] bytes = "rowid-1".getBytes(StandardCharsets.UTF_8);
        Value bound = bindParameter(statement -> statement.setRowId(1, new LibsqlRowId(bytes)));

        LibsqlResultSet rows = new LibsqlResultSet(
                new LibsqlStatement(null),
                new StmtResult(
                        List.of(new Col("c", "ROWID")), List.of(new Row(List.of(bound))), 0, null));

        assertThat(rows.next()).isTrue();
        assertThat(rows.getRowId(1).getBytes()).isEqualTo(bytes);
    }

    private static Value bind(Object value) throws SQLException {
        return bindParameter(statement -> statement.setObject(1, value));
    }

    private static Value bind(Object value, int targetSqlType) throws SQLException {
        return bindParameter(statement -> statement.setObject(1, value, targetSqlType));
    }

    @FunctionalInterface
    private interface ParameterBinder {
        void bind(LibsqlPreparedStatement statement) throws SQLException;
    }

    private static Value bindParameter(ParameterBinder binder) throws SQLException {
        RecordingConnection connection = new RecordingConnection(NO_ROWS);
        try (LibsqlPreparedStatement statement =
                new LibsqlPreparedStatement(connection, "insert into t values (?)")) {
            binder.bind(statement);
            statement.executeUpdate();
        }
        return connection.executed.get(0).args().get(0);
    }

    private static Calendar calendar2024() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(2024, Calendar.JANUARY, 2, 3, 4, 5);
        return calendar;
    }

    private static String timestampText(LocalDateTime value) {
        return value.toLocalDate() + " " + String.format(
                "%02d:%02d:%02d",
                value.getHour(), value.getMinute(), value.getSecond());
    }

    private static NClob nclob(String text) {
        return (NClob) java.lang.reflect.Proxy.newProxyInstance(
                NClob.class.getClassLoader(),
                new Class<?>[] {NClob.class},
                (proxy, method, args) -> method.getName().equals("getCharacterStream")
                        ? new StringReader(text)
                        : null);
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
