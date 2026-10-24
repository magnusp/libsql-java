package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Value;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Converts SQLite storage classes to local date and time values: INTEGER is epoch milliseconds,
 * FLOAT is a Julian day, and TEXT is a SQLite date/time string. Extracted verbatim from {@link
 * LibsqlResultSet}, which keeps the cursor read so {@code wasNull} still works.
 */
final class SqliteDateTimeValues {

    private SqliteDateTimeValues() {}

    static LocalDateTime from(Value value, String targetType) throws SQLException {
        return switch (value) {
            case Value.Null n -> null;
            case Value.Integer i -> epochMillisToLocalDateTime(i.value(), targetType);
            case Value.Float f ->
                    epochMillisToLocalDateTime(julianDayToEpochMillis(f.value()), targetType);
            case Value.Text t ->
                    t.value().isBlank() ? null : parseSqliteDateTime(t.value(), targetType);
            case Value.Blob b -> throw new SQLException("Bad value for type " + targetType);
        };
    }

    static LocalDateTime epochMillisToLocalDateTime(long epochMillis, String targetType)
            throws SQLException {
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        } catch (DateTimeException e) {
            throw new SQLException("Bad value for type " + targetType, e);
        }
    }

    static long julianDayToEpochMillis(double julianDay) {
        return Math.round((julianDay - 2440587.5) * 86_400_000.0);
    }

    static LocalDateTime parseSqliteDateTime(String text, String targetType)
            throws SQLException {
        String value = text.trim();
        ZoneOffset offset = null;
        if (value.endsWith("Z") || value.endsWith("z")) {
            offset = ZoneOffset.UTC;
            value = value.substring(0, value.length() - 1);
        } else if (value.length() > 10) {
            int sign = Math.max(value.lastIndexOf('+'), value.lastIndexOf('-'));
            if (sign > 9) {
                try {
                    offset = ZoneOffset.of(value.substring(sign));
                } catch (DateTimeException e) {
                    throw new SQLException("Bad value for type " + targetType + ": " + text, e);
                }
                value = value.substring(0, sign);
            }
        }

        try {
            int separator = -1;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == ' ' || c == 'T') {
                    separator = i;
                    break;
                }
            }

            LocalDate date;
            LocalTime time;
            if (separator >= 0) {
                date = LocalDate.parse(value.substring(0, separator));
                time = parseSqliteTime(value.substring(separator + 1));
            } else if (value.length() >= 10 && value.charAt(4) == '-') {
                date = LocalDate.parse(value);
                time = LocalTime.MIDNIGHT;
            } else {
                // SQLite uses 2000-01-01 when a time string carries no date.
                date = LocalDate.of(2000, 1, 1);
                time = parseSqliteTime(value);
            }

            LocalDateTime local = LocalDateTime.of(date, time);
            return offset == null
                    ? local
                    : local.atOffset(offset)
                            .atZoneSameInstant(ZoneId.systemDefault())
                            .toLocalDateTime();
        } catch (DateTimeException | NumberFormatException e) {
            throw new SQLException("Bad value for type " + targetType + ": " + text, e);
        }
    }

    static LocalTime parseSqliteTime(String text) {
        int dot = text.indexOf('.');
        String clock = dot >= 0 ? text.substring(0, dot) : text;
        String[] parts = clock.split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw new DateTimeException("Bad time: " + text);
        }
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        int second = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;

        int nanos = 0;
        if (dot >= 0) {
            String fraction = text.substring(dot + 1);
            String digits = fraction.length() > 9 ? fraction.substring(0, 9) : fraction;
            nanos = Integer.parseInt(digits);
            for (int i = digits.length(); i < 9; i++) {
                nanos *= 10;
            }
        }
        return LocalTime.of(hour, minute, second, nanos);
    }
}
