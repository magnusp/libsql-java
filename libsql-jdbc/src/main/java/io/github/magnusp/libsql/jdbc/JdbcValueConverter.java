package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Value;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
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
import java.time.ZoneId;
import java.util.Calendar;

/**
 * Converts a Java value to the Hrana value bound for a parameter.
 *
 * <p>{@link #toValue(Object, int)} implements JDBC 4.3 Appendix B, Table B-5: the value is first
 * converted to the target JDBC type and then to the Hrana value of that type's storage class. A
 * pair that Table B-5 does not list throws {@link SQLFeatureNotSupportedException}, so the target
 * type is never silently ignored. {@link #toValue(Object)} implements Table B-4 for the
 * {@code setObject} call that has no target type.
 *
 * <p>The converter is a pure function of the value and the target type. It holds no statement or
 * result-set state. LOB content is read through {@link Clob#getCharacterStream()} and {@link
 * Blob#getBinaryStream()}; a LOB is never converted with {@code toString}.
 *
 * <p>A text target uses the Java {@code toString} form, with one documented exception: a {@link
 * Boolean} binds {@code "true"} or {@code "false"} rather than {@code "TRUE"} or {@code "FALSE"},
 * so the text round trips through {@code getBoolean}. Numeric targets narrow with Java primitive
 * conversion rules. Integral and boolean targets bind Hrana integers, and decimal targets bind
 * decimal text. Date and time targets bind the SQLite text format that {@code getDate}, {@code
 * getTime}, and {@code getTimestamp} parse.
 */
final class JdbcValueConverter {

    private JdbcValueConverter() {}

    /** Converts a value with no target type, per Table B-4. */
    static Value toValue(Object value) throws SQLException {
        if (value == null) {
            return Value.ofNull();
        }
        if (value instanceof String text) {
            return Value.of(text);
        }
        if (value instanceof BigDecimal decimal) {
            return Value.of(decimal.toString());
        }
        if (value instanceof BigInteger integer) {
            return Value.of(bigIntegerLong(integer));
        }
        if (value instanceof Boolean bool) {
            return Value.of(bool ? 1L : 0L);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return Value.of(((Number) value).longValue());
        }
        if (value instanceof Float floating) {
            return Value.of((double) floating);
        }
        if (value instanceof Double floating) {
            return Value.of((double) floating);
        }
        if (value instanceof byte[] bytes) {
            return Value.of(bytes);
        }
        if (value instanceof Timestamp timestamp) {
            return Value.of(formatTimestamp(timestamp.toLocalDateTime()));
        }
        if (value instanceof Time time) {
            return Value.of(formatTime(time.toLocalTime()));
        }
        if (value instanceof Date date) {
            return Value.of(date.toString());
        }
        if (value instanceof LocalDate date) {
            return Value.of(date.toString());
        }
        if (value instanceof LocalTime time) {
            return Value.of(formatTime(time));
        }
        if (value instanceof LocalDateTime dateTime) {
            return Value.of(formatTimestamp(dateTime));
        }
        if (value instanceof OffsetTime time) {
            return Value.of(time.toString());
        }
        if (value instanceof OffsetDateTime dateTime) {
            return Value.of(dateTime.toString());
        }
        if (value instanceof Calendar calendar) {
            return Value.of(formatTimestamp(localDateTime(calendar.toInstant())));
        }
        if (value instanceof java.util.Date date) {
            return Value.of(formatTimestamp(localDateTime(date.toInstant())));
        }
        if (value instanceof Clob clob) {
            return Value.of(characterText(clob));
        }
        if (value instanceof Blob blob) {
            return Value.of(binaryBytes(blob));
        }
        if (value instanceof URL || value instanceof RowId || value instanceof SQLXML) {
            // Table B-4 maps these to DATALINK, ROWID and SQLXML, which the driver supports.
            return Value.of(value.toString());
        }
        // Table B-4 maps Array, Struct, Ref and every other class to an N/A JDBC type.
        throw unsupported(value, unsupportedSourceType(value));
    }

    private static int unsupportedSourceType(Object value) {
        if (value instanceof Array) {
            return Types.ARRAY;
        }
        if (value instanceof Struct) {
            return Types.STRUCT;
        }
        if (value instanceof Ref) {
            return Types.REF;
        }
        return Types.JAVA_OBJECT;
    }

    /** Converts a value to the target JDBC type, then to the Hrana value of that type. */
    static Value toValue(Object value, int targetSqlType) throws SQLException {
        if (value == null) {
            return Value.ofNull();
        }
        return switch (targetSqlType) {
            case Types.TINYINT -> Value.of((long) numeric(value, targetSqlType).byteValue());
            case Types.SMALLINT -> Value.of((long) numeric(value, targetSqlType).shortValue());
            case Types.INTEGER -> Value.of((long) numeric(value, targetSqlType).intValue());
            case Types.BIGINT -> Value.of(bigInt(value));
            case Types.REAL -> Value.of((double) numeric(value, targetSqlType).floatValue());
            case Types.FLOAT, Types.DOUBLE -> Value.of(numeric(value, targetSqlType).doubleValue());
            case Types.DECIMAL, Types.NUMERIC -> Value.of(numeric(value, targetSqlType).toString());
            case Types.BIT, Types.BOOLEAN -> Value.of(bit(value, targetSqlType));
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR -> Value.of(text(value, targetSqlType));
            case Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
                    Value.of(nationalText(value, targetSqlType));
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY ->
                    Value.of(binary(value, targetSqlType));
            case Types.BLOB -> Value.of(blobBytes(value));
            case Types.CLOB -> Value.of(clobText(value));
            case Types.NCLOB -> Value.of(nclobText(value));
            case Types.DATE -> Value.of(dateText(value, targetSqlType));
            case Types.TIME -> Value.of(timeText(value, targetSqlType));
            case Types.TIMESTAMP -> Value.of(timestampText(value, targetSqlType));
            case Types.DATALINK -> Value.of(datalinkText(value));
            case Types.ARRAY, Types.STRUCT, Types.REF, Types.JAVA_OBJECT, Types.DISTINCT ->
                    throw unsupported(value, targetSqlType);
            case Types.ROWID -> Value.of(rowIdText(value));
            case Types.SQLXML -> Value.of(sqlXmlText(value));
            case Types.TIME_WITH_TIMEZONE -> Value.of(offsetTimeText(value));
            case Types.TIMESTAMP_WITH_TIMEZONE -> Value.of(offsetDateTimeText(value));
            default -> throw unsupported(value, targetSqlType);
        };
    }

    private static long bigIntegerLong(BigInteger value) throws SQLException {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new SQLException("BigInteger value does not fit a BIGINT: " + value, e);
        }
    }

    /**
     * Narrows a numeric value with the Java primitive conversion rules. {@link
     * BigDecimal#byteValue()} and its siblings already perform the same narrowing as a Java cast,
     * so routing every numeric source through BigDecimal keeps the rules in one place.
     */
    private static BigDecimal numeric(Object value, int targetSqlType) throws SQLException {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Boolean bool) {
            return bool ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return BigDecimal.ONE;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException e) {
                throw new SQLException(
                        "Cannot convert \"" + text + "\" to " + JdbcTypeMapping.sqlTypeName(targetSqlType),
                        e);
            }
        }
        throw unsupported(value, targetSqlType);
    }

    private static long bigInt(Object value) throws SQLException {
        if (value instanceof BigInteger integer) {
            return bigIntegerLong(integer);
        }
        return numeric(value, Types.BIGINT).longValue();
    }

    private static long bit(Object value, int targetSqlType) throws SQLException {
        return numeric(value, targetSqlType).signum() == 0 ? 0L : 1L;
    }

    private static String text(Object value, int targetSqlType) throws SQLException {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof BigDecimal || value instanceof BigInteger
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) {
            return value.toString();
        }
        if (value instanceof java.util.Date
                || value instanceof Calendar
                || value instanceof LocalDate
                || value instanceof LocalTime
                || value instanceof LocalDateTime
                || value instanceof OffsetTime
                || value instanceof OffsetDateTime) {
            return value.toString();
        }
        throw unsupported(value, targetSqlType);
    }

    private static String nationalText(Object value, int targetSqlType) throws SQLException {
        if (value instanceof String text) {
            return text;
        }
        throw unsupported(value, targetSqlType);
    }

    private static byte[] binary(Object value, int targetSqlType) throws SQLException {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        throw unsupported(value, targetSqlType);
    }

    private static byte[] blobBytes(Object value) throws SQLException {
        if (value instanceof Blob blob) {
            return binaryBytes(blob);
        }
        throw unsupported(value, Types.BLOB);
    }

    private static String clobText(Object value) throws SQLException {
        // NClob is a Clob, but Table B-5 lists it only for the NCLOB target.
        if (value instanceof Clob clob && !(value instanceof NClob)) {
            return characterText(clob);
        }
        throw unsupported(value, Types.CLOB);
    }

    private static String nclobText(Object value) throws SQLException {
        if (value instanceof NClob clob) {
            return characterText(clob);
        }
        throw unsupported(value, Types.NCLOB);
    }

    private static String dateText(Object value, int targetSqlType) throws SQLException {
        LocalDate date;
        if (value instanceof Timestamp timestamp) {
            date = timestamp.toLocalDateTime().toLocalDate();
        } else if (value instanceof Date sqlDate) {
            date = sqlDate.toLocalDate();
        } else if (value instanceof LocalDate localDate) {
            date = localDate;
        } else if (value instanceof LocalDateTime localDateTime) {
            date = localDateTime.toLocalDate();
        } else if (value instanceof java.sql.Time) {
            throw unsupported(value, targetSqlType);
        } else if (value instanceof Calendar calendar) {
            date = LocalDate.ofInstant(calendar.toInstant(), ZoneId.systemDefault());
        } else if (value instanceof java.util.Date utilDate) {
            date = LocalDate.ofInstant(utilDate.toInstant(), ZoneId.systemDefault());
        } else if (value instanceof String text) {
            // A string is already in the target type's text form. Binding it unchanged keeps the
            // B-5 acceptance rule and lets the getter reject malformed text.
            return text;
        } else {
            throw unsupported(value, targetSqlType);
        }
        return date.toString();
    }

    private static String timeText(Object value, int targetSqlType) throws SQLException {
        LocalTime time;
        if (value instanceof Time sqlTime) {
            time = sqlTime.toLocalTime();
        } else if (value instanceof Timestamp timestamp) {
            time = timestamp.toLocalDateTime().toLocalTime();
        } else if (value instanceof LocalTime localTime) {
            time = localTime;
        } else if (value instanceof LocalDateTime localDateTime) {
            time = localDateTime.toLocalTime();
        } else if (value instanceof Date) {
            throw unsupported(value, targetSqlType);
        } else if (value instanceof Calendar calendar) {
            time = LocalTime.ofInstant(calendar.toInstant(), ZoneId.systemDefault());
        } else if (value instanceof java.util.Date utilDate) {
            time = LocalTime.ofInstant(utilDate.toInstant(), ZoneId.systemDefault());
        } else if (value instanceof String text) {
            return text;
        } else {
            throw unsupported(value, targetSqlType);
        }
        return formatTime(time);
    }

    private static String timestampText(Object value, int targetSqlType) throws SQLException {
        LocalDateTime dateTime;
        if (value instanceof Timestamp timestamp) {
            dateTime = timestamp.toLocalDateTime();
        } else if (value instanceof Date date) {
            dateTime = date.toLocalDate().atStartOfDay();
        } else if (value instanceof LocalDateTime localDateTime) {
            dateTime = localDateTime;
        } else if (value instanceof Time time) {
            // The date part of a TIME to TIMESTAMP conversion is implementation defined;
            // sqlite-jdbc and this driver use the epoch date.
            dateTime = LocalDate.of(1970, 1, 1).atTime(time.toLocalTime());
        } else if (value instanceof Calendar calendar) {
            dateTime = localDateTime(calendar.toInstant());
        } else if (value instanceof java.util.Date utilDate) {
            dateTime = localDateTime(utilDate.toInstant());
        } else if (value instanceof String text) {
            return text;
        } else {
            throw unsupported(value, targetSqlType);
        }
        return formatTimestamp(dateTime);
    }

    private static String datalinkText(Object value) throws SQLException {
        if (value instanceof URL url) {
            return url.toString();
        }
        throw unsupported(value, Types.DATALINK);
    }

    private static String rowIdText(Object value) throws SQLException {
        if (value instanceof RowId rowId) {
            return rowId.toString();
        }
        throw unsupported(value, Types.ROWID);
    }

    private static String sqlXmlText(Object value) throws SQLException {
        if (value instanceof SQLXML xml) {
            return xml.toString();
        }
        throw unsupported(value, Types.SQLXML);
    }

    private static String offsetTimeText(Object value) throws SQLException {
        if (value instanceof OffsetTime time) {
            return time.toString();
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toOffsetTime().toString();
        }
        throw unsupported(value, Types.TIME_WITH_TIMEZONE);
    }

    private static String offsetDateTimeText(Object value) throws SQLException {
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toString();
        }
        throw unsupported(value, Types.TIMESTAMP_WITH_TIMEZONE);
    }

    private static LocalDateTime localDateTime(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static String formatTimestamp(LocalDateTime value) {
        return value.toLocalDate() + " " + formatTime(value.toLocalTime());
    }

    private static String formatTime(LocalTime value) {
        StringBuilder text = new StringBuilder(18);
        appendTwoDigits(text, value.getHour());
        text.append(':');
        appendTwoDigits(text, value.getMinute());
        text.append(':');
        appendTwoDigits(text, value.getSecond());
        int nanos = value.getNano();
        if (nanos != 0) {
            String fraction = Integer.toString(1_000_000_000 + nanos).substring(1);
            int end = fraction.length();
            while (end > 0 && fraction.charAt(end - 1) == '0') {
                end--;
            }
            text.append('.').append(fraction, 0, end);
        }
        return text.toString();
    }

    private static void appendTwoDigits(StringBuilder text, int value) {
        text.append((char) ('0' + value / 10)).append((char) ('0' + value % 10));
    }

    private static String characterText(Clob clob) throws SQLException {
        Reader reader = clob.getCharacterStream();
        if (reader == null) {
            return null;
        }
        try {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                text.append(buffer, 0, read);
            }
            return text.toString();
        } catch (IOException e) {
            throw new SQLException("Cannot read from character stream: " + e.getMessage(), e);
        }
    }

    private static byte[] binaryBytes(Blob blob) throws SQLException {
        InputStream in = blob.getBinaryStream();
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new SQLException("Cannot read from binary stream: " + e.getMessage(), e);
        }
    }

    private static SQLFeatureNotSupportedException unsupported(Object value, int targetSqlType) {
        return new SQLFeatureNotSupportedException(
                "Cannot convert " + value.getClass().getName() + " to "
                        + JdbcTypeMapping.sqlTypeName(targetSqlType));
    }
}
