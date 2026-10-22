package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlResultSetTest {

    private static final StmtResult INTEGER_ROWS = new StmtResult(
            List.of(new Col("id", "INTEGER")),
            List.of(new Row(List.of(Value.of(42L))), new Row(List.of(Value.of(5_000_000_000L)))),
            0,
            null);

    @Test
    void getObjectReturnsIntegerInRangeAndLongOutside() throws SQLException {
        LibsqlResultSet resultSet = new LibsqlResultSet(new LibsqlStatement(null), INTEGER_ROWS);

        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getObject(1)).isInstanceOf(Integer.class).isEqualTo(42);
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getObject(1)).isInstanceOf(Long.class).isEqualTo(5_000_000_000L);
    }

    @Test
    void cachesResultSetMetadata() throws SQLException {
        LibsqlResultSet resultSet = new LibsqlResultSet(new LibsqlStatement(null), INTEGER_ROWS);

        assertThat(resultSet.getMetaData()).isSameAs(resultSet.getMetaData());
    }

    @Test
    void closeDoesNotCloseStatementByDefault() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);
        LibsqlResultSet resultSet = new LibsqlResultSet(statement, INTEGER_ROWS);

        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isFalse();
    }

    @Test
    void closeClosesStatementWhenRequested() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);
        LibsqlResultSet resultSet = new LibsqlResultSet(statement, INTEGER_ROWS, null, true);

        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isTrue();
    }

    @Test
    void closeClosesStatementWithCloseOnCompletion() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);
        statement.closeOnCompletion();
        LibsqlResultSet resultSet = new LibsqlResultSet(statement, INTEGER_ROWS);

        resultSet.close();
        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isTrue();
    }

    @Test
    void statementCloseClosesCurrentResultSet() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);
        LibsqlResultSet resultSet = new LibsqlResultSet(statement, INTEGER_ROWS);
        statement.currentResultSet = resultSet;

        statement.close();

        assertThat(resultSet.isClosed()).isTrue();
    }

    @Test
    void nonNumericTextCoercesToZero() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("abc"));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 0, 0L, 0.0f, 0.0);
    }

    @Test
    void numericTextCoercesToNumber() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("123"));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 123, 123L, 123.0f, 123.0);
    }

    @Test
    void realTextTruncatesForIntegralGettersAndKeepsFractionForFloatingGetters() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("1.9"));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 1, 1L, 1.9f, 1.9);
    }

    @Test
    void textWithExponentUsesIntegerPrefixForIntegralGetters() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("1.5e2"));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 1, 1L, 150.0f, 150.0);
    }

    @Test
    void textWithLeadingWhitespaceAndTrailingGarbageUsesNumericPrefix() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text(" 42xyz"));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 42, 42L, 42.0f, 42.0);
    }

    @Test
    void numericBlobIsInterpretedAsText() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Blob("123".getBytes(StandardCharsets.UTF_8)));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 123, 123L, 123.0f, 123.0);
    }

    @Test
    void realTextBlobIsInterpretedAsText() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Blob("1.9".getBytes(StandardCharsets.UTF_8)));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 1, 1L, 1.9f, 1.9);
    }

    @Test
    void nonNumericBlobCoercesToZero() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Blob("abc".getBytes(StandardCharsets.UTF_8)));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 0, 0L, 0.0f, 0.0);
    }

    @Test
    void realStorageTruncatesForIntegralGettersAndKeepsFractionForFloatingGetters() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Float(1.9));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 1, 1L, 1.9f, 1.9);
    }

    @Test
    void realStorageWrapsToIntThroughInt64LikeSqlite() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Float(3e9), new Value.Float(-3e9));

        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(-1294967296);
        assertThat(rs.getObject(1, Integer.class)).isEqualTo(-1294967296);
        assertThat(rs.getInt(2)).isEqualTo(1294967296);
    }

    @Test
    void getBooleanOnWrappingRealIsFalse() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Float(4294967296.0));
        assertThat(rs.next()).isTrue();
        assertThat(rs.getBoolean(1)).isFalse();
    }

    @Test
    void getFloatOnLargeIntegerRoundsThroughDouble() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Integer(1152921573326323713L));
        assertThat(rs.next()).isTrue();
        assertThat(rs.getFloat(1)).isEqualTo(1.1529215E18f);
    }

    @Test
    void integerStorageIsReturnedUnchanged() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Integer(42));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 42, 42L, 42.0f, 42.0);
    }

    @Test
    void getObjectNumericConversionsStayStrictForNonNumericText() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("abc"));
        assertThat(rs.next()).isTrue();

        assertNumeric(rs, 1, 0, 0L, 0.0f, 0.0);
        assertThatThrownBy(() -> rs.getObject(1, Double.class))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Bad value for type Double");
        assertThatThrownBy(() -> rs.getObject(1, Long.class))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Bad value for type Long");
        assertThatThrownBy(() -> rs.getObject(1, Float.class))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Bad value for type Float");
        assertThatThrownBy(() -> rs.getObject(1, Integer.class))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Bad value for type Integer");
    }

    @Test
    void getObjectNumericConversionsRejectNumericTextLikeXerial() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("123"));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getObject(1, Double.class))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type Double");
        assertThatThrownBy(() -> rs.getObject(1, Long.class))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type Long");
        assertThatThrownBy(() -> rs.getObject(1, Float.class))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type Float");
        assertThatThrownBy(() -> rs.getObject(1, Integer.class))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type Integer");
    }

    @Test
    void textDateTimeFeedsEveryDateAndTimeGetter() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("2024-01-02 10:00:00"));
        assertThat(rs.next()).isTrue();

        long epochMillis = LocalDateTime.of(2024, 1, 2, 10, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        assertDateTimeGetters(rs, epochMillis);
    }

    @Test
    void integerEpochMillisFeedsEveryDateAndTimeGetter() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Integer(1_700_000_000_000L));
        assertThat(rs.next()).isTrue();

        assertDateTimeGetters(rs, 1_700_000_000_000L);
    }

    @Test
    void realJulianDayFeedsEveryDateAndTimeGetter() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Float(2460311.5));
        assertThat(rs.next()).isTrue();

        assertDateTimeGetters(rs, 1_704_153_600_000L);
    }

    private static void assertDateTimeGetters(LibsqlResultSet rs, long epochMillis) throws SQLException {
        LocalDateTime expected = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());

        assertThat(rs.getDate(1)).isNotNull();
        assertThat(rs.getDate(1).toLocalDate()).isEqualTo(expected.toLocalDate());
        assertThat(rs.getTime(1)).isNotNull();
        assertThat(rs.getTime(1).toLocalTime()).isEqualTo(expected.toLocalTime());
        assertThat(rs.getTimestamp(1)).isNotNull();
        assertThat(rs.getTimestamp(1).getTime()).isEqualTo(epochMillis);
        assertThat(rs.getObject(1, LocalDate.class)).isEqualTo(expected.toLocalDate());
        assertThat(rs.getObject(1, LocalTime.class)).isEqualTo(expected.toLocalTime());
        assertThat(rs.getObject(1, LocalDateTime.class)).isEqualTo(expected);
    }

    private static void assertNumeric(LibsqlResultSet rs, int column, int expectedInt, long expectedLong,
            float expectedFloat, double expectedDouble) throws SQLException {
        assertThat(rs.getInt(column)).isEqualTo(expectedInt);
        assertThat(rs.getLong(column)).isEqualTo(expectedLong);
        assertThat(rs.getFloat(column)).isEqualTo(expectedFloat);
        assertThat(rs.getDouble(column)).isEqualTo(expectedDouble);
    }

    private static LibsqlResultSet resultSet(Value... values) {
        List<Col> cols = IntStream.range(0, values.length)
                .mapToObj(i -> new Col("c" + (i + 1), null))
                .toList();
        StmtResult result = new StmtResult(cols, List.of(new Row(List.of(values))), 1, null);
        return new LibsqlResultSet(null, result);
    }
}
