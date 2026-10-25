package io.github.magnusp.libsql.jdbc;

import java.nio.charset.StandardCharsets;

/**
 * Parses SQLite's text-to-number coercion, matching the conversion sqlite-jdbc 3.53.4.0 applies
 * for text and BLOB columns. Extracted verbatim from {@link LibsqlResultSet}.
 *
 * <p>Parsing consumes a numeric prefix only, so trailing garbage is ignored. An exponent counts
 * only when at least one digit follows it. Unparseable text yields {@code 0} or {@code 0.0}, and
 * integer overflow saturates to {@link Long#MIN_VALUE} or {@link Long#MAX_VALUE}.
 */
final class SqliteNumericText {

    private SqliteNumericText() {}

    static long parseLong(String text) {
        int length = text.length();
        int index = skipSqliteWhitespace(text, 0);
        boolean negative = false;
        if (index < length && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
            negative = text.charAt(index) == '-';
            index++;
        }

        int digitsStart = index;
        long value = 0L;
        boolean overflow = false;
        while (index < length) {
            char c = text.charAt(index);
            if (c < '0' || c > '9') {
                break;
            }
            if (!overflow) {
                int digit = c - '0';
                if (value > (Long.MAX_VALUE - digit) / 10) {
                    overflow = true;
                } else {
                    value = value * 10 + digit;
                }
            }
            index++;
        }

        if (index == digitsStart) {
            return 0L;
        }
        if (overflow) {
            return negative ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return negative ? -value : value;
    }

    static double parseDouble(String text) {
        int length = text.length();
        int index = skipSqliteWhitespace(text, 0);
        int start = index;
        if (index < length && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
            index++;
        }

        int digits = 0;
        while (index < length && isAsciiDigit(text.charAt(index))) {
            index++;
            digits++;
        }
        if (index < length && text.charAt(index) == '.') {
            index++;
            while (index < length && isAsciiDigit(text.charAt(index))) {
                index++;
                digits++;
            }
        }

        if (digits == 0) {
            return 0.0;
        }

        int end = index;
        if (index < length && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            int exponentIndex = index + 1;
            if (exponentIndex < length
                    && (text.charAt(exponentIndex) == '+' || text.charAt(exponentIndex) == '-')) {
                exponentIndex++;
            }
            int exponentDigits = 0;
            while (exponentIndex < length && isAsciiDigit(text.charAt(exponentIndex))) {
                exponentIndex++;
                exponentDigits++;
            }
            if (exponentDigits > 0) {
                end = exponentIndex;
            }
        }

        try {
            return Double.parseDouble(text.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    static String blobAsText(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int skipSqliteWhitespace(String text, int index) {
        while (index < text.length() && isSqliteWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static boolean isSqliteWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000b' || c == '\f';
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
