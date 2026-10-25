package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * JDBC 4.3 Appendix B, read side: Tables B-1, B-3 and B-6.
 *
 * <p>The oracle is the specification, not sqlite-jdbc. A failure here means the driver disagrees
 * with Appendix B; the test name names the table row, so the failure list is the gap list. Values
 * are supplied through {@link StmtResult} metadata rather than a live server, which keeps the
 * matrix exhaustive and fast.
 *
 * <p>B-1 is defined for CallableStatement getters, which this driver rejects. It is therefore
 * exercised through the natural ResultSet getter for each JDBC type (the same Java type the
 * CallableStatement getter would return). B-3 is exercised through {@code getObject(int)}. B-6 is
 * exercised cell by cell: a listed conversion must succeed and return a non-null value of the
 * getter's Java type, and an unlisted conversion must reject the value with a {@link SQLException}.
 */
class Jdbc4AppendixBReadTest {

    // ---------------------------------------------------------------------
    // Model for the JDBC types in Tables B-1, B-3 and B-6.
    // ---------------------------------------------------------------------

    record JdbcType(
            String name,
            int sqlType,
            Class<?> b1JavaType,
            Class<?> b3ObjectType,
            Value sample,
            boolean inB1B3) {}

    private static final Value TEXT_ABC = Value.of("abc");
    private static final Value TEXT_DATE = Value.of("2024-01-02");
    private static final Value TEXT_TIME = Value.of("03:04:05");
    private static final Value TEXT_TIMESTAMP = Value.of("2024-01-02 03:04:05");
    private static final Value TEXT_URL = Value.of("https://example.com");
    private static final Value TEXT_ROWID = Value.of("rowid-1");
    private static final Value TEXT_XML = Value.of("<a/>");
    private static final Value NUMBER_42 = Value.of(42L);
    private static final Value REAL_42_5 = Value.of(42.5);
    private static final Value BYTES_ABC = Value.of("abc".getBytes(StandardCharsets.UTF_8));

    private static final List<JdbcType> JDBC_TYPES = List.of(
            jdbc("CHAR", Types.CHAR, String.class, String.class, TEXT_ABC),
            jdbc("VARCHAR", Types.VARCHAR, String.class, String.class, TEXT_ABC),
            jdbc("LONGVARCHAR", Types.LONGVARCHAR, String.class, String.class, TEXT_ABC),
            jdbc("NUMERIC", Types.NUMERIC, BigDecimal.class, BigDecimal.class, NUMBER_42),
            jdbc("DECIMAL", Types.DECIMAL, BigDecimal.class, BigDecimal.class, NUMBER_42),
            jdbc("BIT", Types.BIT, boolean.class, Boolean.class, NUMBER_42),
            jdbc("BOOLEAN", Types.BOOLEAN, boolean.class, Boolean.class, NUMBER_42),
            jdbc("TINYINT", Types.TINYINT, byte.class, Integer.class, NUMBER_42),
            jdbc("SMALLINT", Types.SMALLINT, short.class, Integer.class, NUMBER_42),
            jdbc("INTEGER", Types.INTEGER, int.class, Integer.class, NUMBER_42),
            // A small value: B-3 keys the object type on the declared type, not the magnitude.
            jdbc("BIGINT", Types.BIGINT, long.class, Long.class, NUMBER_42),
            jdbc("REAL", Types.REAL, float.class, Float.class, REAL_42_5),
            jdbc("FLOAT", Types.FLOAT, double.class, Double.class, REAL_42_5),
            jdbc("DOUBLE", Types.DOUBLE, double.class, Double.class, REAL_42_5),
            jdbc("BINARY", Types.BINARY, byte[].class, byte[].class, BYTES_ABC),
            jdbc("VARBINARY", Types.VARBINARY, byte[].class, byte[].class, BYTES_ABC),
            jdbc("LONGVARBINARY", Types.LONGVARBINARY, byte[].class, byte[].class, BYTES_ABC),
            jdbc("DATE", Types.DATE, Date.class, Date.class, TEXT_DATE),
            jdbc("TIME", Types.TIME, Time.class, Time.class, TEXT_TIME),
            jdbc("TIMESTAMP", Types.TIMESTAMP, Timestamp.class, Timestamp.class, TEXT_TIMESTAMP),
            jdbc("CLOB", Types.CLOB, Clob.class, Clob.class, TEXT_ABC),
            jdbc("BLOB", Types.BLOB, Blob.class, Blob.class, BYTES_ABC),
            jdbc("ARRAY", Types.ARRAY, Array.class, Array.class, TEXT_ABC),
            jdbc("DISTINCT", Types.DISTINCT, String.class, String.class, TEXT_ABC),
            jdbc("STRUCT", Types.STRUCT, Struct.class, Struct.class, TEXT_ABC),
            jdbc("REF", Types.REF, Ref.class, Ref.class, TEXT_ABC),
            jdbc("DATALINK", Types.DATALINK, URL.class, URL.class, TEXT_URL),
            jdbc("JAVA_OBJECT", Types.JAVA_OBJECT, String.class, String.class, TEXT_ABC),
            jdbc("ROWID", Types.ROWID, RowId.class, RowId.class, TEXT_ROWID),
            jdbc("NCHAR", Types.NCHAR, String.class, String.class, TEXT_ABC),
            jdbc("NVARCHAR", Types.NVARCHAR, String.class, String.class, TEXT_ABC),
            jdbc("LONGNVARCHAR", Types.LONGNVARCHAR, String.class, String.class, TEXT_ABC),
            jdbc("NCLOB", Types.NCLOB, NClob.class, NClob.class, TEXT_ABC),
            jdbc("SQLXML", Types.SQLXML, SQLXML.class, SQLXML.class, TEXT_XML),
            new JdbcType(
                    "TIME_WITH_TIMEZONE",
                    Types.TIME_WITH_TIMEZONE,
                    null,
                    null,
                    Value.of("03:04:05+01:00"),
                    false),
            new JdbcType(
                    "TIMESTAMP_WITH_TIMEZONE",
                    Types.TIMESTAMP_WITH_TIMEZONE,
                    null,
                    null,
                    Value.of("2024-01-02 03:04:05+01:00"),
                    false));

    private static JdbcType jdbc(
            String name, int sqlType, Class<?> b1JavaType, Class<?> b3ObjectType, Value sample) {
        return new JdbcType(name, sqlType, b1JavaType, b3ObjectType, sample, true);
    }

    // ---------------------------------------------------------------------
    // Table B-1: the natural getter for each JDBC type returns the B-1 Java type.
    // ---------------------------------------------------------------------

    @FunctionalInterface
    interface SqlReader {
        Object read(ResultSet resultSet, int column) throws SQLException;
    }

    record Accessor(String name, Class<?> javaType, SqlReader reader, Set<String> supported) {}

    private static final Accessor GET_BYTE =
            new Accessor("getByte", byte.class, ResultSet::getByte, numericTextWithRowId());
    private static final Accessor GET_SHORT =
            new Accessor("getShort", short.class, ResultSet::getShort, numericText());
    private static final Accessor GET_INT =
            new Accessor("getInt", int.class, ResultSet::getInt, numericText());
    private static final Accessor GET_LONG =
            new Accessor("getLong", long.class, ResultSet::getLong, numericText());
    private static final Accessor GET_FLOAT =
            new Accessor("getFloat", float.class, ResultSet::getFloat, numericText());
    private static final Accessor GET_DOUBLE =
            new Accessor("getDouble", double.class, ResultSet::getDouble, numericText());
    private static final Accessor GET_BIG_DECIMAL =
            new Accessor("getBigDecimal", BigDecimal.class, ResultSet::getBigDecimal, numericText());
    private static final Accessor GET_BOOLEAN =
            new Accessor("getBoolean", boolean.class, ResultSet::getBoolean, numericText());
    private static final Accessor GET_STRING =
            new Accessor("getString", String.class, ResultSet::getString, stringTargets());
    private static final Accessor GET_N_STRING =
            new Accessor("getNString", String.class, ResultSet::getNString, stringTargets());
    private static final Accessor GET_BYTES = new Accessor(
            "getBytes", byte[].class, ResultSet::getBytes, set("BINARY", "VARBINARY", "LONGVARBINARY"));
    private static final Accessor GET_DATE = new Accessor(
            "getDate", Date.class, ResultSet::getDate, set("CHAR", "VARCHAR", "LONGVARCHAR", "DATE", "TIMESTAMP"));
    private static final Accessor GET_TIME = new Accessor(
            "getTime", Time.class, ResultSet::getTime, set("CHAR", "VARCHAR", "LONGVARCHAR", "TIME", "TIMESTAMP"));
    private static final Accessor GET_TIMESTAMP = new Accessor(
            "getTimestamp",
            Timestamp.class,
            ResultSet::getTimestamp,
            set("CHAR", "VARCHAR", "LONGVARCHAR", "DATE", "TIME", "TIMESTAMP"));
    private static final Accessor GET_ASCII_STREAM = new Accessor(
            "getAsciiStream",
            InputStream.class,
            ResultSet::getAsciiStream,
            set("CHAR", "VARCHAR", "LONGVARCHAR", "BINARY", "VARBINARY", "LONGVARBINARY", "CLOB", "NCLOB"));
    private static final Accessor GET_BINARY_STREAM = new Accessor(
            "getBinaryStream",
            InputStream.class,
            ResultSet::getBinaryStream,
            set("BINARY", "VARBINARY", "LONGVARBINARY"));
    private static final Accessor GET_CHARACTER_STREAM = new Accessor(
            "getCharacterStream",
            Reader.class,
            ResultSet::getCharacterStream,
            set(
                    "CHAR",
                    "VARCHAR",
                    "LONGVARCHAR",
                    "BINARY",
                    "VARBINARY",
                    "LONGVARBINARY",
                    "CLOB",
                    "NCHAR",
                    "NVARCHAR",
                    "LONGNVARCHAR",
                    "NCLOB",
                    "SQLXML"));
    private static final Accessor GET_N_CHARACTER_STREAM = new Accessor(
            "getNCharacterStream",
            Reader.class,
            ResultSet::getNCharacterStream,
            set(
                    "CHAR",
                    "VARCHAR",
                    "LONGVARCHAR",
                    "BINARY",
                    "VARBINARY",
                    "LONGVARBINARY",
                    "CLOB",
                    "NCHAR",
                    "NVARCHAR",
                    "LONGNVARCHAR",
                    "NCLOB",
                    "SQLXML"));
    private static final Accessor GET_CLOB =
            new Accessor("getClob", Clob.class, ResultSet::getClob, set("CLOB", "NCLOB"));
    private static final Accessor GET_N_CLOB =
            new Accessor("getNClob", NClob.class, ResultSet::getNClob, set("CLOB", "NCLOB"));
    private static final Accessor GET_BLOB =
            new Accessor("getBlob", Blob.class, ResultSet::getBlob, set("BLOB"));
    private static final Accessor GET_ARRAY =
            new Accessor("getArray", Array.class, ResultSet::getArray, set("ARRAY"));
    private static final Accessor GET_REF =
            new Accessor("getRef", Ref.class, ResultSet::getRef, set("REF"));
    private static final Accessor GET_URL =
            new Accessor("getURL", URL.class, ResultSet::getURL, set("DATALINK"));
    private static final Accessor GET_OBJECT =
            new Accessor("getObject", Object.class, (rs, i) -> rs.getObject(i), allJdbcTypes());
    private static final Accessor GET_ROW_ID =
            new Accessor("getRowId", RowId.class, ResultSet::getRowId, set("ROWID"));
    private static final Accessor GET_SQLXML =
            new Accessor("getSQLXML", SQLXML.class, ResultSet::getSQLXML, set("SQLXML"));

    private static final List<Accessor> ACCESSORS = List.of(
            GET_BYTE,
            GET_SHORT,
            GET_INT,
            GET_LONG,
            GET_FLOAT,
            GET_DOUBLE,
            GET_BIG_DECIMAL,
            GET_BOOLEAN,
            GET_STRING,
            GET_N_STRING,
            GET_BYTES,
            GET_DATE,
            GET_TIME,
            GET_TIMESTAMP,
            GET_ASCII_STREAM,
            GET_BINARY_STREAM,
            GET_CHARACTER_STREAM,
            GET_N_CHARACTER_STREAM,
            GET_CLOB,
            GET_N_CLOB,
            GET_BLOB,
            GET_ARRAY,
            GET_REF,
            GET_URL,
            GET_OBJECT,
            GET_ROW_ID,
            GET_SQLXML);

    private static final Map<String, Accessor> B1_ACCESSORS = b1Accessors();

    private static Map<String, Accessor> b1Accessors() {
        Map<String, Accessor> accessors = new LinkedHashMap<>();
        for (String name : set("CHAR", "VARCHAR", "LONGVARCHAR", "NCHAR", "NVARCHAR", "LONGNVARCHAR", "DISTINCT")) {
            accessors.put(name, GET_STRING);
        }
        accessors.put("NUMERIC", GET_BIG_DECIMAL);
        accessors.put("DECIMAL", GET_BIG_DECIMAL);
        accessors.put("BIT", GET_BOOLEAN);
        accessors.put("BOOLEAN", GET_BOOLEAN);
        accessors.put("TINYINT", GET_BYTE);
        accessors.put("SMALLINT", GET_SHORT);
        accessors.put("INTEGER", GET_INT);
        accessors.put("BIGINT", GET_LONG);
        accessors.put("REAL", GET_FLOAT);
        accessors.put("FLOAT", GET_DOUBLE);
        accessors.put("DOUBLE", GET_DOUBLE);
        accessors.put("BINARY", GET_BYTES);
        accessors.put("VARBINARY", GET_BYTES);
        accessors.put("LONGVARBINARY", GET_BYTES);
        accessors.put("DATE", GET_DATE);
        accessors.put("TIME", GET_TIME);
        accessors.put("TIMESTAMP", GET_TIMESTAMP);
        accessors.put("CLOB", GET_CLOB);
        accessors.put("BLOB", GET_BLOB);
        accessors.put("ARRAY", GET_ARRAY);
        accessors.put("STRUCT", GET_OBJECT);
        accessors.put("REF", GET_REF);
        accessors.put("DATALINK", GET_URL);
        accessors.put("JAVA_OBJECT", GET_OBJECT);
        accessors.put("ROWID", GET_ROW_ID);
        accessors.put("NCLOB", GET_N_CLOB);
        accessors.put("SQLXML", GET_SQLXML);
        return Map.copyOf(accessors);
    }

    static Stream<Arguments> b1Cases() {
        return JDBC_TYPES.stream()
                .filter(JdbcType::inB1B3)
                .map(type -> Arguments.of(type, B1_ACCESSORS.get(type.name())));
    }

    @ParameterizedTest(name = "B-1 {0} via {1}")
    @MethodSource("b1Cases")
    void b1NaturalGetterReturnsDeclaredJavaType(JdbcType type, Accessor accessor) throws SQLException {
        LibsqlResultSet resultSet = oneRow(type, type.sample());
        assertThat(resultSet.next()).isTrue();

        Object value;
        try {
            value = accessor.reader().read(resultSet, 1);
        } catch (SQLException e) {
            throw new AssertionError(
                    "B-1: " + type.name() + " via " + accessor.name() + " threw " + e, e);
        }

        assertThat(value)
                .as("B-1: %s read with %s must be a %s", type.name(), accessor.name(), type.b1JavaType())
                .isInstanceOf(boxed(type.b1JavaType()));
    }

    // ---------------------------------------------------------------------
    // Table B-3: getObject maps each JDBC type to its Java object type.
    // ---------------------------------------------------------------------

    static Stream<JdbcType> b3Cases() {
        return JDBC_TYPES.stream().filter(JdbcType::inB1B3);
    }

    @ParameterizedTest(name = "B-3 {0} -> {1}")
    @MethodSource("b3Cases")
    void b3GetObjectReturnsDeclaredObjectType(JdbcType type) throws SQLException {
        LibsqlResultSet resultSet = oneRow(type, type.sample());
        assertThat(resultSet.next()).isTrue();

        assertThat(resultSet.getObject(1))
                .as("B-3: %s must read back as %s", type.name(), type.b3ObjectType())
                .isInstanceOf(type.b3ObjectType());
    }

    @Test
    void b3GetObjectReturnsNullForNull() throws SQLException {
        for (JdbcType type : JDBC_TYPES) {
            if (!type.inB1B3()) {
                continue;
            }
            LibsqlResultSet resultSet = oneRow(type, new Value.Null());
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getObject(1)).as("B-3: NULL %s", type.name()).isNull();
            assertThat(resultSet.wasNull()).as("B-3: wasNull for NULL %s", type.name()).isTrue();
        }
    }

    // ---------------------------------------------------------------------
    // Table B-6: which JDBC types each getter may read.
    // ---------------------------------------------------------------------

    static Stream<Arguments> b6Cases() {
        List<Arguments> arguments = new ArrayList<>();
        for (Accessor accessor : ACCESSORS) {
            for (JdbcType type : JDBC_TYPES) {
                arguments.add(Arguments.of(accessor, type, accessor.supported().contains(type.name())));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "B-6 {0} on {1} ({2})")
    @MethodSource("b6Cases")
    void b6GetterHonoursSupportedJdbcTypes(Accessor getter, JdbcType type, boolean supported)
            throws SQLException {
        LibsqlResultSet resultSet = oneRow(type, fixtureFor(getter, type));
        assertThat(resultSet.next()).isTrue();

        if (supported) {
            Object value;
            try {
                value = getter.reader().read(resultSet, 1);
            } catch (SQLException e) {
                throw new AssertionError(
                        "B-6: " + getter.name() + " must support " + type.name() + " but threw " + e, e);
            }
            assertThat(value)
                    .as("B-6: %s must support %s", getter.name(), type.name())
                    .isNotNull();
            if (getter.javaType() != Object.class && getter.javaType() != byte[].class) {
                assertThat(value)
                        .as("B-6: %s on %s must return %s", getter.name(), type.name(), getter.javaType())
                        .isInstanceOf(boxed(getter.javaType()));
            }
        } else {
            try {
                Object value = getter.reader().read(resultSet, 1);
                fail("B-6: " + getter.name() + " must reject " + type.name()
                        + " but returned " + value);
            } catch (SQLException expected) {
                // The specification lists no conversion, so rejection is the contract.
            }
        }
    }

    @Test
    void b6NullIsVisibleThroughWasNull() throws SQLException {
        for (Accessor getter : ACCESSORS) {
            LibsqlResultSet resultSet = oneRow(jdbcType("VARCHAR"), new Value.Null());
            assertThat(resultSet.next()).isTrue();
            try {
                getter.reader().read(resultSet, 1);
                assertThat(resultSet.wasNull())
                        .as("B-6: %s on NULL must report wasNull", getter.name())
                        .isTrue();
            } catch (SQLException unsupportedOnNull) {
                // A getter the driver does not implement at all is still allowed to reject NULL.
            }
        }
    }

    @Test
    void b6NumericGettersRejectNonNumericText() {
        for (Accessor getter : List.of(
                GET_BYTE, GET_SHORT, GET_INT, GET_LONG, GET_FLOAT, GET_DOUBLE, GET_BIG_DECIMAL)) {
            LibsqlResultSet resultSet;
            try {
                resultSet = oneRow(jdbcType("VARCHAR"), Value.of("not-a-number"));
                assertThat(resultSet.next()).isTrue();
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
            try {
                Object value = getter.reader().read(resultSet, 1);
                fail("B-6: " + getter.name()
                        + " must reject non-numeric text instead of coercing to " + value);
            } catch (SQLException expected) {
                // Any SQLException is a pass: the spec does not prescribe the exception type.
            }
        }
    }

    @Test
    void b6TemporalGettersRejectNonDateText() {
        for (Accessor getter : List.of(GET_DATE, GET_TIME, GET_TIMESTAMP)) {
            LibsqlResultSet resultSet;
            try {
                resultSet = oneRow(jdbcType("VARCHAR"), Value.of("not-a-date"));
                assertThat(resultSet.next()).isTrue();
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
            try {
                Object value = getter.reader().read(resultSet, 1);
                fail("B-6: " + getter.name() + " must reject non-date text but returned " + value);
            } catch (SQLException expected) {
                // Any SQLException is a pass: the spec does not prescribe the exception type.
            }
        }
    }

    // ---------------------------------------------------------------------
    // PR-3 wrappers: URL, NClob, SQLXML, RowId, and the ASCII/Unicode streams.
    // ---------------------------------------------------------------------

    @Test
    void b1UrlGetterParsesAbsoluteTextAndRejectsTheRest() throws SQLException {
        LibsqlResultSet absolute = oneRow(jdbcType("DATALINK"), TEXT_URL);
        assertThat(absolute.next()).isTrue();
        assertThat(absolute.getURL(1).toExternalForm()).isEqualTo("https://example.com");
        assertThat(absolute.getURL("c").toExternalForm()).isEqualTo("https://example.com");

        LibsqlResultSet malformed = oneRow(jdbcType("DATALINK"), Value.of("not a url"));
        assertThat(malformed.next()).isTrue();
        assertThatThrownBy(() -> malformed.getURL(1))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("URL");
    }

    @Test
    void b3NewWrappersReadBackTheirContent() throws Exception {
        LibsqlResultSet nclobRow = oneRow(jdbcType("NCLOB"), TEXT_ABC);
        assertThat(nclobRow.next()).isTrue();
        NClob nclob = nclobRow.getNClob(1);
        assertThat(nclob.length()).isEqualTo(3);
        assertThat(nclob.getSubString(1, 2)).isEqualTo("ab");
        assertThat(readAll(nclob.getCharacterStream())).isEqualTo("abc");
        assertThat(nclobRow.getNClob("c").getSubString(2, 1)).isEqualTo("b");

        LibsqlResultSet xmlRow = oneRow(jdbcType("SQLXML"), TEXT_XML);
        assertThat(xmlRow.next()).isTrue();
        SQLXML xml = xmlRow.getSQLXML(1);
        assertThat(xml.getString()).isEqualTo("<a/>");
        assertThat(readAll(xml.getCharacterStream())).isEqualTo("<a/>");
        assertThat(readAll(xml.getBinaryStream())).isEqualTo("<a/>".getBytes(StandardCharsets.UTF_8));
        assertThat(xmlRow.getSQLXML("c").getString()).isEqualTo("<a/>");

        LibsqlResultSet rowIdRow = oneRow(jdbcType("ROWID"), TEXT_ROWID);
        assertThat(rowIdRow.next()).isTrue();
        assertThat(rowIdRow.getRowId(1).getBytes())
                .isEqualTo("rowid-1".getBytes(StandardCharsets.UTF_8));
        assertThat(rowIdRow.getRowId("c").getBytes())
                .isEqualTo("rowid-1".getBytes(StandardCharsets.UTF_8));

        LibsqlResultSet textRow = oneRow(jdbcType("VARCHAR"), TEXT_ABC);
        assertThat(textRow.next()).isTrue();
        assertThat(readAll(textRow.getAsciiStream(1)))
                .isEqualTo("abc".getBytes(StandardCharsets.US_ASCII));
        assertThat(readAll(textRow.getAsciiStream("c")))
                .isEqualTo("abc".getBytes(StandardCharsets.US_ASCII));
        assertThat(readAll(textRow.getNCharacterStream(1))).isEqualTo("abc");
        assertThat(readAll(textRow.getNCharacterStream("c"))).isEqualTo("abc");
    }

    @Test
    void b3FreedWrappersRejectFurtherReads() throws SQLException {
        LibsqlResultSet nclobRow = oneRow(jdbcType("NCLOB"), TEXT_ABC);
        assertThat(nclobRow.next()).isTrue();
        NClob nclob = nclobRow.getNClob(1);
        nclob.free();
        assertThatThrownBy(nclob::length).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> nclob.getSubString(1, 1)).isInstanceOf(SQLException.class);
        assertThatThrownBy(nclob::getCharacterStream).isInstanceOf(SQLException.class);

        LibsqlResultSet xmlRow = oneRow(jdbcType("SQLXML"), TEXT_XML);
        assertThat(xmlRow.next()).isTrue();
        SQLXML xml = xmlRow.getSQLXML(1);
        xml.free();
        assertThatThrownBy(xml::getString).isInstanceOf(SQLException.class);
        assertThatThrownBy(xml::getCharacterStream).isInstanceOf(SQLException.class);
        assertThatThrownBy(xml::getBinaryStream).isInstanceOf(SQLException.class);

        LibsqlResultSet clobRow = oneRow(jdbcType("CLOB"), TEXT_ABC);
        assertThat(clobRow.next()).isTrue();
        Clob clob = clobRow.getClob(1);
        clob.free();
        assertThatThrownBy(clob::length).isInstanceOf(SQLException.class);
        assertThatThrownBy(clob::getCharacterStream).isInstanceOf(SQLException.class);
        assertThatThrownBy(clob::getAsciiStream).isInstanceOf(SQLException.class);

        LibsqlResultSet blobRow = oneRow(jdbcType("BLOB"), BYTES_ABC);
        assertThat(blobRow.next()).isTrue();
        Blob blob = blobRow.getBlob(1);
        blob.free();
        assertThatThrownBy(blob::length).isInstanceOf(SQLException.class);
        assertThatThrownBy(blob::getBinaryStream).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> blob.getBytes(1, 1)).isInstanceOf(SQLException.class);
    }

    @Test
    void b3RowIdComparesAndCopiesItsBytes() {
        LibsqlRowId rowId = new LibsqlRowId(new byte[] {1, 2});
        byte[] returned = rowId.getBytes();
        returned[0] = 9;

        assertThat(rowId.getBytes()).containsExactly(1, 2);
        assertThat(rowId)
                .isEqualTo(new LibsqlRowId(new byte[] {1, 2}))
                .hasSameHashCodeAs(new LibsqlRowId(new byte[] {1, 2}))
                .isNotEqualTo(new LibsqlRowId(new byte[] {1, 3}));
    }

    // ---------------------------------------------------------------------
    // Fixtures and helpers.
    // ---------------------------------------------------------------------

    private static Value fixtureFor(Accessor getter, JdbcType type) {
        boolean textual = isTextual(type.name());
        return switch (getter.name()) {
            case "getByte", "getShort", "getInt", "getLong", "getFloat", "getDouble", "getBigDecimal",
                    "getBoolean" ->
                    textual ? Value.of("42") : type.sample();
            case "getDate", "getTime", "getTimestamp" -> textual ? TEXT_TIMESTAMP : type.sample();
            case "getBytes", "getBinaryStream", "getAsciiStream", "getCharacterStream",
                    "getNCharacterStream", "getClob", "getNClob" ->
                    textual ? TEXT_ABC : type.sample();
            case "getURL" -> TEXT_URL;
            case "getRowId" -> TEXT_ROWID;
            case "getSQLXML" -> TEXT_XML;
            case "getArray", "getRef" -> TEXT_ABC;
            default -> type.sample();
        };
    }

    private static boolean isTextual(String name) {
        return switch (name) {
            case "CHAR", "VARCHAR", "LONGVARCHAR", "NCHAR", "NVARCHAR", "LONGNVARCHAR", "CLOB",
                    "NCLOB", "DATE", "TIME", "TIMESTAMP", "DATALINK", "SQLXML", "ROWID", "ARRAY",
                    "STRUCT", "REF", "JAVA_OBJECT", "DISTINCT", "TIME_WITH_TIMEZONE",
                    "TIMESTAMP_WITH_TIMEZONE" ->
                    true;
            default -> false;
        };
    }

    private static LibsqlResultSet oneRow(JdbcType type, Value value) {
        return oneRow(type.name(), value);
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[256];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            text.append(buffer, 0, count);
        }
        return text.toString();
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        return stream.readAllBytes();
    }

    private static LibsqlResultSet oneRow(String declaredType, Value value) {
        return new LibsqlResultSet(
                new LibsqlStatement(null),
                new StmtResult(
                        List.of(new Col("c", declaredType)),
                        List.of(new Row(List.of(value))),
                        0,
                        null));
    }

    private static JdbcType jdbcType(String name) {
        return JDBC_TYPES.stream()
                .filter(type -> type.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name));
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        throw new IllegalArgumentException(type.getName());
    }

    private static Set<String> set(String... names) {
        return Set.of(names);
    }

    private static Set<String> numericText() {
        return set(
                "TINYINT",
                "SMALLINT",
                "INTEGER",
                "BIGINT",
                "REAL",
                "FLOAT",
                "DOUBLE",
                "DECIMAL",
                "NUMERIC",
                "BIT",
                "BOOLEAN",
                "CHAR",
                "VARCHAR",
                "LONGVARCHAR");
    }

    private static Set<String> numericTextWithRowId() {
        Set<String> names = new java.util.HashSet<>(numericText());
        names.add("ROWID");
        return Set.copyOf(names);
    }

    private static Set<String> stringTargets() {
        return set(
                "TINYINT",
                "SMALLINT",
                "INTEGER",
                "BIGINT",
                "REAL",
                "FLOAT",
                "DOUBLE",
                "DECIMAL",
                "NUMERIC",
                "BIT",
                "BOOLEAN",
                "CHAR",
                "VARCHAR",
                "LONGVARCHAR",
                "BINARY",
                "VARBINARY",
                "LONGVARBINARY",
                "DATE",
                "TIME",
                "TIMESTAMP",
                "DATALINK",
                "NCHAR",
                "NVARCHAR",
                "LONGNVARCHAR");
    }

    private static Set<String> allJdbcTypes() {
        return JDBC_TYPES.stream()
                .map(JdbcType::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
