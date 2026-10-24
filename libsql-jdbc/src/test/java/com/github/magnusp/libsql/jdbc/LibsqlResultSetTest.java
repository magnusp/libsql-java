package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

    @ParameterizedTest
    @ValueSource(strings = {" ", "\t", "\n", "\r", "\u000b", "\f"})
    void everySqliteWhitespaceCharacterIsSkippedBeforeTheNumericPrefix(String whitespace) throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text(whitespace + "42xyz"));
        assertThat(rs.next()).isTrue();
        assertNumeric(rs, 1, 42, 42L, 42.0f, 42.0);
    }

    @Test
    void largeIntegerTextBelowLongMaxIsReturnedUnchanged() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("6000000000000000000"));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getLong(1)).isEqualTo(6_000_000_000_000_000_000L);
        assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("6000000000000000000"));
    }

    @Test
    void textJustPastLongMaxSaturatesToLongMax() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("9223372036854775808"));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getLong(1)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void textJustPastLongMinSaturatesToLongMin() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("-9223372036854775809"));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getLong(1)).isEqualTo(Long.MIN_VALUE);
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
    void emptyTextDateAndTimeGettersReturnNull() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text(""));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getDate(1)).isNull();
        assertThat(rs.getTime(1)).isNull();
        assertThat(rs.getTimestamp(1)).isNull();
        assertThat(rs.getObject(1, LocalDate.class)).isNull();
        assertThat(rs.getObject(1, LocalTime.class)).isNull();
        assertThat(rs.getObject(1, LocalDateTime.class)).isNull();
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

    @Test
    void textDateTimeWithAnOffsetSuffixIsConvertedToTheSystemZone() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("2024-03-15 10:30:00+02:00"));
        assertThat(rs.next()).isTrue();

        LocalDateTime expected = LocalDateTime.of(2024, 3, 15, 10, 30)
                .atOffset(ZoneOffset.ofHours(2))
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();

        assertThat(rs.getObject(1, LocalDateTime.class)).isEqualTo(expected);
        assertThat(rs.getObject(1, LocalDate.class)).isEqualTo(expected.toLocalDate());
        assertThat(rs.getObject(1, LocalTime.class)).isEqualTo(expected.toLocalTime());
    }

    @Test
    void textDateTimeWithAZSuffixIsReadAsUtc() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("2024-03-15T10:30:00Z"));
        assertThat(rs.next()).isTrue();

        LocalDateTime expected = LocalDateTime.of(2024, 3, 15, 10, 30)
                .atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();

        assertThat(rs.getObject(1, LocalDateTime.class)).isEqualTo(expected);
        assertThat(rs.getObject(1, LocalDate.class)).isEqualTo(expected.toLocalDate());
        assertThat(rs.getObject(1, LocalTime.class)).isEqualTo(expected.toLocalTime());
    }

    @Test
    void bareTimeTextGetsTheYear2000DefaultDate() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("10:30:45"));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getObject(1, LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2000, 1, 1, 10, 30, 45));
        assertThat(rs.getObject(1, LocalDate.class)).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(rs.getObject(1, LocalTime.class)).isEqualTo(LocalTime.of(10, 30, 45));
    }

    @Test
    void blobRejectsDateAndTimeGettersWithTheTargetTypeName() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Blob(new byte[] {1, 2, 3}));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getObject(1, LocalDate.class))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type LocalDate");
        assertThatThrownBy(() -> rs.getObject(1, LocalTime.class))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type LocalTime");
        assertThatThrownBy(() -> rs.getObject(1, LocalDateTime.class))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type LocalDateTime");
    }

    @Test
    void binaryStreamContentMatchesGetBytesByteForByte() throws Exception {
        byte[] payload = {0, 1, 2, 65, 127, -128, -1};
        LibsqlResultSet rs = resultSet(new Value.Blob(payload));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getBytes(1)).isEqualTo(payload);
        assertThat(rs.getBinaryStream(1).readAllBytes()).isEqualTo(payload);
        assertThat(rs.getBinaryStream("c1").readAllBytes()).isEqualTo(payload);
    }

    @Test
    void characterStreamContentMatchesGetStringCharacterForCharacter() throws Exception {
        String text = "h\u00e9llo \u20ac \ud83d\ude00";
        LibsqlResultSet rs = resultSet(new Value.Text(text));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getString(1)).isEqualTo(text);
        assertThat(read(rs.getCharacterStream(1))).isEqualTo(text);
        assertThat(read(rs.getCharacterStream("c1"))).isEqualTo(text);
    }

    @Test
    void streamGettersReturnNullForSqlNull() throws Exception {
        LibsqlResultSet rs = resultSet(Value.ofNull());
        assertThat(rs.next()).isTrue();

        assertThat(rs.getBinaryStream(1)).isNull();
        assertThat(rs.getCharacterStream(1)).isNull();
        assertThat(rs.getBlob(1)).isNull();
        assertThat(rs.getClob(1)).isNull();
    }

    @Test
    void blobAndClobExposeTheColumnValue() throws Exception {
        byte[] payload = {9, 8, 7, 0, -1};
        String text = "clob text \u20ac";
        LibsqlResultSet rs = resultSet(new Value.Blob(payload), new Value.Text(text));
        assertThat(rs.next()).isTrue();

        Blob blob = rs.getBlob(1);
        assertThat(blob.length()).isEqualTo(payload.length);
        assertThat(blob.getBytes(1, payload.length)).isEqualTo(payload);
        assertThat(blob.getBinaryStream().readAllBytes()).isEqualTo(payload);
        assertThat(blob.position(payload, 1)).isEqualTo(1);
        assertThat(rs.getBlob("c1").getBytes(1, payload.length)).isEqualTo(payload);

        Clob clob = rs.getClob(2);
        assertThat(clob.length()).isEqualTo(text.length());
        assertThat(clob.getSubString(1, text.length())).isEqualTo(text);
        assertThat(read(clob.getCharacterStream())).isEqualTo(text);
        assertThat(read(rs.getClob("c2").getCharacterStream())).isEqualTo(text);

        assertThatThrownBy(() -> blob.setBytes(1, new byte[] {1}))
                .isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(() -> clob.setString(1, "x"))
                .isInstanceOf(SQLFeatureNotSupportedException.class);
    }

    @Test
    void getObjectWithMapRejectsNonEmptyMap() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Integer(42));
        assertThat(rs.next()).isTrue();
        Map<String, Class<?>> typeMap = Map.of("x", String.class);

        assertThat(rs.getObject(1, Map.of())).isEqualTo(42);
        assertThat(rs.getObject("c1", Map.of())).isEqualTo(42);
        assertThatThrownBy(() -> rs.getObject(1, typeMap))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessageContaining("Type maps");
        assertThatThrownBy(() -> rs.getObject("c1", typeMap))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessageContaining("Type maps");
    }

    @Test
    void findColumnRejectsNullLabel() {
        LibsqlResultSet rs = resultSet(new Value.Integer(1));

        assertThatThrownBy(() -> rs.findColumn(null))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessageContaining("null");
    }

    @Test
    void everyUnsupportedMethodThrowsSqlFeatureNotSupportedException() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Integer(1));
        assertThat(rs.next()).isTrue();
        Ref ref = null;
        Array array = null;
        RowId rowId = null;
        Blob blob = null;
        Clob clob = null;
        NClob nClob = null;
        SQLXML sqlxml = null;
        InputStream stream = null;
        Reader reader = null;

        List<UnsupportedCall> calls = List.of(
                new UnsupportedCall("getAsciiStream(int)", () -> rs.getAsciiStream(1)),
                new UnsupportedCall("getAsciiStream(String)", () -> rs.getAsciiStream("c1")),
                new UnsupportedCall("getUnicodeStream(int)", () -> rs.getUnicodeStream(1)),
                new UnsupportedCall("getUnicodeStream(String)", () -> rs.getUnicodeStream("c1")),
                new UnsupportedCall("getRef(int)", () -> rs.getRef(1)),
                new UnsupportedCall("getRef(String)", () -> rs.getRef("c1")),
                new UnsupportedCall("getArray(int)", () -> rs.getArray(1)),
                new UnsupportedCall("getArray(String)", () -> rs.getArray("c1")),
                new UnsupportedCall("getURL(int)", () -> rs.getURL(1)),
                new UnsupportedCall("getURL(String)", () -> rs.getURL("c1")),
                new UnsupportedCall("getRowId(int)", () -> rs.getRowId(1)),
                new UnsupportedCall("getRowId(String)", () -> rs.getRowId("c1")),
                new UnsupportedCall("getNClob(int)", () -> rs.getNClob(1)),
                new UnsupportedCall("getNClob(String)", () -> rs.getNClob("c1")),
                new UnsupportedCall("getSQLXML(int)", () -> rs.getSQLXML(1)),
                new UnsupportedCall("getSQLXML(String)", () -> rs.getSQLXML("c1")),
                new UnsupportedCall("getNCharacterStream(int)", () -> rs.getNCharacterStream(1)),
                new UnsupportedCall("getNCharacterStream(String)", () -> rs.getNCharacterStream("c1")),
                new UnsupportedCall("updateNull(int)", () -> rs.updateNull(1)),
                new UnsupportedCall("updateBoolean(int)", () -> rs.updateBoolean(1, true)),
                new UnsupportedCall("updateByte(int)", () -> rs.updateByte(1, (byte) 1)),
                new UnsupportedCall("updateShort(int)", () -> rs.updateShort(1, (short) 1)),
                new UnsupportedCall("updateInt(int)", () -> rs.updateInt(1, 1)),
                new UnsupportedCall("updateLong(int)", () -> rs.updateLong(1, 1L)),
                new UnsupportedCall("updateFloat(int)", () -> rs.updateFloat(1, 1.0f)),
                new UnsupportedCall("updateDouble(int)", () -> rs.updateDouble(1, 1.0)),
                new UnsupportedCall("updateBigDecimal(int)", () -> rs.updateBigDecimal(1, null)),
                new UnsupportedCall("updateString(int)", () -> rs.updateString(1, "x")),
                new UnsupportedCall("updateBytes(int)", () -> rs.updateBytes(1, new byte[] {1})),
                new UnsupportedCall("updateDate(int)", () -> rs.updateDate(1, null)),
                new UnsupportedCall("updateTime(int)", () -> rs.updateTime(1, null)),
                new UnsupportedCall("updateTimestamp(int)", () -> rs.updateTimestamp(1, null)),
                new UnsupportedCall("updateAsciiStream(int,int)", () -> rs.updateAsciiStream(1, stream, 1)),
                new UnsupportedCall("updateBinaryStream(int,int)", () -> rs.updateBinaryStream(1, stream, 1)),
                new UnsupportedCall("updateCharacterStream(int,int)", () -> rs.updateCharacterStream(1, reader, 1)),
                new UnsupportedCall("updateObject(int,int)", () -> rs.updateObject(1, "x", 1)),
                new UnsupportedCall("updateObject(int)", () -> rs.updateObject(1, "x")),
                new UnsupportedCall("updateNull(String)", () -> rs.updateNull("c1")),
                new UnsupportedCall("updateBoolean(String)", () -> rs.updateBoolean("c1", true)),
                new UnsupportedCall("updateByte(String)", () -> rs.updateByte("c1", (byte) 1)),
                new UnsupportedCall("updateShort(String)", () -> rs.updateShort("c1", (short) 1)),
                new UnsupportedCall("updateInt(String)", () -> rs.updateInt("c1", 1)),
                new UnsupportedCall("updateLong(String)", () -> rs.updateLong("c1", 1L)),
                new UnsupportedCall("updateFloat(String)", () -> rs.updateFloat("c1", 1.0f)),
                new UnsupportedCall("updateDouble(String)", () -> rs.updateDouble("c1", 1.0)),
                new UnsupportedCall("updateBigDecimal(String)", () -> rs.updateBigDecimal("c1", null)),
                new UnsupportedCall("updateString(String)", () -> rs.updateString("c1", "x")),
                new UnsupportedCall("updateBytes(String)", () -> rs.updateBytes("c1", new byte[] {1})),
                new UnsupportedCall("updateDate(String)", () -> rs.updateDate("c1", null)),
                new UnsupportedCall("updateTime(String)", () -> rs.updateTime("c1", null)),
                new UnsupportedCall("updateTimestamp(String)", () -> rs.updateTimestamp("c1", null)),
                new UnsupportedCall("updateAsciiStream(String,int)", () -> rs.updateAsciiStream("c1", stream, 1)),
                new UnsupportedCall("updateBinaryStream(String,int)", () -> rs.updateBinaryStream("c1", stream, 1)),
                new UnsupportedCall("updateCharacterStream(String,int)", () -> rs.updateCharacterStream("c1", reader, 1)),
                new UnsupportedCall("updateObject(String,int)", () -> rs.updateObject("c1", "x", 1)),
                new UnsupportedCall("updateObject(String)", () -> rs.updateObject("c1", "x")),
                new UnsupportedCall("insertRow", rs::insertRow),
                new UnsupportedCall("updateRow", rs::updateRow),
                new UnsupportedCall("deleteRow", rs::deleteRow),
                new UnsupportedCall("refreshRow", rs::refreshRow),
                new UnsupportedCall("cancelRowUpdates", rs::cancelRowUpdates),
                new UnsupportedCall("moveToInsertRow", rs::moveToInsertRow),
                new UnsupportedCall("moveToCurrentRow", rs::moveToCurrentRow),
                new UnsupportedCall("updateRef(int)", () -> rs.updateRef(1, ref)),
                new UnsupportedCall("updateRef(String)", () -> rs.updateRef("c1", ref)),
                new UnsupportedCall("updateBlob(int)", () -> rs.updateBlob(1, blob)),
                new UnsupportedCall("updateBlob(String)", () -> rs.updateBlob("c1", blob)),
                new UnsupportedCall("updateClob(int)", () -> rs.updateClob(1, clob)),
                new UnsupportedCall("updateClob(String)", () -> rs.updateClob("c1", clob)),
                new UnsupportedCall("updateArray(int)", () -> rs.updateArray(1, array)),
                new UnsupportedCall("updateArray(String)", () -> rs.updateArray("c1", array)),
                new UnsupportedCall("updateRowId(int)", () -> rs.updateRowId(1, rowId)),
                new UnsupportedCall("updateRowId(String)", () -> rs.updateRowId("c1", rowId)),
                new UnsupportedCall("updateNString(int)", () -> rs.updateNString(1, "x")),
                new UnsupportedCall("updateNString(String)", () -> rs.updateNString("c1", "x")),
                new UnsupportedCall("updateNClob(int)", () -> rs.updateNClob(1, nClob)),
                new UnsupportedCall("updateNClob(String)", () -> rs.updateNClob("c1", nClob)),
                new UnsupportedCall("updateSQLXML(int)", () -> rs.updateSQLXML(1, sqlxml)),
                new UnsupportedCall("updateSQLXML(String)", () -> rs.updateSQLXML("c1", sqlxml)),
                new UnsupportedCall("updateNCharacterStream(int,long)", () -> rs.updateNCharacterStream(1, reader, 1L)),
                new UnsupportedCall("updateNCharacterStream(String,long)", () -> rs.updateNCharacterStream("c1", reader, 1L)),
                new UnsupportedCall("updateAsciiStream(int,long)", () -> rs.updateAsciiStream(1, stream, 1L)),
                new UnsupportedCall("updateBinaryStream(int,long)", () -> rs.updateBinaryStream(1, stream, 1L)),
                new UnsupportedCall("updateCharacterStream(int,long)", () -> rs.updateCharacterStream(1, reader, 1L)),
                new UnsupportedCall("updateAsciiStream(String,long)", () -> rs.updateAsciiStream("c1", stream, 1L)),
                new UnsupportedCall("updateBinaryStream(String,long)", () -> rs.updateBinaryStream("c1", stream, 1L)),
                new UnsupportedCall("updateCharacterStream(String,long)", () -> rs.updateCharacterStream("c1", reader, 1L)),
                new UnsupportedCall("updateBlob(int,InputStream,long)", () -> rs.updateBlob(1, stream, 1L)),
                new UnsupportedCall("updateBlob(String,InputStream,long)", () -> rs.updateBlob("c1", stream, 1L)),
                new UnsupportedCall("updateClob(int,Reader,long)", () -> rs.updateClob(1, reader, 1L)),
                new UnsupportedCall("updateClob(String,Reader,long)", () -> rs.updateClob("c1", reader, 1L)),
                new UnsupportedCall("updateNClob(int,Reader,long)", () -> rs.updateNClob(1, reader, 1L)),
                new UnsupportedCall("updateNClob(String,Reader,long)", () -> rs.updateNClob("c1", reader, 1L)),
                new UnsupportedCall("updateNCharacterStream(int)", () -> rs.updateNCharacterStream(1, reader)),
                new UnsupportedCall("updateNCharacterStream(String)", () -> rs.updateNCharacterStream("c1", reader)),
                new UnsupportedCall("updateAsciiStream(int)", () -> rs.updateAsciiStream(1, stream)),
                new UnsupportedCall("updateBinaryStream(int)", () -> rs.updateBinaryStream(1, stream)),
                new UnsupportedCall("updateCharacterStream(int)", () -> rs.updateCharacterStream(1, reader)),
                new UnsupportedCall("updateAsciiStream(String)", () -> rs.updateAsciiStream("c1", stream)),
                new UnsupportedCall("updateBinaryStream(String)", () -> rs.updateBinaryStream("c1", stream)),
                new UnsupportedCall("updateCharacterStream(String)", () -> rs.updateCharacterStream("c1", reader)),
                new UnsupportedCall("updateBlob(int,InputStream)", () -> rs.updateBlob(1, stream)),
                new UnsupportedCall("updateBlob(String,InputStream)", () -> rs.updateBlob("c1", stream)),
                new UnsupportedCall("updateClob(int,Reader)", () -> rs.updateClob(1, reader)),
                new UnsupportedCall("updateClob(String,Reader)", () -> rs.updateClob("c1", reader)),
                new UnsupportedCall("updateNClob(int,Reader)", () -> rs.updateNClob(1, reader)),
                new UnsupportedCall("updateNClob(String,Reader)", () -> rs.updateNClob("c1", reader)));

        for (UnsupportedCall call : calls) {
            assertThatThrownBy(call.call())
                    .as(call.name())
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
        }
    }

    private record UnsupportedCall(String name, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {}

    private static String read(Reader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[256];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            text.append(buffer, 0, count);
        }
        return text.toString();
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
