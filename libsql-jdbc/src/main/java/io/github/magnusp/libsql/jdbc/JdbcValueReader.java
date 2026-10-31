package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Value;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Converts a Hrana {@link Value} to the Java value a result-set getter returns.
 *
 * <p>This is the read-side counterpart of {@link JdbcValueConverter}. Every method is a pure
 * function of the stored value, the declared type where one applies, and the caller's calendar
 * zone. It holds no result-set state. A declared column parses numeric text strictly, while an
 * expression column keeps SQLite's permissive storage coercion; the caller passes that distinction
 * in. Exceptions are {@link SQLException} with the sqlite-jdbc 3.53.4.0 messages.
 */
final class JdbcValueReader {

    private JdbcValueReader() {}

    static String stringValue(Value val) {
        return switch (val) {
            case Value.Null n -> null;
            case Value.Integer i -> Long.toString(i.value());
            case Value.Float f -> SqliteRealText.format(f.value());
            case Value.Text t -> t.value();
            case Value.Blob b -> new String(b.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    static boolean booleanValue(Value value) {
        if (value instanceof Value.Text text) {
            String string = text.value().trim();
            if ("true".equalsIgnoreCase(string)) {
                return true;
            }
            if ("false".equalsIgnoreCase(string)) {
                return false;
            }
        }
        return (int) toLong(value) != 0;
    }

    /**
     * Reads a declared column strictly: a text or blob value that is not a number is an error
     * rather than SQLite's zero coercion. An expression column has no declared type and keeps the
     * permissive storage fallback that xerial users rely on.
     */
    static long longValue(Value value, boolean declared) throws SQLException {
        if (!declared) {
            return toLong(value);
        }
        return switch (value) {
            case Value.Text text -> strictLong(text.value());
            case Value.Blob blob -> strictLong(SqliteNumericText.blobAsText(blob.bytes()));
            default -> toLong(value);
        };
    }

    static double doubleValue(Value value, boolean declared) throws SQLException {
        if (!declared) {
            return toDouble(value);
        }
        return switch (value) {
            case Value.Text text -> strictDouble(text.value());
            case Value.Blob blob -> strictDouble(SqliteNumericText.blobAsText(blob.bytes()));
            default -> toDouble(value);
        };
    }

    private static long strictLong(String text) throws SQLException {
        try {
            return new BigDecimal(text.trim()).longValue();
        } catch (NumberFormatException e) {
            throw new SQLException("Bad value for type numeric: " + text, e);
        }
    }

    private static double strictDouble(String text) throws SQLException {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Bad value for type numeric: " + text, e);
        }
    }

    static long toLong(Value val) {
        return switch (val) {
            case Value.Null n -> 0L;
            case Value.Integer i -> i.value();
            case Value.Float f -> (long) f.value();
            case Value.Text t -> SqliteNumericText.parseLong(t.value());
            case Value.Blob b ->
                    SqliteNumericText.parseLong(SqliteNumericText.blobAsText(b.bytes()));
        };
    }

    static double toDouble(Value val) {
        return switch (val) {
            case Value.Null n -> 0.0;
            case Value.Integer i -> (double) i.value();
            case Value.Float f -> f.value();
            case Value.Text t -> SqliteNumericText.parseDouble(t.value());
            case Value.Blob b ->
                    SqliteNumericText.parseDouble(SqliteNumericText.blobAsText(b.bytes()));
        };
    }

    static byte[] bytesValue(Value val) {
        return switch (val) {
            case Value.Null n -> null;
            case Value.Blob b -> b.bytes();
            case Value.Text t -> t.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case Value.Integer i ->
                    Long.toString(i.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case Value.Float f ->
                    SqliteRealText.format(f.value())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    static Date dateValue(Value value) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "Date");
        return dateTime == null ? null : Date.valueOf(dateTime.toLocalDate());
    }

    static Date dateValue(Value value, ZoneId zone) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "Date", zone);
        if (dateTime == null) {
            return null;
        }
        return new Date(dateTime.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli());
    }

    static Time timeValue(Value value) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "Time");
        return dateTime == null ? null : Time.valueOf(dateTime.toLocalTime());
    }

    static Time timeValue(Value value, ZoneId zone) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "Time", zone);
        if (dateTime == null) {
            return null;
        }
        return new Time(
                LocalDate.of(1970, 1, 1)
                        .atTime(dateTime.toLocalTime())
                        .atZone(zone)
                        .toInstant()
                        .toEpochMilli());
    }

    static Timestamp timestampValue(Value value) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "Timestamp");
        return dateTime == null ? null : Timestamp.valueOf(dateTime);
    }

    static Timestamp timestampValue(Value value, ZoneId zone) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "Timestamp", zone);
        return dateTime == null ? null : Timestamp.from(dateTime.atZone(zone).toInstant());
    }

    static OffsetTime offsetTimeValue(Value value) throws SQLException {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            return OffsetTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new SQLException("Bad value for type OffsetTime: " + text, e);
        }
    }

    static OffsetDateTime offsetDateTimeValue(Value value) throws SQLException {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(sqlTimestampSeparator(text));
        } catch (DateTimeParseException e) {
            throw new SQLException("Bad value for type OffsetDateTime: " + text, e);
        }
    }

    /**
     * Accepts the SQLite text form with a space between the date and the time as well as the
     * ISO-8601 form with {@code T}. {@link OffsetDateTime#parse(CharSequence)} accepts only the
     * latter, and SQLite columns are commonly written with the former.
     */
    private static String sqlTimestampSeparator(String text) {
        return text.length() > 10 && text.charAt(10) == ' '
                ? text.substring(0, 10) + 'T' + text.substring(11)
                : text;
    }

    static BigDecimal bigDecimalValue(Value value) throws SQLException {
        String s = stringValue(value);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new SQLException("Bad value for type BigDecimal: " + s, e);
        }
    }

    static Object objectValue(Value value, JdbcTypeMapping.JdbcType type) throws SQLException {
        Class<?> objectClass = type.objectClass();
        if (objectClass == Integer.class) {
            return checkedInt(value, type.sqlType());
        }
        if (objectClass == Long.class) {
            return toLong(value);
        }
        if (objectClass == Double.class) {
            return toDouble(value);
        }
        if (objectClass == Float.class) {
            return (float) toDouble(value);
        }
        if (objectClass == String.class) {
            return stringValue(value);
        }
        return convertedObject(value, type);
    }

    /**
     * Converts every declared type outside the numeric and text fast path. It stays out of the
     * result-set getter so the common reads stay small enough for the JIT to inline and
     * scalar-replace their boxes.
     */
    private static Object convertedObject(Value value, JdbcTypeMapping.JdbcType type)
            throws SQLException {
        Class<?> objectClass = type.objectClass();
        if (objectClass == BigDecimal.class) {
            return bigDecimalValue(value);
        }
        if (objectClass == Boolean.class) {
            return booleanValue(value);
        }
        if (objectClass == byte[].class) {
            return bytesValue(value);
        }
        if (objectClass == Date.class) {
            return dateValue(value);
        }
        if (objectClass == Time.class) {
            return timeValue(value);
        }
        if (objectClass == Timestamp.class) {
            return timestampValue(value);
        }
        if (objectClass == OffsetTime.class) {
            return offsetTimeValue(value);
        }
        if (objectClass == OffsetDateTime.class) {
            return offsetDateTimeValue(value);
        }
        if (objectClass == Clob.class) {
            return clobValue(value);
        }
        if (objectClass == NClob.class) {
            return nclobValue(value);
        }
        if (objectClass == Blob.class) {
            return blobValue(value);
        }
        if (objectClass == RowId.class) {
            return rowIdValue(value);
        }
        if (objectClass == SQLXML.class) {
            return sqlxmlValue(value);
        }
        if (objectClass == URL.class) {
            return urlValue(value);
        }
        return storedObject(value);
    }

    private static Object storedObject(Value value) {
        return switch (value) {
            case Value.Null n -> null;
            case Value.Integer i ->
                    i.value() >= Integer.MIN_VALUE && i.value() <= Integer.MAX_VALUE
                            ? (Object) (int) i.value()
                            : (Object) i.value();
            case Value.Float f -> f.value();
            case Value.Text t -> t.value();
            case Value.Blob b -> b.bytes();
        };
    }

    static Integer checkedInt(Value value, int sqlType) throws SQLException {
        return checkedInt(toLong(value), sqlType);
    }

    static Integer checkedInt(long number, int sqlType) throws SQLException {
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new SQLException(
                    "Value "
                            + number
                            + " does not fit the declared "
                            + sqlTypeName(sqlType)
                            + " type");
        }
        return (int) number;
    }

    private static String sqlTypeName(int sqlType) {
        return switch (sqlType) {
            case java.sql.Types.TINYINT -> "TINYINT";
            case java.sql.Types.SMALLINT -> "SMALLINT";
            case java.sql.Types.INTEGER -> "INTEGER";
            default -> "numeric";
        };
    }

    static Blob blobValue(Value value) {
        byte[] bytes = bytesValue(value);
        return bytes == null ? null : new LibsqlBlob(bytes);
    }

    static Clob clobValue(Value value) {
        String string = stringValue(value);
        return string == null ? null : new LibsqlClob(string);
    }

    static URL urlValue(Value value) throws SQLException {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            // URI.toURL replaces the deprecated URL(String) constructor and rejects the same text.
            return URI.create(text).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new SQLException("Bad value for type URL: " + text, e);
        }
    }

    static RowId rowIdValue(Value value) {
        byte[] bytes = bytesValue(value);
        return bytes == null ? null : new LibsqlRowId(bytes);
    }

    static NClob nclobValue(Value value) {
        String text = stringValue(value);
        return text == null ? null : new LibsqlNClob(text);
    }

    static SQLXML sqlxmlValue(Value value) {
        String text = stringValue(value);
        return text == null ? null : new LibsqlSQLXML(text);
    }

    /**
     * Returns a value converted to the requested Java type. Numeric wrapper targets are gated on
     * the storage class and read strictly when the column is declared, matching the individual
     * getters. The result-set override owns the null-type check and the declared-type rejection.
     */
    static <T> T typedObject(Value stored, Class<T> type) throws SQLException {
        if (type == String.class) {
            return type.cast(stringValue(stored));
        }
        if (type == Boolean.class) {
            return type.cast(booleanValue(stored));
        }
        if (type == BigDecimal.class) {
            return type.cast(bigDecimalValue(stored));
        }
        if (type == byte[].class) {
            return type.cast(bytesValue(stored));
        }
        if (type == Date.class) {
            return type.cast(dateValue(stored));
        }
        if (type == Time.class) {
            return type.cast(timeValue(stored));
        }
        if (type == Timestamp.class) {
            return type.cast(timestampValue(stored));
        }
        if (type == LocalDate.class) {
            return type.cast(localDate(stored));
        }
        if (type == LocalTime.class) {
            return type.cast(localTime(stored));
        }
        if (type == LocalDateTime.class) {
            return type.cast(dateTimeValue(stored, "LocalDateTime"));
        }
        if (type == OffsetTime.class) {
            return type.cast(offsetTimeValue(stored));
        }
        if (type == OffsetDateTime.class) {
            return type.cast(offsetDateTimeValue(stored));
        }

        boolean numeric = stored instanceof Value.Integer || stored instanceof Value.Float;
        if (type == Double.class) {
            if (numeric) {
                return type.cast(toDouble(stored));
            }
            throw new SQLException("Bad value for type Double");
        }
        if (type == Long.class) {
            if (numeric) {
                return type.cast(toLong(stored));
            }
            throw new SQLException("Bad value for type Long");
        }
        if (type == Float.class) {
            if (numeric) {
                return type.cast((float) toDouble(stored));
            }
            throw new SQLException("Bad value for type Float");
        }
        if (type == Integer.class) {
            if (numeric) {
                return type.cast((int) toLong(stored));
            }
            throw new SQLException("Bad value for type Integer");
        }

        throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver");
    }

    private static LocalDate localDate(Value value) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "LocalDate");
        return dateTime == null ? null : dateTime.toLocalDate();
    }

    private static LocalTime localTime(Value value) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "LocalTime");
        return dateTime == null ? null : dateTime.toLocalTime();
    }

    /**
     * Reads a value as a local date and time using SQLite storage-class conventions: INTEGER is
     * epoch milliseconds, FLOAT is a Julian day, and TEXT is a SQLite date/time string.
     */
    static LocalDateTime dateTimeValue(Value value, String targetType) throws SQLException {
        return SqliteDateTimeValues.from(value, targetType);
    }

    static LocalDateTime dateTimeValue(Value value, String targetType, ZoneId zone)
            throws SQLException {
        return SqliteDateTimeValues.from(value, targetType, zone);
    }
}
