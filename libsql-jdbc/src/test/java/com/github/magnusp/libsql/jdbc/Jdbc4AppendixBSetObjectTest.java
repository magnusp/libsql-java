package com.github.magnusp.libsql.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * JDBC 4.3 Appendix B, Table B-5, negative half: a target JDBC type the table does not list for a
 * Java object type must not be accepted.
 *
 * <p>Table B-5 says which JDBC types {@code setObject} and {@code setNull} "may" be given for each
 * Java type. This suite takes the strict reading: a listed pair must be accepted (covered by the
 * integration suite, which performs the round trip), and an unlisted pair must be rejected with a
 * {@link SQLException}. Any {@link SQLException} is a pass because the specification does not
 * prescribe the exception type.
 *
 * <p>No connection is needed: binding happens locally before the statement is executed, so a
 * statement over a {@code null} connection is enough to observe whether the target type is
 * validated.
 */
class Jdbc4AppendixBSetObjectTest {

    record Target(String name, int sqlType) {}

    private static final List<Target> TARGETS = List.of(
            new Target("TINYINT", Types.TINYINT),
            new Target("SMALLINT", Types.SMALLINT),
            new Target("INTEGER", Types.INTEGER),
            new Target("BIGINT", Types.BIGINT),
            new Target("REAL", Types.REAL),
            new Target("FLOAT", Types.FLOAT),
            new Target("DOUBLE", Types.DOUBLE),
            new Target("DECIMAL", Types.DECIMAL),
            new Target("NUMERIC", Types.NUMERIC),
            new Target("BIT", Types.BIT),
            new Target("BOOLEAN", Types.BOOLEAN),
            new Target("CHAR", Types.CHAR),
            new Target("VARCHAR", Types.VARCHAR),
            new Target("LONGVARCHAR", Types.LONGVARCHAR),
            new Target("BINARY", Types.BINARY),
            new Target("VARBINARY", Types.VARBINARY),
            new Target("LONGVARBINARY", Types.LONGVARBINARY),
            new Target("DATE", Types.DATE),
            new Target("TIME", Types.TIME),
            new Target("TIMESTAMP", Types.TIMESTAMP),
            new Target("NCHAR", Types.NCHAR),
            new Target("NVARCHAR", Types.NVARCHAR),
            new Target("LONGNVARCHAR", Types.LONGNVARCHAR),
            new Target("ARRAY", Types.ARRAY),
            new Target("BLOB", Types.BLOB),
            new Target("CLOB", Types.CLOB),
            new Target("STRUCT", Types.STRUCT),
            new Target("REF", Types.REF),
            new Target("DATALINK", Types.DATALINK),
            new Target("JAVA_OBJECT", Types.JAVA_OBJECT),
            new Target("ROWID", Types.ROWID),
            new Target("NCLOB", Types.NCLOB),
            new Target("SQLXML", Types.SQLXML),
            new Target("TIME_WITH_TIMEZONE", Types.TIME_WITH_TIMEZONE),
            new Target("TIMESTAMP_WITH_TIMEZONE", Types.TIMESTAMP_WITH_TIMEZONE));

    record JavaRow(String name, Object value, Set<Integer> targets) {}

    private static final Set<Integer> NUMERIC_TEXT = Set.of(
            Types.TINYINT,
            Types.SMALLINT,
            Types.INTEGER,
            Types.BIGINT,
            Types.REAL,
            Types.FLOAT,
            Types.DOUBLE,
            Types.DECIMAL,
            Types.NUMERIC,
            Types.BIT,
            Types.BOOLEAN,
            Types.CHAR,
            Types.VARCHAR,
            Types.LONGVARCHAR);

    private static Set<Integer> set(int... sqlTypes) {
        return java.util.Arrays.stream(sqlTypes).boxed().collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static java.net.URL url(String value) {
        try {
            return URI.create(value).toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException(value, e);
        }
    }

    private static Set<Integer> union(Set<Integer> base, int... extra) {
        Set<Integer> result = new java.util.HashSet<>(base);
        for (int sqlType : extra) {
            result.add(sqlType);
        }
        return Set.copyOf(result);
    }

    private static final Calendar CALENDAR = calendar2024();

    private static Calendar calendar2024() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(2024, Calendar.JANUARY, 2, 3, 4, 5);
        return calendar;
    }

    private static final java.util.Date UTIL_DATE =
            new java.util.Date(CALENDAR.getTimeInMillis());

    private static final List<JavaRow> ROWS = List.of(
            new JavaRow(
                    "String",
                    "42",
                    union(NUMERIC_TEXT, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY,
                            Types.DATE, Types.TIME, Types.TIMESTAMP, Types.NCHAR, Types.NVARCHAR,
                            Types.LONGNVARCHAR)),
            new JavaRow("BigDecimal", new BigDecimal("7"), NUMERIC_TEXT),
            new JavaRow("Boolean", Boolean.TRUE, NUMERIC_TEXT),
            new JavaRow("Byte", (byte) 7, NUMERIC_TEXT),
            new JavaRow("Short", (short) 7, NUMERIC_TEXT),
            new JavaRow("Integer", 7, NUMERIC_TEXT),
            new JavaRow("Long", 7L, NUMERIC_TEXT),
            new JavaRow("Float", 7.0f, NUMERIC_TEXT),
            new JavaRow("Double", 7.0d, NUMERIC_TEXT),
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
            new JavaRow("java.sql.Blob", new LibsqlBlob("abc".getBytes(StandardCharsets.UTF_8)),
                    set(Types.BLOB)),
            new JavaRow("java.sql.Clob", new LibsqlClob("abc"), set(Types.CLOB)),
            new JavaRow("java.sql.Struct", stub(Struct.class), set(Types.STRUCT)),
            new JavaRow("java.sql.Ref", stub(Ref.class), set(Types.REF)),
            new JavaRow("java.net.URL", url("https://example.com"),
                    set(Types.DATALINK)),
            new JavaRow("Java class", new Object(), set(Types.JAVA_OBJECT)),
            new JavaRow("java.sql.RowId", stub(RowId.class), set(Types.ROWID)),
            new JavaRow("java.sql.NClob", stub(NClob.class), set(Types.NCLOB)),
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

    static Stream<JavaRow> javaRows() {
        return ROWS.stream();
    }

    @ParameterizedTest(name = "B-5 rejects unlisted targets for {0}")
    @MethodSource("javaRows")
    void b5SetObjectRejectsUnlistedTargetTypes(JavaRow row) {
        List<Executable> checks = new ArrayList<>();
        for (Target target : TARGETS) {
            if (row.targets().contains(target.sqlType())) {
                continue;
            }
            checks.add(() -> {
                LibsqlPreparedStatement statement = new LibsqlPreparedStatement(null, "?");
                try {
                    statement.setObject(1, row.value(), target.sqlType());
                    fail("B-5: setObject(" + row.name() + ", " + target.name()
                            + ") must be rejected, but it was accepted");
                } catch (SQLException expected) {
                    // Any SQLException is a pass.
                }
            });
        }
        assertAll("B-5 " + row.name(), checks);
    }

    @Test
    void b5SetObjectAcceptsListedTargetTypes() throws SQLException {
        for (JavaRow row : ROWS) {
            for (Target target : TARGETS) {
                if (!row.targets().contains(target.sqlType())) {
                    continue;
                }
                LibsqlPreparedStatement statement = new LibsqlPreparedStatement(null, "?");
                statement.setObject(1, row.value(), target.sqlType());
            }
        }
    }

    @Test
    void b5SetNullAcceptsEveryTargetType() throws SQLException {
        for (Target target : TARGETS) {
            LibsqlPreparedStatement statement = new LibsqlPreparedStatement(null, "?");
            statement.setNull(1, target.sqlType());
        }
    }

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

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }
}
