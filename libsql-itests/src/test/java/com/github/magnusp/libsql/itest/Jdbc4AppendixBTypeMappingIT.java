package com.github.magnusp.libsql.itest;

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * JDBC 4.3 Appendix B, server round trips: Tables B-1, B-2, B-3, B-4, B-5 and B-6.
 *
 * <p>The companion unit suite ({@code Jdbc4AppendixBReadTest} and {@code
 * Jdbc4AppendixBSetObjectTest}) supplies values through Hrana metadata. This suite creates real
 * sqld tables so the declared type comes from the server and the binding actually round trips.
 *
 * <p>The oracle is the specification, not sqlite-jdbc. A failure names the table, row and column.
 * Where the appendix intentionally leaves the value representation open (for example Calendar to
 * CHAR, or Time to TIMESTAMP), the assertion is limited to the required Java type.
 */
public class Jdbc4AppendixBTypeMappingIT extends SqldContainerBase {

    private static String url() {
        return "jdbc:libsql:" + getHttpUrl();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(url());
    }

    private static String table(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    // =====================================================================
    // Table B-1, B-3: read path over real DDL.
    // =====================================================================

    record JdbcType(
            String name,
            int sqlType,
            Class<?> b1JavaType,
            Class<?> b3ObjectType,
            Object sample) {}

    private static final String TEXT_ABC = "abc";
    private static final String TEXT_DATE = "2024-01-02";
    private static final String TEXT_TIME = "03:04:05";
    private static final String TEXT_TIMESTAMP = "2024-01-02 03:04:05";
    private static final String TEXT_URL = "https://example.com";
    private static final String TEXT_ROWID = "rowid-1";
    private static final String TEXT_XML = "<a/>";
    private static final byte[] BYTES_ABC = "abc".getBytes(StandardCharsets.UTF_8);

    private static final List<JdbcType> READ_TYPES = List.of(
            jdbc("CHAR", Types.CHAR, String.class, String.class, TEXT_ABC),
            jdbc("VARCHAR", Types.VARCHAR, String.class, String.class, TEXT_ABC),
            jdbc("LONGVARCHAR", Types.LONGVARCHAR, String.class, String.class, TEXT_ABC),
            jdbc("NUMERIC", Types.NUMERIC, BigDecimal.class, BigDecimal.class, 42L),
            jdbc("DECIMAL", Types.DECIMAL, BigDecimal.class, BigDecimal.class, 42L),
            jdbc("BIT", Types.BIT, boolean.class, Boolean.class, 42L),
            jdbc("BOOLEAN", Types.BOOLEAN, boolean.class, Boolean.class, 42L),
            jdbc("TINYINT", Types.TINYINT, byte.class, Integer.class, 42L),
            jdbc("SMALLINT", Types.SMALLINT, short.class, Integer.class, 42L),
            jdbc("INTEGER", Types.INTEGER, int.class, Integer.class, 42L),
            jdbc("BIGINT", Types.BIGINT, long.class, Long.class, 42L),
            jdbc("REAL", Types.REAL, float.class, Float.class, 42.5d),
            jdbc("FLOAT", Types.FLOAT, double.class, Double.class, 42.5d),
            jdbc("DOUBLE", Types.DOUBLE, double.class, Double.class, 42.5d),
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
            jdbc("SQLXML", Types.SQLXML, SQLXML.class, SQLXML.class, TEXT_XML));

    private static JdbcType jdbc(
            String name, int sqlType, Class<?> b1, Class<?> b3, Object sample) {
        return new JdbcType(name, sqlType, b1, b3, sample);
    }

    interface SqlRead {
        Object read(ResultSet resultSet, int column) throws SQLException;
    }

    private static final Map<String, SqlRead> NATURAL_GETTER = naturalGetters();

    private static Map<String, SqlRead> naturalGetters() {
        Map<String, SqlRead> getters = new LinkedHashMap<>();
        for (String name : List.of("CHAR", "VARCHAR", "LONGVARCHAR", "NCHAR", "NVARCHAR", "LONGNVARCHAR", "DISTINCT")) {
            getters.put(name, ResultSet::getString);
        }
        getters.put("NUMERIC", ResultSet::getBigDecimal);
        getters.put("DECIMAL", ResultSet::getBigDecimal);
        getters.put("BIT", ResultSet::getBoolean);
        getters.put("BOOLEAN", ResultSet::getBoolean);
        getters.put("TINYINT", ResultSet::getByte);
        getters.put("SMALLINT", ResultSet::getShort);
        getters.put("INTEGER", ResultSet::getInt);
        getters.put("BIGINT", ResultSet::getLong);
        getters.put("REAL", ResultSet::getFloat);
        getters.put("FLOAT", ResultSet::getDouble);
        getters.put("DOUBLE", ResultSet::getDouble);
        getters.put("BINARY", ResultSet::getBytes);
        getters.put("VARBINARY", ResultSet::getBytes);
        getters.put("LONGVARBINARY", ResultSet::getBytes);
        getters.put("DATE", ResultSet::getDate);
        getters.put("TIME", ResultSet::getTime);
        getters.put("TIMESTAMP", ResultSet::getTimestamp);
        getters.put("CLOB", ResultSet::getClob);
        getters.put("BLOB", ResultSet::getBlob);
        getters.put("ARRAY", ResultSet::getArray);
        getters.put("STRUCT", (rs, i) -> rs.getObject(i));
        getters.put("REF", ResultSet::getRef);
        getters.put("DATALINK", ResultSet::getURL);
        getters.put("JAVA_OBJECT", (rs, i) -> rs.getObject(i));
        getters.put("ROWID", ResultSet::getRowId);
        getters.put("NCLOB", ResultSet::getNClob);
        getters.put("SQLXML", ResultSet::getSQLXML);
        return Map.copyOf(getters);
    }

    @ParameterizedTest(name = "B-1/B-3 read {0} from a real column")
    @MethodSource("readTypeRows")
    void b1AndB3ReadPathAgainstRealDdl(JdbcType type) throws SQLException {
        String table = table("read");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE " + table + " (v \"" + type.name() + "\")");
            try (PreparedStatement insert =
                    connection.prepareStatement("INSERT INTO " + table + " (v) VALUES (?)")) {
                insert.setObject(1, type.sample());
                insert.executeUpdate();
            }
            try (Statement query = connection.createStatement();
                    ResultSet resultSet = query.executeQuery("SELECT v FROM " + table)) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getMetaData().getColumnTypeName(1))
                        .as("server reported declared type for %s", type.name())
                        .isEqualTo(type.name());

                List<Executable> checks = new ArrayList<>();
                checks.add(() -> {
                    Object natural;
                    try {
                        natural = NATURAL_GETTER.get(type.name()).read(resultSet, 1);
                    } catch (SQLException e) {
                        throw new AssertionError(
                                "B-1: " + type.name() + " via its natural getter threw " + e, e);
                    }
                    assertThat(natural)
                            .as("B-1: %s must read as %s", type.name(), type.b1JavaType())
                            .isInstanceOf(boxed(type.b1JavaType()));
                });
                checks.add(() -> assertThat(resultSet.getObject(1))
                        .as("B-3: %s must read back as %s", type.name(), type.b3ObjectType())
                        .isInstanceOf(type.b3ObjectType()));
                assertAll(type.name(), checks);
            }
        }
    }

    static Stream<JdbcType> readTypeRows() {
        return READ_TYPES.stream();
    }

    // =====================================================================
    // Table B-6: each getter reads its recommended JDBC type from real DDL.
    // =====================================================================

    record GetterCell(
            String getter, String declaredType, Object sample, SqlRead reader, Class<?> javaType) {}

    private static final List<GetterCell> B6_CELLS = List.of(
            new GetterCell("getByte", "TINYINT", 42L, ResultSet::getByte, byte.class),
            new GetterCell("getShort", "SMALLINT", 42L, ResultSet::getShort, short.class),
            new GetterCell("getInt", "INTEGER", 42L, ResultSet::getInt, int.class),
            new GetterCell("getLong", "BIGINT", 42L, ResultSet::getLong, long.class),
            new GetterCell("getFloat", "REAL", 42.5d, ResultSet::getFloat, float.class),
            new GetterCell("getDouble", "DOUBLE", 42.5d, ResultSet::getDouble, double.class),
            new GetterCell("getBigDecimal", "DECIMAL", 42L, ResultSet::getBigDecimal, BigDecimal.class),
            new GetterCell("getBoolean", "BOOLEAN", 42L, ResultSet::getBoolean, boolean.class),
            new GetterCell("getString", "VARCHAR", TEXT_ABC, ResultSet::getString, String.class),
            new GetterCell("getNString", "NCHAR", TEXT_ABC, ResultSet::getNString, String.class),
            new GetterCell("getBytes", "VARBINARY", BYTES_ABC, ResultSet::getBytes, byte[].class),
            new GetterCell("getDate", "DATE", TEXT_DATE, ResultSet::getDate, Date.class),
            new GetterCell("getTime", "TIME", TEXT_TIME, ResultSet::getTime, Time.class),
            new GetterCell("getTimestamp", "TIMESTAMP", TEXT_TIMESTAMP, ResultSet::getTimestamp,
                    Timestamp.class),
            new GetterCell("getAsciiStream", "LONGVARCHAR", TEXT_ABC, ResultSet::getAsciiStream,
                    java.io.InputStream.class),
            new GetterCell("getBinaryStream", "LONGVARBINARY", BYTES_ABC,
                    ResultSet::getBinaryStream, java.io.InputStream.class),
            new GetterCell("getCharacterStream", "LONGVARCHAR", TEXT_ABC,
                    ResultSet::getCharacterStream, java.io.Reader.class),
            new GetterCell("getNCharacterStream", "LONGNVARCHAR", TEXT_ABC,
                    ResultSet::getNCharacterStream, java.io.Reader.class),
            new GetterCell("getClob", "CLOB", TEXT_ABC, ResultSet::getClob, Clob.class),
            new GetterCell("getNClob", "NCLOB", TEXT_ABC, ResultSet::getNClob, NClob.class),
            new GetterCell("getBlob", "BLOB", BYTES_ABC, ResultSet::getBlob, Blob.class),
            new GetterCell("getArray", "ARRAY", TEXT_ABC, ResultSet::getArray, Array.class),
            new GetterCell("getRef", "REF", TEXT_ABC, ResultSet::getRef, Ref.class),
            new GetterCell("getURL", "DATALINK", TEXT_URL, ResultSet::getURL, URL.class),
            new GetterCell("getObject", "JAVA_OBJECT", TEXT_ABC, (rs, i) -> rs.getObject(i),
                    Object.class),
            new GetterCell("getObject", "TIME_WITH_TIMEZONE", "03:04:05Z",
                    (rs, i) -> rs.getObject(i), OffsetTime.class),
            new GetterCell("getObject", "TIMESTAMP_WITH_TIMEZONE", "2024-01-02T03:04:05Z",
                    (rs, i) -> rs.getObject(i), OffsetDateTime.class),
            new GetterCell("getRowId", "ROWID", TEXT_ROWID, ResultSet::getRowId, RowId.class),
            new GetterCell("getSQLXML", "SQLXML", TEXT_XML, ResultSet::getSQLXML, SQLXML.class));

    @ParameterizedTest(name = "B-6 {0} reads its recommended {1}")
    @MethodSource("b6Cells")
    void b6GetterReadsRecommendedTypeFromRealDdl(GetterCell cell) throws SQLException {
        String table = table("b6");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (v \"" + cell.declaredType() + "\")");
            try (PreparedStatement insert =
                    connection.prepareStatement("INSERT INTO " + table + " (v) VALUES (?)")) {
                insert.setObject(1, cell.sample());
                insert.executeUpdate();
            }
            try (Statement query = connection.createStatement();
                    ResultSet resultSet = query.executeQuery("SELECT v FROM " + table)) {
                assertThat(resultSet.next()).isTrue();
                Object value;
                try {
                    value = cell.reader().read(resultSet, 1);
                } catch (SQLException e) {
                    throw new AssertionError(
                            "B-6: " + cell.getter() + " on " + cell.declaredType() + " threw " + e, e);
                }
                assertThat(value)
                        .as("B-6: %s on %s must return a %s", cell.getter(), cell.declaredType(),
                                cell.javaType())
                        .isNotNull();
                if (cell.javaType() != Object.class && cell.javaType() != byte[].class) {
                    assertThat(value)
                            .as("B-6: %s on %s must return a %s", cell.getter(), cell.declaredType(),
                                    cell.javaType())
                            .isInstanceOf(boxed(cell.javaType()));
                }
            }
        }
    }

    static Stream<GetterCell> b6Cells() {
        return B6_CELLS.stream();
    }

    // =====================================================================
    // Table B-2: standard setters map a Java type to a JDBC type.
    // =====================================================================

    record WriteRow(
            String name,
            Object value,
            String targetName,
            int targetType,
            Set<String> storage,
            SqlRead reader,
            Expected expected) {}

    record Expected(Object value, Class<?> anyType) {
        static Expected of(Object value) {
            return new Expected(value, null);
        }

        static Expected any(Class<?> type) {
            return new Expected(null, type);
        }
    }

    private static final Set<String> INTEGRAL = Set.of("integer");
    private static final Set<String> REAL = Set.of("real");
    private static final Set<String> NUMERIC = Set.of("integer", "real");
    private static final Set<String> TEXT = Set.of("text");
    private static final Set<String> BLOB = Set.of("blob");
    private static final Set<String> UNSPECIFIED = Set.of();

    private static final List<WriteRow> B2_ROWS = List.of(
            new WriteRow("String", "abc", "VARCHAR", Types.VARCHAR, TEXT,
                    ResultSet::getString, Expected.of("abc")),
            new WriteRow("BigDecimal", new BigDecimal("7"), "NUMERIC", Types.NUMERIC, NUMERIC,
                    ResultSet::getBigDecimal, Expected.of(new BigDecimal("7"))),
            new WriteRow("boolean", true, "BOOLEAN", Types.BOOLEAN, INTEGRAL,
                    ResultSet::getBoolean, Expected.of(true)),
            new WriteRow("byte", (byte) 7, "TINYINT", Types.TINYINT, INTEGRAL,
                    ResultSet::getByte, Expected.of((byte) 7)),
            new WriteRow("short", (short) 7, "SMALLINT", Types.SMALLINT, INTEGRAL,
                    ResultSet::getShort, Expected.of((short) 7)),
            new WriteRow("int", 7, "INTEGER", Types.INTEGER, INTEGRAL,
                    ResultSet::getInt, Expected.of(7)),
            new WriteRow("long", 7L, "BIGINT", Types.BIGINT, INTEGRAL,
                    ResultSet::getLong, Expected.of(7L)),
            new WriteRow("float", 7.0f, "REAL", Types.REAL, REAL,
                    ResultSet::getFloat, Expected.of(7.0f)),
            new WriteRow("double", 7.0d, "DOUBLE", Types.DOUBLE, REAL,
                    ResultSet::getDouble, Expected.of(7.0d)),
            new WriteRow("byte[]", new byte[] {1, 2, 3}, "VARBINARY", Types.VARBINARY, BLOB,
                    ResultSet::getBytes, Expected.of(new byte[] {1, 2, 3})),
            new WriteRow("java.sql.Date", Date.valueOf("2024-01-02"), "DATE", Types.DATE, TEXT,
                    ResultSet::getDate, Expected.of(Date.valueOf("2024-01-02"))),
            new WriteRow("java.sql.Time", Time.valueOf("03:04:05"), "TIME", Types.TIME, TEXT,
                    ResultSet::getTime, Expected.of(Time.valueOf("03:04:05"))),
            new WriteRow("java.sql.Timestamp", Timestamp.valueOf("2024-01-02 03:04:05"),
                    "TIMESTAMP", Types.TIMESTAMP, TEXT, ResultSet::getTimestamp,
                    Expected.of(Timestamp.valueOf("2024-01-02 03:04:05"))),
            new WriteRow("java.sql.Clob", clob("abc"), "CLOB", Types.CLOB, TEXT,
                    Jdbc4AppendixBTypeMappingIT::clobText, Expected.of("abc")),
            new WriteRow("java.sql.Blob", blob(new byte[] {1, 2, 3}), "BLOB", Types.BLOB, BLOB,
                    Jdbc4AppendixBTypeMappingIT::blobBytes, Expected.of(new byte[] {1, 2, 3})),
            new WriteRow("java.sql.Array", stub(Array.class), "ARRAY", Types.ARRAY, UNSPECIFIED,
                    ResultSet::getArray, Expected.any(Array.class)),
            new WriteRow("java.sql.Struct", stub(Struct.class), "STRUCT", Types.STRUCT, UNSPECIFIED,
                    (rs, i) -> rs.getObject(i), Expected.any(Struct.class)),
            new WriteRow("java.sql.Ref", stub(Ref.class), "REF", Types.REF, UNSPECIFIED,
                    ResultSet::getRef, Expected.any(Ref.class)),
            new WriteRow("java.net.URL", url("https://example.com"), "DATALINK", Types.DATALINK,
                    TEXT, ResultSet::getURL, Expected.any(URL.class)),
            new WriteRow("Java class", new Marker("abc"), "JAVA_OBJECT", Types.JAVA_OBJECT,
                    UNSPECIFIED, (rs, i) -> rs.getObject(i), Expected.of(new Marker("abc"))),
            new WriteRow("java.sql.RowId", stub(RowId.class), "ROWID", Types.ROWID, UNSPECIFIED,
                    ResultSet::getRowId, Expected.any(RowId.class)),
            new WriteRow("java.sql.NClob", nclob("abc"), "NCLOB", Types.NCLOB, TEXT,
                    ResultSet::getNClob, Expected.any(NClob.class)),
            new WriteRow("java.sql.SQLXML", stub(SQLXML.class), "SQLXML", Types.SQLXML, UNSPECIFIED,
                    ResultSet::getSQLXML, Expected.any(SQLXML.class)));

    @ParameterizedTest(name = "B-2 {0} -> {2}")
    @MethodSource("b2Rows")
    void b2StandardSettersMapJavaTypeToJdbcType(WriteRow row) throws SQLException {
        String table = table("b2");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (v \"" + row.targetName() + "\")");
            try (PreparedStatement insert =
                    connection.prepareStatement("INSERT INTO " + table + " (v) VALUES (?)")) {
                try {
                    bindB2(insert, row);
                } catch (SQLException e) {
                    throw new AssertionError(
                            "B-2: " + row.name() + " -> " + row.targetName() + " bind failed: " + e,
                            e);
                }
                insert.executeUpdate();
            }
            verifyRoundTrip(connection, table, row, "B-2");
        }
    }

    static Stream<WriteRow> b2Rows() {
        return B2_ROWS.stream();
    }

    private static void bindB2(PreparedStatement statement, WriteRow row) throws SQLException {
        switch (row.name()) {
            case "String" -> statement.setString(1, (String) row.value());
            case "BigDecimal" -> statement.setBigDecimal(1, (BigDecimal) row.value());
            case "boolean" -> statement.setBoolean(1, (boolean) row.value());
            case "byte" -> statement.setByte(1, (byte) row.value());
            case "short" -> statement.setShort(1, (short) row.value());
            case "int" -> statement.setInt(1, (int) row.value());
            case "long" -> statement.setLong(1, (long) row.value());
            case "float" -> statement.setFloat(1, (float) row.value());
            case "double" -> statement.setDouble(1, (double) row.value());
            case "byte[]" -> statement.setBytes(1, (byte[]) row.value());
            case "java.sql.Date" -> statement.setDate(1, (Date) row.value());
            case "java.sql.Time" -> statement.setTime(1, (Time) row.value());
            case "java.sql.Timestamp" -> statement.setTimestamp(1, (Timestamp) row.value());
            case "java.sql.Clob" -> statement.setClob(1, (Clob) row.value());
            case "java.sql.Blob" -> statement.setBlob(1, (Blob) row.value());
            case "java.sql.Array" -> statement.setArray(1, (Array) row.value());
            case "java.sql.Struct", "Java class" -> statement.setObject(1, row.value());
            case "java.sql.Ref" -> statement.setRef(1, (Ref) row.value());
            case "java.net.URL" -> statement.setURL(1, (URL) row.value());
            case "java.sql.RowId" -> statement.setRowId(1, (RowId) row.value());
            case "java.sql.NClob" -> statement.setNClob(1, (NClob) row.value());
            case "java.sql.SQLXML" -> statement.setSQLXML(1, (SQLXML) row.value());
            default -> throw new IllegalArgumentException(row.name());
        }
    }

    // =====================================================================
    // Table B-4: setObject without a target type maps a Java object type.
    // =====================================================================

    private static final Calendar CALENDAR = calendar2024();
    private static final java.util.Date UTIL_DATE = new java.util.Date(CALENDAR.getTimeInMillis());

    private static Calendar calendar2024() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(2024, Calendar.JANUARY, 2, 3, 4, 5);
        return calendar;
    }

    private static final List<WriteRow> B4_ROWS = List.of(
            new WriteRow("String", "abc", "VARCHAR", Types.VARCHAR, TEXT,
                    ResultSet::getString, Expected.of("abc")),
            new WriteRow("BigDecimal", new BigDecimal("7"), "NUMERIC", Types.NUMERIC, NUMERIC,
                    ResultSet::getBigDecimal, Expected.of(new BigDecimal("7"))),
            new WriteRow("Boolean", true, "BOOLEAN", Types.BOOLEAN, INTEGRAL,
                    ResultSet::getBoolean, Expected.of(true)),
            new WriteRow("Byte", (byte) 7, "TINYINT", Types.TINYINT, INTEGRAL,
                    ResultSet::getByte, Expected.of((byte) 7)),
            new WriteRow("Short", (short) 7, "SMALLINT", Types.SMALLINT, INTEGRAL,
                    ResultSet::getShort, Expected.of((short) 7)),
            new WriteRow("Integer", 7, "INTEGER", Types.INTEGER, INTEGRAL,
                    ResultSet::getInt, Expected.of(7)),
            new WriteRow("Long", 7L, "BIGINT", Types.BIGINT, INTEGRAL,
                    ResultSet::getLong, Expected.of(7L)),
            new WriteRow("Float", 7.0f, "REAL", Types.REAL, REAL,
                    ResultSet::getFloat, Expected.of(7.0f)),
            new WriteRow("Double", 7.0d, "DOUBLE", Types.DOUBLE, REAL,
                    ResultSet::getDouble, Expected.of(7.0d)),
            new WriteRow("byte[]", new byte[] {1, 2, 3}, "VARBINARY", Types.VARBINARY, BLOB,
                    ResultSet::getBytes, Expected.of(new byte[] {1, 2, 3})),
            new WriteRow("java.math.BigInteger", BigInteger.valueOf(7), "BIGINT", Types.BIGINT,
                    INTEGRAL, ResultSet::getLong, Expected.of(7L)),
            new WriteRow("java.sql.Date", Date.valueOf("2024-01-02"), "DATE", Types.DATE, TEXT,
                    ResultSet::getDate, Expected.of(Date.valueOf("2024-01-02"))),
            new WriteRow("java.sql.Time", Time.valueOf("03:04:05"), "TIME", Types.TIME, TEXT,
                    ResultSet::getTime, Expected.of(Time.valueOf("03:04:05"))),
            new WriteRow("java.sql.Timestamp", Timestamp.valueOf("2024-01-02 03:04:05"),
                    "TIMESTAMP", Types.TIMESTAMP, TEXT, ResultSet::getTimestamp,
                    Expected.of(Timestamp.valueOf("2024-01-02 03:04:05"))),
            new WriteRow("java.sql.Clob", clob("abc"), "CLOB", Types.CLOB, TEXT,
                    Jdbc4AppendixBTypeMappingIT::clobText, Expected.of("abc")),
            new WriteRow("java.sql.Blob", blob(new byte[] {1, 2, 3}), "BLOB", Types.BLOB, BLOB,
                    Jdbc4AppendixBTypeMappingIT::blobBytes, Expected.of(new byte[] {1, 2, 3})),
            new WriteRow("java.sql.Array", stub(Array.class), "ARRAY", Types.ARRAY, UNSPECIFIED,
                    ResultSet::getArray, Expected.any(Array.class)),
            new WriteRow("java.sql.Struct", stub(Struct.class), "STRUCT", Types.STRUCT, UNSPECIFIED,
                    (rs, i) -> rs.getObject(i), Expected.any(Struct.class)),
            new WriteRow("java.sql.Ref", stub(Ref.class), "REF", Types.REF, UNSPECIFIED,
                    ResultSet::getRef, Expected.any(Ref.class)),
            new WriteRow("java.net.URL", url("https://example.com"), "DATALINK", Types.DATALINK,
                    TEXT, ResultSet::getURL, Expected.any(URL.class)),
            new WriteRow("Java class", new Marker("abc"), "JAVA_OBJECT", Types.JAVA_OBJECT,
                    UNSPECIFIED, (rs, i) -> rs.getObject(i), Expected.of(new Marker("abc"))),
            new WriteRow("java.sql.RowId", stub(RowId.class), "ROWID", Types.ROWID, UNSPECIFIED,
                    ResultSet::getRowId, Expected.any(RowId.class)),
            new WriteRow("java.sql.NClob", nclob("abc"), "NCLOB", Types.NCLOB, TEXT,
                    ResultSet::getNClob, Expected.any(NClob.class)),
            new WriteRow("java.sql.SQLXML", stub(SQLXML.class), "SQLXML", Types.SQLXML, UNSPECIFIED,
                    ResultSet::getSQLXML, Expected.any(SQLXML.class)),
            new WriteRow("java.util.Calendar", CALENDAR, "TIMESTAMP", Types.TIMESTAMP, TEXT,
                    ResultSet::getTimestamp,
                    Expected.of(Timestamp.valueOf(localDateTime(CALENDAR.toInstant())))),
            new WriteRow("java.util.Date", UTIL_DATE, "TIMESTAMP", Types.TIMESTAMP, TEXT,
                    ResultSet::getTimestamp,
                    Expected.of(Timestamp.valueOf(localDateTime(UTIL_DATE.toInstant())))),
            new WriteRow("java.time.LocalDate", LocalDate.of(2024, 1, 2), "DATE", Types.DATE,
                    TEXT, ResultSet::getDate, Expected.of(Date.valueOf("2024-01-02"))),
            new WriteRow("java.time.LocalTime", LocalTime.of(3, 4, 5), "TIME", Types.TIME, TEXT,
                    ResultSet::getTime, Expected.of(Time.valueOf("03:04:05"))),
            new WriteRow("java.time.LocalDateTime", LocalDateTime.of(2024, 1, 2, 3, 4, 5),
                    "TIMESTAMP", Types.TIMESTAMP, TEXT, ResultSet::getTimestamp,
                    Expected.of(Timestamp.valueOf("2024-01-02 03:04:05"))),
            new WriteRow("java.time.OffsetTime", OffsetTime.of(3, 4, 5, 0, ZoneOffset.UTC),
                    "TIME_WITH_TIMEZONE", Types.TIME_WITH_TIMEZONE, TEXT,
                    (rs, i) -> rs.getObject(i, OffsetTime.class),
                    Expected.of(OffsetTime.of(3, 4, 5, 0, ZoneOffset.UTC))),
            new WriteRow("java.time.OffsetDateTime",
                    OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC),
                    "TIMESTAMP_WITH_TIMEZONE", Types.TIMESTAMP_WITH_TIMEZONE, TEXT,
                    (rs, i) -> rs.getObject(i, OffsetDateTime.class),
                    Expected.of(OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC))));

    @ParameterizedTest(name = "B-4 {0} -> {2}")
    @MethodSource("b4Rows")
    void b4SetObjectMapsJavaObjectTypeToJdbcType(WriteRow row) throws SQLException {
        String table = table("b4");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (v \"" + row.targetName() + "\")");
            try (PreparedStatement insert =
                    connection.prepareStatement("INSERT INTO " + table + " (v) VALUES (?)")) {
                insert.setObject(1, row.value());
                insert.executeUpdate();
            }
            verifyRoundTrip(connection, table, row, "B-4");
        }
    }

    static Stream<WriteRow> b4Rows() {
        return B4_ROWS.stream();
    }

    // =====================================================================
    // Table B-5: setObject with a target type converts between Java and JDBC types.
    // =====================================================================

    record Target(String name, int sqlType) {}

    private static final List<Target> TARGETS = List.of(
            target("TINYINT", Types.TINYINT),
            target("SMALLINT", Types.SMALLINT),
            target("INTEGER", Types.INTEGER),
            target("BIGINT", Types.BIGINT),
            target("REAL", Types.REAL),
            target("FLOAT", Types.FLOAT),
            target("DOUBLE", Types.DOUBLE),
            target("DECIMAL", Types.DECIMAL),
            target("NUMERIC", Types.NUMERIC),
            target("BIT", Types.BIT),
            target("BOOLEAN", Types.BOOLEAN),
            target("CHAR", Types.CHAR),
            target("VARCHAR", Types.VARCHAR),
            target("LONGVARCHAR", Types.LONGVARCHAR),
            target("BINARY", Types.BINARY),
            target("VARBINARY", Types.VARBINARY),
            target("LONGVARBINARY", Types.LONGVARBINARY),
            target("DATE", Types.DATE),
            target("TIME", Types.TIME),
            target("TIMESTAMP", Types.TIMESTAMP),
            target("NCHAR", Types.NCHAR),
            target("NVARCHAR", Types.NVARCHAR),
            target("LONGNVARCHAR", Types.LONGNVARCHAR),
            target("ARRAY", Types.ARRAY),
            target("BLOB", Types.BLOB),
            target("CLOB", Types.CLOB),
            target("STRUCT", Types.STRUCT),
            target("REF", Types.REF),
            target("DATALINK", Types.DATALINK),
            target("JAVA_OBJECT", Types.JAVA_OBJECT),
            target("ROWID", Types.ROWID),
            target("NCLOB", Types.NCLOB),
            target("SQLXML", Types.SQLXML),
            target("TIME_WITH_TIMEZONE", Types.TIME_WITH_TIMEZONE),
            target("TIMESTAMP_WITH_TIMEZONE", Types.TIMESTAMP_WITH_TIMEZONE));

    private static Target target(String name, int sqlType) {
        return new Target(name, sqlType);
    }

    record JavaRow(String name, Object value, Set<Integer> targets) {}

    private static final Set<Integer> NUMERIC_TEXT_TARGETS = Set.of(
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL, Types.FLOAT,
            Types.DOUBLE, Types.DECIMAL, Types.NUMERIC, Types.BIT, Types.BOOLEAN, Types.CHAR,
            Types.VARCHAR, Types.LONGVARCHAR);

    private static Set<Integer> set(int... values) {
        return Arrays.stream(values).boxed().collect(Collectors.toUnmodifiableSet());
    }

    private static Set<Integer> union(Set<Integer> base, int... extra) {
        Set<Integer> result = new java.util.HashSet<>(base);
        for (int value : extra) {
            result.add(value);
        }
        return Set.copyOf(result);
    }

    private static final List<JavaRow> B5_ROWS = List.of(
            new JavaRow("String", "42", union(NUMERIC_TEXT_TARGETS, Types.BINARY, Types.VARBINARY,
                    Types.LONGVARBINARY, Types.DATE, Types.TIME, Types.TIMESTAMP, Types.NCHAR,
                    Types.NVARCHAR, Types.LONGNVARCHAR)),
            new JavaRow("BigDecimal", new BigDecimal("7"), NUMERIC_TEXT_TARGETS),
            new JavaRow("Boolean", Boolean.TRUE, NUMERIC_TEXT_TARGETS),
            new JavaRow("Byte", (byte) 7, NUMERIC_TEXT_TARGETS),
            new JavaRow("Short", (short) 7, NUMERIC_TEXT_TARGETS),
            new JavaRow("Integer", 7, NUMERIC_TEXT_TARGETS),
            new JavaRow("Long", 7L, NUMERIC_TEXT_TARGETS),
            new JavaRow("Float", 7.0f, NUMERIC_TEXT_TARGETS),
            new JavaRow("Double", 7.0d, NUMERIC_TEXT_TARGETS),
            new JavaRow("byte[]", new byte[] {1, 2, 3},
                    set(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY)),
            new JavaRow("BigInteger", BigInteger.valueOf(7),
                    set(Types.BIGINT, Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR)),
            new JavaRow("java.sql.Date", Date.valueOf("2024-01-02"),
                    set(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.DATE, Types.TIMESTAMP)),
            new JavaRow("java.sql.Time", Time.valueOf("03:04:05"),
                    set(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.TIME, Types.TIMESTAMP)),
            new JavaRow("java.sql.Timestamp", Timestamp.valueOf("2024-01-02 03:04:05"),
                    set(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.DATE, Types.TIME,
                            Types.TIMESTAMP)),
            new JavaRow("java.sql.Array", stub(Array.class), set(Types.ARRAY)),
            new JavaRow("java.sql.Blob", blob(new byte[] {1, 2, 3}), set(Types.BLOB)),
            new JavaRow("java.sql.Clob", clob("abc"), set(Types.CLOB)),
            new JavaRow("java.sql.Struct", stub(Struct.class), set(Types.STRUCT)),
            new JavaRow("java.sql.Ref", stub(Ref.class), set(Types.REF)),
            new JavaRow("java.net.URL", url("https://example.com"), set(Types.DATALINK)),
            new JavaRow("Java class", new Marker("abc"), set(Types.JAVA_OBJECT)),
            new JavaRow("java.sql.RowId", stub(RowId.class), set(Types.ROWID)),
            new JavaRow("java.sql.NClob", nclob("abc"), set(Types.NCLOB)),
            new JavaRow("java.sql.SQLXML", stub(SQLXML.class), set(Types.SQLXML)),
            new JavaRow("java.util.Calendar", CALENDAR,
                    union(set(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.DATE, Types.TIME,
                            Types.TIMESTAMP), Types.ARRAY)),
            new JavaRow("java.util.Date", UTIL_DATE,
                    union(set(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.DATE, Types.TIME,
                            Types.TIMESTAMP), Types.ARRAY)),
            new JavaRow("java.time.LocalDate", LocalDate.of(2024, 1, 2),
                    set(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.DATE)),
            new JavaRow("java.time.LocalTime", LocalTime.of(3, 4, 5),
                    set(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.TIME)),
            new JavaRow("java.time.LocalDateTime", LocalDateTime.of(2024, 1, 2, 3, 4, 5),
                    set(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.DATE, Types.TIME,
                            Types.TIMESTAMP)),
            new JavaRow("java.time.OffsetTime", OffsetTime.of(3, 4, 5, 0, ZoneOffset.UTC),
                    set(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.TIME_WITH_TIMEZONE)),
            new JavaRow("java.time.OffsetDateTime",
                    OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC),
                    set(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.TIME_WITH_TIMEZONE,
                            Types.TIMESTAMP_WITH_TIMEZONE)));

    @ParameterizedTest(name = "B-5 {0} to every listed target")
    @MethodSource("b5Rows")
    void b5SetObjectConvertsToExplicitTargetType(JavaRow row) throws SQLException {
        String table = table("b5");
        String columns = TARGETS.stream()
                .map(target -> "\"" + target.name() + "\" \"" + target.name() + "\"")
                .collect(Collectors.joining(", "));
        String placeholders = TARGETS.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        String select = TARGETS.stream()
                .map(target -> "typeof(\"" + target.name() + "\"), \"" + target.name() + "\"")
                .collect(Collectors.joining(", "));

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id INTEGER, " + columns + ")");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + table + " (id, " + String.join(", ", columnNames()) + ") VALUES (1, "
                            + placeholders + ")")) {
                int parameter = 1;
                for (Target target : TARGETS) {
                    if (row.targets().contains(target.sqlType())) {
                        insert.setObject(parameter++, sourceFor(row, target), target.sqlType());
                    } else {
                        insert.setNull(parameter++, target.sqlType());
                    }
                }
                insert.executeUpdate();
            }

            try (Statement query = connection.createStatement();
                    ResultSet resultSet =
                            query.executeQuery("SELECT " + select + " FROM " + table + " WHERE id = 1")) {
                assertThat(resultSet.next()).isTrue();
                List<Executable> checks = new ArrayList<>();
                int targetIndex = 0;
                for (Target target : TARGETS) {
                    int typeofColumn = targetIndex * 2 + 1;
                    int valueColumn = typeofColumn + 1;
                    targetIndex++;
                    if (!row.targets().contains(target.sqlType())) {
                        continue;
                    }
                    checks.add(() -> {
                        Set<String> storage = storageOf(target.name());
                        if (!storage.isEmpty()) {
                            assertThat(resultSet.getString(typeofColumn))
                                    .as("B-5: %s -> %s storage class", row.name(), target.name())
                                    .isIn(storage);
                        }
                        Object actual;
                        try {
                            actual = readFor(target).read(resultSet, valueColumn);
                        } catch (SQLException e) {
                            throw new AssertionError(
                                    "B-5: " + row.name() + " -> " + target.name()
                                            + " read failed: " + e,
                                    e);
                        }
                        assertExpected(actual, expectedFor(row, target), "B-5", row.name(), target.name());
                    });
                }
                assertAll("B-5 " + row.name(), checks);
            }
        }
    }

    static Stream<JavaRow> b5Rows() {
        return B5_ROWS.stream();
    }

    private static List<String> columnNames() {
        return TARGETS.stream().map(target -> "\"" + target.name() + "\"").toList();
    }

    // =====================================================================
    // Round-trip helpers.
    // =====================================================================

    private static void verifyRoundTrip(
            Connection connection, String table, WriteRow row, String tableName) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet resultSet = query.executeQuery("SELECT typeof(v), v FROM " + table)) {
            assertThat(resultSet.next()).isTrue();
            if (!row.storage().isEmpty()) {
                assertThat(resultSet.getString(1))
                        .as("%s: %s -> %s storage class", tableName, row.name(), row.targetName())
                        .isIn(row.storage());
            }
            Object actual;
            try {
                actual = row.reader().read(resultSet, 2);
            } catch (SQLException e) {
                throw new AssertionError(
                        tableName + ": " + row.name() + " -> " + row.targetName() + " read failed: " + e,
                        e);
            }
            assertExpected(actual, row.expected(), tableName, row.name(), row.targetName());
        }
    }

    private static void assertExpected(
            Object actual, Expected expected, String table, String row, String target) {
        if (expected.anyType() != null) {
            assertThat(actual)
                    .as("%s: %s -> %s must be a %s", table, row, target, expected.anyType())
                    .isInstanceOf(expected.anyType());
            return;
        }
        Object value = expected.value();
        if (value instanceof BigDecimal decimal) {
            assertThat((BigDecimal) actual)
                    .as("%s: %s -> %s", table, row, target)
                    .isEqualByComparingTo(decimal);
            return;
        }
        if (value != null && value.getClass().isArray()) {
            assertThat(actual).as("%s: %s -> %s", table, row, target).isEqualTo(value);
            return;
        }
        assertThat(actual).as("%s: %s -> %s", table, row, target).isEqualTo(value);
    }

    private static Set<String> storageOf(String targetName) {
        return switch (targetName) {
            case "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "BIT", "BOOLEAN" -> INTEGRAL;
            case "REAL", "FLOAT", "DOUBLE" -> REAL;
            case "DECIMAL", "NUMERIC" -> NUMERIC;
            case "CHAR", "VARCHAR", "LONGVARCHAR", "NCHAR", "NVARCHAR", "LONGNVARCHAR", "DATE",
                    "TIME", "TIMESTAMP", "CLOB", "NCLOB", "DATALINK", "TIME_WITH_TIMEZONE",
                    "TIMESTAMP_WITH_TIMEZONE" ->
                    TEXT;
            case "BINARY", "VARBINARY", "LONGVARBINARY", "BLOB" -> BLOB;
            default -> UNSPECIFIED;
        };
    }

    private static SqlRead readFor(Target target) {
        return switch (target.name()) {
            case "TINYINT" -> ResultSet::getByte;
            case "SMALLINT" -> ResultSet::getShort;
            case "INTEGER" -> ResultSet::getInt;
            case "BIGINT" -> ResultSet::getLong;
            case "REAL" -> ResultSet::getFloat;
            case "FLOAT", "DOUBLE" -> ResultSet::getDouble;
            case "DECIMAL", "NUMERIC" -> ResultSet::getBigDecimal;
            case "BIT", "BOOLEAN" -> ResultSet::getBoolean;
            case "CHAR", "VARCHAR", "LONGVARCHAR", "NCHAR", "NVARCHAR", "LONGNVARCHAR" ->
                    ResultSet::getString;
            case "BINARY", "VARBINARY", "LONGVARBINARY" -> ResultSet::getBytes;
            case "DATE" -> ResultSet::getDate;
            case "TIME" -> ResultSet::getTime;
            case "TIMESTAMP" -> ResultSet::getTimestamp;
            case "CLOB" -> Jdbc4AppendixBTypeMappingIT::clobText;
            case "BLOB" -> Jdbc4AppendixBTypeMappingIT::blobBytes;
            case "NCLOB" -> (rs, i) -> {
                NClob nclob = rs.getNClob(i);
                return nclob == null ? null : nclob.getSubString(1, (int) nclob.length());
            };
            case "ARRAY" -> ResultSet::getArray;
            case "STRUCT" -> (rs, i) -> rs.getObject(i);
            case "REF" -> ResultSet::getRef;
            case "DATALINK" -> ResultSet::getURL;
            case "JAVA_OBJECT" -> (rs, i) -> rs.getObject(i);
            case "ROWID" -> ResultSet::getRowId;
            case "SQLXML" -> ResultSet::getSQLXML;
            case "TIME_WITH_TIMEZONE" -> (rs, i) -> rs.getObject(i, OffsetTime.class);
            case "TIMESTAMP_WITH_TIMEZONE" -> (rs, i) -> rs.getObject(i, OffsetDateTime.class);
            default -> throw new IllegalArgumentException(target.name());
        };
    }

    private static Object sourceFor(JavaRow row, Target target) {
        if (row.value() instanceof String) {
            return switch (target.name()) {
                case "DATE" -> "2024-01-02";
                case "TIME" -> "03:04:05";
                case "TIMESTAMP" -> "2024-01-02 03:04:05";
                case "BINARY", "VARBINARY", "LONGVARBINARY" -> "abc";
                case "BIT", "BOOLEAN" -> "true";
                default -> "42";
            };
        }
        return row.value();
    }

    private static Expected expectedFor(JavaRow row, Target target) {
        Object source = sourceFor(row, target);
        return switch (target.name()) {
            case "TINYINT" -> Expected.of((byte) number(source));
            case "SMALLINT" -> Expected.of((short) number(source));
            case "INTEGER" -> Expected.of((int) number(source));
            case "BIGINT" -> Expected.of((long) number(source));
            case "REAL" -> Expected.of((float) number(source));
            case "FLOAT", "DOUBLE" -> Expected.of(number(source));
            case "DECIMAL", "NUMERIC" -> Expected.of(BigDecimal.valueOf(number(source)));
            case "BIT", "BOOLEAN" -> Expected.of(number(source) != 0);
            case "CHAR", "VARCHAR", "LONGVARCHAR", "NCHAR", "NVARCHAR", "LONGNVARCHAR" ->
                    textExpected(source);
            case "DATE" -> dateExpected(source);
            case "TIME" -> timeExpected(source);
            case "TIMESTAMP" -> timestampExpected(source);
            case "CLOB" -> Expected.of(clobTextValue(source));
            case "NCLOB" -> Expected.any(NClob.class);
            case "BLOB" -> Expected.of(bytesForm(source));
            case "BINARY", "VARBINARY", "LONGVARBINARY" -> Expected.of(bytesForm(source));
            case "DATALINK" -> Expected.any(URL.class);
            case "ARRAY" -> Expected.any(Array.class);
            case "STRUCT" -> Expected.any(Struct.class);
            case "REF" -> Expected.any(Ref.class);
            case "JAVA_OBJECT" -> Expected.of(source);
            case "ROWID" -> Expected.any(RowId.class);
            case "SQLXML" -> Expected.any(SQLXML.class);
            case "TIME_WITH_TIMEZONE" -> offsetTimeExpected(source);
            case "TIMESTAMP_WITH_TIMEZONE" -> offsetDateTimeExpected(source);
            default -> throw new IllegalArgumentException(target.name());
        };
    }

    private static Expected textExpected(Object source) {
        // The appendix requires the Java type but leaves the text representation open for
        // Calendar, java.util.Date and Boolean.
        if (source instanceof Calendar
                || source.getClass() == java.util.Date.class
                || source instanceof Boolean) {
            return Expected.any(String.class);
        }
        return Expected.of(textForm(source));
    }

    private static Expected dateExpected(Object source) {
        if (source instanceof Date date) {
            return Expected.of(date);
        }
        if (source instanceof Timestamp timestamp) {
            return Expected.of(Date.valueOf(timestamp.toLocalDateTime().toLocalDate()));
        }
        if (source instanceof LocalDate localDate) {
            return Expected.of(Date.valueOf(localDate));
        }
        if (source instanceof LocalDateTime localDateTime) {
            return Expected.of(Date.valueOf(localDateTime.toLocalDate()));
        }
        if (source instanceof java.util.Date date) {
            return Expected.of(Date.valueOf(LocalDate.ofInstant(date.toInstant(), ZoneId.systemDefault())));
        }
        if (source instanceof Calendar calendar) {
            return Expected.of(Date.valueOf(LocalDate.ofInstant(calendar.toInstant(), ZoneId.systemDefault())));
        }
        if (source instanceof String text) {
            return Expected.of(Date.valueOf(LocalDate.parse(text.substring(0, 10))));
        }
        throw new IllegalArgumentException(source.getClass().getName());
    }

    private static Expected timeExpected(Object source) {
        if (source instanceof Time time) {
            return Expected.of(time);
        }
        if (source instanceof Timestamp timestamp) {
            return Expected.of(Time.valueOf(timestamp.toLocalDateTime().toLocalTime()));
        }
        if (source instanceof LocalTime localTime) {
            return Expected.of(Time.valueOf(localTime));
        }
        if (source instanceof LocalDateTime localDateTime) {
            return Expected.of(Time.valueOf(localDateTime.toLocalTime()));
        }
        if (source instanceof java.util.Date date) {
            return Expected.of(Time.valueOf(LocalTime.ofInstant(date.toInstant(), ZoneId.systemDefault())));
        }
        if (source instanceof Calendar calendar) {
            return Expected.of(Time.valueOf(LocalTime.ofInstant(calendar.toInstant(), ZoneId.systemDefault())));
        }
        if (source instanceof String text) {
            return Expected.of(Time.valueOf(LocalTime.parse(text.substring(text.length() - 8))));
        }
        throw new IllegalArgumentException(source.getClass().getName());
    }

    private static Expected timestampExpected(Object source) {
        if (source instanceof Timestamp timestamp) {
            return Expected.of(timestamp);
        }
        if (source instanceof Date date) {
            return Expected.of(Timestamp.valueOf(date.toLocalDate().atStartOfDay()));
        }
        if (source instanceof LocalDate localDate) {
            return Expected.of(Timestamp.valueOf(localDate.atStartOfDay()));
        }
        if (source instanceof LocalDateTime localDateTime) {
            return Expected.of(Timestamp.valueOf(localDateTime));
        }
        // The date part of a Time to TIMESTAMP conversion is implementation defined.
        if (source instanceof Time) {
            return Expected.any(Timestamp.class);
        }
        if (source instanceof java.util.Date date) {
            return Expected.of(Timestamp.valueOf(localDateTime(date.toInstant())));
        }
        if (source instanceof Calendar calendar) {
            return Expected.of(Timestamp.valueOf(localDateTime(calendar.toInstant())));
        }
        if (source instanceof String text) {
            return Expected.of(Timestamp.valueOf(text.replace('T', ' ')));
        }
        throw new IllegalArgumentException(source.getClass().getName());
    }

    private static Expected offsetTimeExpected(Object source) {
        if (source instanceof OffsetTime offsetTime) {
            return Expected.of(offsetTime);
        }
        if (source instanceof OffsetDateTime offsetDateTime) {
            return Expected.of(offsetDateTime.toOffsetTime());
        }
        if (source instanceof String text) {
            return Expected.of(OffsetTime.parse(text));
        }
        throw new IllegalArgumentException(source.getClass().getName());
    }

    private static Expected offsetDateTimeExpected(Object source) {
        if (source instanceof OffsetDateTime offsetDateTime) {
            return Expected.of(offsetDateTime);
        }
        throw new IllegalArgumentException(source.getClass().getName());
    }

    private static String clobTextValue(Object source) {
        if (source instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return textForm(source);
    }

    private static String textForm(Object source) {
        return switch (source) {
            case String text -> text;
            case BigDecimal decimal -> decimal.toPlainString();
            case BigInteger integer -> integer.toString();
            case Boolean bool -> bool.toString();
            case Number number -> String.valueOf(number);
            case Date date -> date.toString();
            case Time time -> time.toString();
            case Timestamp timestamp -> timestamp.toString();
            case LocalDate localDate -> localDate.toString();
            case LocalTime localTime -> localTime.toString();
            case LocalDateTime localDateTime -> localDateTime.toString();
            case OffsetTime offsetTime -> offsetTime.toString();
            case OffsetDateTime offsetDateTime -> offsetDateTime.toString();
            default -> throw new IllegalArgumentException(source.getClass().getName());
        };
    }

    private static byte[] bytesForm(Object source) {
        if (source instanceof byte[] bytes) {
            return bytes;
        }
        if (source instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        if (source instanceof Blob blob) {
            try {
                return blob.getBytes(1, (int) blob.length());
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException(source.getClass().getName());
    }

    private static double number(Object source) {
        if (source instanceof Number number) {
            return number.doubleValue();
        }
        if (source instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (source instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return 1;
            }
            if ("false".equalsIgnoreCase(text)) {
                return 0;
            }
            return Double.parseDouble(text);
        }
        throw new IllegalArgumentException(source.getClass().getName());
    }

    private static LocalDateTime localDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static String clobText(ResultSet resultSet, int column) throws SQLException {
        Clob clob = resultSet.getClob(column);
        return clob == null ? null : clob.getSubString(1, (int) clob.length());
    }

    private static byte[] blobBytes(ResultSet resultSet, int column) throws SQLException {
        Blob blob = resultSet.getBlob(column);
        if (blob == null) {
            return null;
        }
        try {
            return blob.getBinaryStream().readAllBytes();
        } catch (java.io.IOException e) {
            throw new SQLException(e);
        }
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "boolean" -> Boolean.class;
            case "char" -> Character.class;
            default -> throw new IllegalArgumentException(type.getName());
        };
    }

    private record Marker(String value) {}

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "stub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Blob blob(byte[] data) {
        return (Blob) Proxy.newProxyInstance(
                Blob.class.getClassLoader(),
                new Class<?>[] {Blob.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "blob";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "length" -> (long) data.length;
                    case "getBytes" -> data;
                    case "getBinaryStream" -> new ByteArrayInputStream(data);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Clob clob(String text) {
        return (Clob) Proxy.newProxyInstance(
                Clob.class.getClassLoader(),
                new Class<?>[] {Clob.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "clob";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "length" -> (long) text.length();
                    case "getSubString" -> text;
                    case "getCharacterStream" -> new StringReader(text);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static NClob nclob(String text) {
        return (NClob) Proxy.newProxyInstance(
                NClob.class.getClassLoader(),
                new Class<?>[] {NClob.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> text;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "length" -> (long) text.length();
                    case "getSubString" -> text;
                    case "getCharacterStream" -> new StringReader(text);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static URL url(String value) {
        try {
            return URI.create(value).toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException(value, e);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        return switch (type.getName()) {
            case "boolean" -> false;
            case "char" -> '\0';
            case "byte" -> (byte) 0;
            case "short" -> (short) 0;
            case "int" -> 0;
            case "long" -> 0L;
            case "float" -> 0.0f;
            case "double" -> 0.0d;
            default -> null;
        };
    }
}
