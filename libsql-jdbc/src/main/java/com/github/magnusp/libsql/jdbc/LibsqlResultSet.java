package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Result set over a materialized Hrana statement result.
 *
 * <p>Navigation matches sqlite-jdbc 3.53.4.0 rather than strict JDBC, because the driver is a
 * drop-in replacement for that driver. Before the first {@link #next()} call the column getters
 * read the first row and {@link #getRow()} returns 0. Column getters after the last row return
 * SQL NULL (numeric getters return 0), while {@link #getRow()} keeps returning the number of the
 * last row. {@link #isLast()} always throws {@link SQLFeatureNotSupportedException}, matching
 * xerial. {@link #wasNull()} reports the last column read and throws while no column has been
 * read in the current row, including immediately before the first row and immediately after the
 * last row. When labels repeat, {@link #findColumn(String)} returns the first match.
 */
public class LibsqlResultSet implements ResultSet {

    private final Statement statement;
    private final List<Col> cols;
    private final List<Row> rows;
    private final String sql;
    private final LibsqlConnection connection;
    private final boolean closeStatementOnClose;
    private int currentRowIndex = -1;
    private int lastColumnIndex = 0;
    private boolean closed = false;
    private int fetchSize = 0;
    private LibsqlResultSetMetaData metadata;

    // Mitigation for Bug 6: O(1) column label lookup
    private Map<String, Integer> columnNameToIndex;

    public LibsqlResultSet(Statement statement, StmtResult result) {
        this(statement, result, null, false);
    }

    LibsqlResultSet(Statement statement, StmtResult result, String sql, boolean closeStatementOnClose) {
        this.statement = statement;
        this.cols = (result != null && result.cols() != null) ? result.cols() : List.of();
        this.rows = (result != null && result.rows() != null) ? result.rows() : List.of();
        this.sql = sql;
        this.connection = statement instanceof LibsqlStatement libsqlStatement
                ? libsqlStatement.connection
                : null;
        this.closeStatementOnClose = closeStatementOnClose;
    }

    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("ResultSet is closed");
        }
    }

    private Value valueAtCursor(int columnIndex) {
        int rowIndex = currentRowIndex < 0 ? 0 : currentRowIndex;
        if (rowIndex >= rows.size()) {
            return Value.ofNull();
        }
        return rows.get(rowIndex).values().get(columnIndex - 1);
    }

    private Value getCurrentValue(int columnIndex) throws SQLException {
        checkClosed();
        if (columnIndex < 1 || columnIndex > cols.size()) {
            throw new SQLException("Column index out of bounds: " + columnIndex);
        }
        lastColumnIndex = columnIndex;
        return valueAtCursor(columnIndex);
    }

    Value valueForMetadata(int columnIndex) {
        int rowIndex = currentRowIndex < 0 ? 0 : currentRowIndex;
        if (rowIndex >= rows.size()) {
            return null;
        }
        Row row = rows.get(rowIndex);
        List<Value> values = row.values();
        if (values == null || columnIndex < 1 || columnIndex > values.size()) {
            return null;
        }
        return values.get(columnIndex - 1);
    }

    @Override
    public boolean next() throws SQLException {
        checkClosed();
        if (rows.isEmpty() || currentRowIndex >= rows.size()) {
            return false;
        }
        lastColumnIndex = -1;
        currentRowIndex++;
        return currentRowIndex < rows.size();
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        this.closed = true;
        if (statement != null
                && (closeStatementOnClose
                        || (statement instanceof LibsqlStatement s && s.isCloseOnCompletion()))) {
            statement.close();
        }
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkClosed();
        if (lastColumnIndex < 1 || lastColumnIndex > cols.size()) {
            throw new SQLException(
                    "column " + lastColumnIndex + " out of bounds [1," + cols.size() + "]");
        }
        return valueAtCursor(lastColumnIndex) instanceof Value.Null;
    }

    /**
     * Returns SQLite's canonical text for each storage class, matching sqlite-jdbc 3.53.4.0.
     *
     * <p>REAL values use the same representation as SQLite's {@code %!.17g}: up to 17 significant
     * digits with trailing zeros removed, fixed notation when the decimal exponent is between -4
     * and 16, and scientific notation with a signed, at least two-digit exponent otherwise. The
     * result always has at least one digit after the decimal point. For example, {@code 0.0001}
     * becomes {@code 0.0001}, {@code 10000000.0} becomes {@code 10000000.0}, and {@code 1e20}
     * becomes {@code 1.0e+20}. Negative zero renders as {@code 0.0}.
     */
    @Override
    public String getString(int columnIndex) throws SQLException {
        Value val = getCurrentValue(columnIndex);
        return switch (val) {
            case Value.Null n -> null;
            case Value.Integer i -> Long.toString(i.value());
            case Value.Float f -> sqliteRealText(f.value());
            case Value.Text t -> t.value();
            case Value.Blob b -> new String(b.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    // Port of the fixed-point conversion SQLite 3.53.4 uses for printf("%!.17g"), which is the
    // text sqlite-jdbc 3.53.4.0 returns for REAL. The power-of-ten tables come from the same
    // source (util.c and tool/mkfptab.c).
    private static final long[] FP_POWERS_OF_TEN = {
        0x8000000000000000L, 0xa000000000000000L, 0xc800000000000000L, 0xfa00000000000000L,
        0x9c40000000000000L, 0xc350000000000000L, 0xf424000000000000L, 0x9896800000000000L,
        0xbebc200000000000L, 0xee6b280000000000L, 0x9502f90000000000L, 0xba43b74000000000L,
        0xe8d4a51000000000L, 0x9184e72a00000000L, 0xb5e620f480000000L, 0xe35fa931a0000000L,
        0x8e1bc9bf04000000L, 0xb1a2bc2ec5000000L, 0xde0b6b3a76400000L, 0x8ac7230489e80000L,
        0xad78ebc5ac620000L, 0xd8d726b7177a8000L, 0x878678326eac9000L, 0xa968163f0a57b400L,
        0xd3c21bcecceda100L, 0x84595161401484a0L, 0xa56fa5b99019a5c8L,
    };

    private static final long[] FP_POWER_SCALES = {
        0x8049a4ac0c5811aeL, 0xcf42894a5dce35eaL, 0xa76c582338ed2621L, 0x873e4f75e2224e68L,
        0xda7f5bf590966848L, 0xb080392cc4349decL, 0x8e938662882af53eL, 0xe65829b3046b0afaL,
        0xba121a4650e4ddebL, 0x964e858c91ba2655L, 0xf2d56790ab41c2a2L, 0xc428d05aa4751e4cL,
        0x9e74d1b791e07e48L, 0xccccccccccccccccL, 0xcecb8f27f4200f3aL, 0xa70c3c40a64e6c51L,
        0x86f0ac99b4e8dafdL, 0xda01ee641a708de9L, 0xb01ae745b101e9e4L, 0x8e41ade9fbebc27dL,
        0xe5d3ef282a242e81L, 0xb9a74a0637ce2ee1L, 0x95f83d0a1fb69cd9L, 0xf24a01a73cf2dccfL,
        0xc3b8358109e84f07L, 0x9e19db92b4e31ba9L,
    };

    private static final int[] FP_POWER_SCALE_LOW = {
        0x205b896d, 0x52064cad, 0xaf2af2b8, 0x5a7744a7, 0xaf39a475, 0xbd8d794e,
        0x547eb47b, 0x0cb4a5a3, 0x92f34d62, 0x3a6a07f9, 0xfae27299, 0xaa97e14c,
        0x775ea265, 0xcccccccc, 0x00000000, 0x999090b6, 0x69a028bb, 0xe80e6f48,
        0x5ec05dd0, 0x14588f14, 0x8f1668c9, 0x6d953e2c, 0x4abdaf10, 0xbc633b39,
        0x0a862f81, 0x6c07a2c2,
    };

    private static String sqliteRealText(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value < 0 ? "-Inf" : "Inf";
        }
        boolean negative = value < 0.0;
        double magnitude = negative ? -value : value;
        if (magnitude == 0.0) {
            return "0.0";
        }

        long bits = Double.doubleToRawLongBits(magnitude);
        int exponent = (int) ((bits >>> 52) & 0x7ff);
        long mantissa = bits & 0x000fffffffffffffL;
        if (exponent == 0) {
            int shift = Long.numberOfLeadingZeros(mantissa);
            mantissa <<= shift;
            exponent = -1074 - shift;
        } else {
            mantissa = (mantissa << 11) | (1L << 63);
            exponent -= 1086;
        }

        int power = 17 - fpPowerOfTwoToTen(exponent + 63);
        long powerOfTen = fpPowerOfTen(power);
        long high = fpMultiplyHigh(mantissa, powerOfTen);
        int shift = -(exponent + fpPowerOfTenToTwo(power) + 2);
        high >>>= shift;
        long digitsValue = (high + ((high << 1) & 2)) >>> 1;
        int powerOfTenExponent = -power;

        String digits = Long.toUnsignedString(digitsValue);
        int digitCount = digits.length();
        int decimalPoint = digitCount + powerOfTenExponent;

        if (digitCount > 17) {
            int roundAt = 17;
            if (digits.charAt(15) == '9' && digits.charAt(14) == '9') {
                int lastKept = 14;
                while (lastKept > 0 && digits.charAt(lastKept - 1) == '9') {
                    lastKept--;
                }
                BigInteger candidate = lastKept == 0
                        ? BigInteger.ONE
                        : new BigInteger(digits.substring(0, lastKept)).add(BigInteger.ONE);
                if (roundTripsTo(magnitude, candidate, powerOfTenExponent + digitCount - lastKept)) {
                    roundAt = lastKept + 1;
                }
            } else if (decimalPoint >= digitCount
                    || (digits.charAt(15) == '0'
                            && digits.charAt(14) == '0'
                            && digits.charAt(13) == '0')) {
                int lastKept = 13;
                while (lastKept > 0 && digits.charAt(lastKept - 1) == '0') {
                    lastKept--;
                }
                BigInteger candidate = new BigInteger(digits.substring(0, lastKept));
                if (roundTripsTo(magnitude, candidate, powerOfTenExponent + digitCount - lastKept)) {
                    roundAt = lastKept + 1;
                }
            }

            char[] rounded = new char[digitCount + 1];
            digits.getChars(0, digitCount, rounded, 1);
            int offset = 1;
            int count = roundAt;
            if (rounded[offset + roundAt] >= '5') {
                int index = offset + roundAt - 1;
                while (true) {
                    rounded[index]++;
                    if (rounded[index] <= '9') {
                        break;
                    }
                    rounded[index] = '0';
                    if (index == offset) {
                        offset--;
                        rounded[offset] = '1';
                        count++;
                        decimalPoint++;
                        break;
                    }
                    index--;
                }
            }
            digits = new String(rounded, offset, count);
        }

        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);

        int decimalExponent = decimalPoint - 1;
        StringBuilder out = new StringBuilder();
        if (negative) {
            out.append('-');
        }
        if (decimalExponent < -4 || decimalExponent > 16) {
            out.append(digits.charAt(0)).append('.');
            int fractionEnd = digits.length();
            while (fractionEnd > 1 && digits.charAt(fractionEnd - 1) == '0') {
                fractionEnd--;
            }
            if (fractionEnd > 1) {
                out.append(digits, 1, fractionEnd);
            } else {
                out.append('0');
            }
            out.append('e');
            out.append(decimalExponent < 0 ? '-' : '+');
            int absoluteExponent = Math.abs(decimalExponent);
            if (absoluteExponent >= 100) {
                out.append((char) ('0' + absoluteExponent / 100));
            }
            out.append((char) ('0' + (absoluteExponent / 10) % 10));
            out.append((char) ('0' + absoluteExponent % 10));
        } else {
            int integerPlaces = decimalPoint - 1;
            int fractionPrecision = 16 - decimalExponent;
            StringBuilder fraction = new StringBuilder();
            if (integerPlaces < 0) {
                out.append('0');
                int leadingZeros = Math.min(-1 - integerPlaces, fractionPrecision);
                fraction.append("0".repeat(leadingZeros));
                fractionPrecision -= leadingZeros;
                fraction.append(digits, 0, Math.min(digits.length(), fractionPrecision));
            } else {
                int integerDigits = Math.min(integerPlaces + 1, digits.length());
                out.append(digits, 0, integerDigits);
                out.append("0".repeat(Math.max(0, integerPlaces + 1 - integerDigits)));
                int significantDigits = Math.min(digits.length() - integerDigits, fractionPrecision);
                if (significantDigits > 0) {
                    fraction.append(digits, integerDigits, integerDigits + significantDigits);
                }
            }
            int fractionEnd = fraction.length();
            while (fractionEnd > 0 && fraction.charAt(fractionEnd - 1) == '0') {
                fractionEnd--;
            }
            out.append('.');
            if (fractionEnd == 0) {
                out.append('0');
            } else {
                out.append(fraction, 0, fractionEnd);
            }
        }
        return out.toString();
    }

    private static boolean roundTripsTo(double magnitude, BigInteger digits, int powerOfTen) {
        double candidate = new BigDecimal(digits).scaleByPowerOfTen(powerOfTen).doubleValue();
        return magnitude == candidate;
    }

    private static int fpPowerOfTenToTwo(int power) {
        return (power * 108853) >> 15;
    }

    private static int fpPowerOfTwoToTen(int power) {
        return (power * 78913) >> 18;
    }

    private static long fpPowerOfTen(int power) {
        int[] nextBits = new int[1];
        if (power < 0) {
            if (power == -1) {
                nextBits[0] = FP_POWER_SCALE_LOW[13];
                return FP_POWER_SCALES[13];
            }
            int group = power / 27;
            int index = power % 27;
            if (index != 0) {
                group--;
                index += 27;
            }
            long scale = FP_POWER_SCALES[group + 13];
            if (index == 0) {
                nextBits[0] = FP_POWER_SCALE_LOW[group + 13];
                return scale;
            }
            return fpNormalizeHigh(
                    fpMultiplyWide(
                            scale, FP_POWER_SCALE_LOW[group + 13], FP_POWERS_OF_TEN[index], nextBits),
                    nextBits);
        }
        if (power < 27) {
            nextBits[0] = 0;
            return FP_POWERS_OF_TEN[power];
        }
        int group = power / 27;
        int index = power % 27;
        long scale = FP_POWER_SCALES[group + 13];
        if (index == 0) {
            nextBits[0] = FP_POWER_SCALE_LOW[group + 13];
            return scale;
        }
        return fpNormalizeHigh(
                fpMultiplyWide(scale, FP_POWER_SCALE_LOW[group + 13], FP_POWERS_OF_TEN[index], nextBits),
                nextBits);
    }

    private static long fpNormalizeHigh(long high, int[] nextBits) {
        if (high >= 0) {
            high = (high << 1) | ((nextBits[0] >>> 31) & 1);
            nextBits[0] = (nextBits[0] << 1) | 1;
        }
        return high;
    }

    private static long fpMultiplyWide(long high, int low, long multiplier, int[] resultLow) {
        BigInteger left = fpUnsigned(high).shiftLeft(32).or(BigInteger.valueOf(low & 0xffffffffL));
        BigInteger product = left.multiply(fpUnsigned(multiplier));
        resultLow[0] = product.shiftRight(64).intValue();
        return product.shiftRight(96).longValue();
    }

    private static long fpMultiplyHigh(long left, long right) {
        return fpUnsigned(left).multiply(fpUnsigned(right)).shiftRight(64).longValue();
    }

    private static BigInteger fpUnsigned(long value) {
        BigInteger unsigned = BigInteger.valueOf(value);
        return value < 0 ? unsigned.add(BigInteger.ONE.shiftLeft(64)) : unsigned;
    }

    /**
     * Converts every storage class through {@link #getInt(int)}, matching sqlite-jdbc. NULL and values
     * whose integer coercion is zero are false: non-numeric text such as {@code "true"}, REAL values
     * between -1 and 1, and empty or non-numeric blobs. Integers, REAL values truncated to a nonzero
     * integer, and text or blob numeric prefixes such as {@code "2"} or {@code x'313233'} are true.
     */
    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return getInt(columnIndex) != 0;
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) getLong(columnIndex);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) getLong(columnIndex);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Value val = getCurrentValue(columnIndex);
        return switch (val) {
            case Value.Null n -> 0;
            case Value.Integer i -> (int) i.value();
            case Value.Float f -> (int) f.value();
            case Value.Text t -> (int) parseSqliteLong(t.value());
            case Value.Blob b -> (int) parseSqliteLong(blobAsText(b));
        };
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return toLong(getCurrentValue(columnIndex));
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        Value val = getCurrentValue(columnIndex);
        return switch (val) {
            case Value.Null n -> 0.0F;
            case Value.Integer i -> (float) i.value();
            case Value.Float f -> (float) f.value();
            case Value.Text t -> (float) parseSqliteDouble(t.value());
            case Value.Blob b -> (float) parseSqliteDouble(blobAsText(b));
        };
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return toDouble(getCurrentValue(columnIndex));
    }

    private static long toLong(Value val) {
        return switch (val) {
            case Value.Null n -> 0L;
            case Value.Integer i -> i.value();
            case Value.Float f -> (long) f.value();
            case Value.Text t -> parseSqliteLong(t.value());
            case Value.Blob b -> parseSqliteLong(blobAsText(b));
        };
    }

    private static double toDouble(Value val) {
        return switch (val) {
            case Value.Null n -> 0.0;
            case Value.Integer i -> (double) i.value();
            case Value.Float f -> f.value();
            case Value.Text t -> parseSqliteDouble(t.value());
            case Value.Blob b -> parseSqliteDouble(blobAsText(b));
        };
    }

    private static String blobAsText(Value.Blob b) {
        return new String(b.bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long parseSqliteLong(String text) {
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

    private static double parseSqliteDouble(String text) {
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

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return getBigDecimal(columnIndex);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        Value val = getCurrentValue(columnIndex);
        return switch (val) {
            case Value.Null n -> null;
            case Value.Blob b -> b.bytes();
            case Value.Text t -> t.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case Value.Integer i -> Long.toString(i.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case Value.Float f -> sqliteRealText(f.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) return null;
        try {
            return Date.valueOf(s);
        } catch (IllegalArgumentException e) {
            try {
                return new Date(Long.parseLong(s));
            } catch (NumberFormatException nfe) {
                return null;
            }
        }
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) return null;
        try {
            return Time.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) return null;
        try {
            return Timestamp.valueOf(s);
        } catch (IllegalArgumentException e) {
            try {
                return new Timestamp(Long.parseLong(s));
            } catch (NumberFormatException nfe) {
                return null;
            }
        }
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new SQLException("Bad value for type BigDecimal: " + s, e);
        }
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        Value val = getCurrentValue(columnIndex);
        return switch (val) {
            case Value.Null n -> null;
            case Value.Integer i -> i.value() >= Integer.MIN_VALUE && i.value() <= Integer.MAX_VALUE
                    ? (Object) (int) i.value()
                    : (Object) i.value();
            case Value.Float f -> f.value();
            case Value.Text t -> t.value();
            case Value.Blob b -> b.bytes();
        };
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkClosed();
        if (columnNameToIndex == null) {
            columnNameToIndex = new HashMap<>(cols.size() * 2);
            for (int i = 0; i < cols.size(); i++) {
                String name = cols.get(i).name();
                if (name != null) {
                    columnNameToIndex.putIfAbsent(name.toLowerCase(Locale.ROOT), i + 1);
                }
            }
        }
        Integer idx = columnNameToIndex.get(columnLabel.toLowerCase(Locale.ROOT));
        if (idx != null) {
            return idx;
        }
        throw new SQLException("no such column: '" + columnLabel + "'");
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkClosed();
        if (metadata == null) {
            metadata = new LibsqlResultSetMetaData(cols, rows, sql, connection, this);
        }
        return metadata;
    }

    @Override
    public Statement getStatement() throws SQLException {
        return statement;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public int getType() throws SQLException {
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getRow() throws SQLException {
        return Math.max(0, Math.min(currentRowIndex + 1, rows.size()));
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        return currentRowIndex == -1 && !rows.isEmpty();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        return currentRowIndex >= rows.size() && !rows.isEmpty();
    }

    @Override
    public boolean isFirst() throws SQLException {
        return currentRowIndex == 0 && !rows.isEmpty();
    }

    @Override
    public boolean isLast() throws SQLException {
        throw new SQLFeatureNotSupportedException("not supported by sqlite");
    }

    @Override public InputStream getAsciiStream(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override @Deprecated @SuppressWarnings("deprecation") public InputStream getUnicodeStream(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public InputStream getBinaryStream(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public InputStream getAsciiStream(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override @Deprecated @SuppressWarnings("deprecation") public InputStream getUnicodeStream(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public InputStream getBinaryStream(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public SQLWarning getWarnings() throws SQLException { return null; }
    @Override public void clearWarnings() throws SQLException {}
    @Override public String getCursorName() throws SQLException { return null; }
    @Override public Reader getCharacterStream(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Reader getCharacterStream(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void beforeFirst() throws SQLException { throw new SQLException("Forward only"); }
    @Override public void afterLast() throws SQLException { throw new SQLException("Forward only"); }
    @Override public boolean first() throws SQLException { throw new SQLException("Forward only"); }
    @Override public boolean last() throws SQLException { throw new SQLException("Forward only"); }
    @Override public boolean absolute(int row) throws SQLException { throw new SQLException("Forward only"); }
    @Override public boolean relative(int rows) throws SQLException { throw new SQLException("Forward only"); }
    @Override public boolean previous() throws SQLException { throw new SQLException("Forward only"); }
    @Override public void setFetchDirection(int direction) throws SQLException {}
    @Override public int getFetchDirection() throws SQLException { return FETCH_FORWARD; }
    @Override
    public void setFetchSize(int rows) throws SQLException {
        if (rows < 0) {
            throw new SQLException("fetch size " + rows + " out of bounds 0");
        }
        fetchSize = rows;
    }
    @Override public int getFetchSize() throws SQLException { return fetchSize; }
    @Override public boolean rowUpdated() throws SQLException { return false; }
    @Override public boolean rowInserted() throws SQLException { return false; }
    @Override public boolean rowDeleted() throws SQLException { return false; }
    @Override public void updateNull(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBoolean(int columnIndex, boolean x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateByte(int columnIndex, byte x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateShort(int columnIndex, short x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateInt(int columnIndex, int x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateLong(int columnIndex, long x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateFloat(int columnIndex, float x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateDouble(int columnIndex, double x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateString(int columnIndex, String x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBytes(int columnIndex, byte[] x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateDate(int columnIndex, Date x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateTime(int columnIndex, Time x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateObject(int columnIndex, Object x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNull(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBoolean(String columnLabel, boolean x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateByte(String columnLabel, byte x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateShort(String columnLabel, short x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateInt(String columnLabel, int x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateLong(String columnLabel, long x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateFloat(String columnLabel, float x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateDouble(String columnLabel, double x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateString(String columnLabel, String x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBytes(String columnLabel, byte[] x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateDate(String columnLabel, Date x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateTime(String columnLabel, Time x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateObject(String columnLabel, Object x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void insertRow() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateRow() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void deleteRow() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void refreshRow() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void cancelRowUpdates() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void moveToInsertRow() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void moveToCurrentRow() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException { return getObject(columnIndex); }
    @Override public Ref getRef(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Blob getBlob(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Clob getClob(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Array getArray(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException { return getObject(columnLabel); }
    @Override public Ref getRef(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Blob getBlob(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Clob getClob(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Array getArray(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Date getDate(int columnIndex, Calendar cal) throws SQLException { return getDate(columnIndex); }
    @Override public Date getDate(String columnLabel, Calendar cal) throws SQLException { return getDate(columnLabel); }
    @Override public Time getTime(int columnIndex, Calendar cal) throws SQLException { return getTime(columnIndex); }
    @Override public Time getTime(String columnLabel, Calendar cal) throws SQLException { return getTime(columnLabel); }
    @Override public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException { return getTimestamp(columnIndex); }
    @Override public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException { return getTimestamp(columnLabel); }
    @Override public URL getURL(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public URL getURL(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateRef(int columnIndex, Ref x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateRef(String columnLabel, Ref x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBlob(int columnIndex, Blob x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBlob(String columnLabel, Blob x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateClob(int columnIndex, Clob x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateClob(String columnLabel, Clob x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateArray(int columnIndex, Array x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateArray(String columnLabel, Array x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public RowId getRowId(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public RowId getRowId(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateRowId(int columnIndex, RowId x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateRowId(String columnLabel, RowId x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public int getHoldability() throws SQLException { return CLOSE_CURSORS_AT_COMMIT; }
    @Override public void updateNString(int columnIndex, String nString) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNString(String columnLabel, String nString) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNClob(int columnIndex, NClob nClob) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNClob(String columnLabel, NClob nClob) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public NClob getNClob(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public NClob getNClob(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public SQLXML getSQLXML(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public SQLXML getSQLXML(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public String getNString(int columnIndex) throws SQLException { return getString(columnIndex); }
    @Override public String getNString(String columnLabel) throws SQLException { return getString(columnLabel); }
    @Override public Reader getNCharacterStream(int columnIndex) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public Reader getNCharacterStream(String columnLabel) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateClob(int columnIndex, Reader reader, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateClob(String columnLabel, Reader reader, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateCharacterStream(int columnIndex, Reader x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateClob(int columnIndex, Reader reader) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateClob(String columnLabel, Reader reader) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNClob(int columnIndex, Reader reader) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void updateNClob(String columnLabel, Reader reader) throws SQLException { throw new UnsupportedOperationException(); }

    /**
     * Returns a column value converted to the requested Java type.
     *
     * <p>The conversion matrix matches sqlite-jdbc 3.53.4.0. Numeric wrapper targets are gated on
     * the current value's SQLite storage class; any other storage class raises {@code Bad value
     * for type Integer} (or the corresponding target type name):
     *
     * <table>
     * <caption>Target type by source storage class</caption>
     * <tr>
     *   <th>Target type</th><th>INTEGER</th><th>FLOAT</th><th>TEXT</th><th>BLOB</th><th>NULL</th>
     * </tr>
     * <tr>
     *   <td>{@code Integer}, {@code Long}, {@code Float}, {@code Double}</td>
     *   <td>converts</td><td>converts</td><td>bad value</td><td>bad value</td><td>bad value</td>
     * </tr>
     * <tr>
     *   <td>{@code String}</td>
     *   <td>canonical text</td><td>canonical text</td><td>text</td><td>UTF-8 text</td>
     *   <td>{@code null}</td>
     * </tr>
     * <tr>
     *   <td>{@code Boolean}</td>
     *   <td>nonzero</td><td>nonzero</td><td>integer coercion</td><td>integer coercion</td>
     *   <td>{@code false}</td>
     * </tr>
     * <tr>
     *   <td>{@code BigDecimal}</td>
     *   <td>decimal text</td><td>decimal text</td><td>decimal text</td><td>decimal text</td>
     *   <td>{@code null}</td>
     * </tr>
     * <tr>
     *   <td>{@code byte[]}</td>
     *   <td>UTF-8 text</td><td>UTF-8 text</td><td>UTF-8 text</td><td>bytes</td>
     *   <td>{@code null}</td>
     * </tr>
     * <tr>
     *   <td>{@code Date}, {@code Time}, {@code Timestamp}, {@code LocalDate}, {@code LocalTime},
     *   {@code LocalDateTime}</td>
     *   <td>text parse</td><td>text parse</td><td>text parse</td><td>text parse</td>
     *   <td>{@code null}</td>
     * </tr>
     * <tr>
     *   <td>{@code Short}, {@code Byte}, any other type</td>
     *   <td colspan="5">unsupported</td>
     * </tr>
     * </table>
     *
     * <p>{@code Short} and {@code Byte} are rejected rather than narrowed from the numeric
     * targets, preserving the sqlite-jdbc 3.53.4.0 contract.
     *
     * @throws SQLException if {@code type} is {@code null}, a numeric target reads a value from
     *     any storage class other than INTEGER or FLOAT, or the value cannot be converted
     * @throws SQLFeatureNotSupportedException if {@code type} is not a supported target type
     */
    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        if (type == null) {
            throw new SQLException("requested type cannot be null");
        }
        if (type == String.class) {
            return type.cast(getString(columnIndex));
        }
        if (type == Boolean.class) {
            return type.cast(getBoolean(columnIndex));
        }
        if (type == BigDecimal.class) {
            return type.cast(getBigDecimal(columnIndex));
        }
        if (type == byte[].class) {
            return type.cast(getBytes(columnIndex));
        }
        if (type == Date.class) {
            return type.cast(getDate(columnIndex));
        }
        if (type == Time.class) {
            return type.cast(getTime(columnIndex));
        }
        if (type == Timestamp.class) {
            return type.cast(getTimestamp(columnIndex));
        }
        if (type == LocalDate.class) {
            return type.cast(getLocalDate(columnIndex));
        }
        if (type == LocalTime.class) {
            return type.cast(getLocalTime(columnIndex));
        }
        if (type == LocalDateTime.class) {
            return type.cast(getLocalDateTime(columnIndex));
        }

        Value stored = getCurrentValue(columnIndex);
        boolean numeric = stored instanceof Value.Integer || stored instanceof Value.Float;
        if (type == Double.class) {
            if (numeric) {
                return type.cast(getDouble(columnIndex));
            }
            throw new SQLException("Bad value for type Double");
        }
        if (type == Long.class) {
            if (numeric) {
                return type.cast(getLong(columnIndex));
            }
            throw new SQLException("Bad value for type Long");
        }
        if (type == Float.class) {
            if (numeric) {
                return type.cast(getFloat(columnIndex));
            }
            throw new SQLException("Bad value for type Float");
        }
        if (type == Integer.class) {
            if (numeric) {
                return type.cast(getInt(columnIndex));
            }
            throw new SQLException("Bad value for type Integer");
        }

        throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver");
    }

    private LocalDate getLocalDate(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            throw new SQLException("Bad value for type LocalDate: " + s, e);
        }
    }

    private LocalTime getLocalTime(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) {
            return null;
        }
        int separator = Math.max(s.indexOf(' '), s.indexOf('T'));
        String value = separator >= 0 ? s.substring(separator + 1) : s;
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new SQLException("Bad value for type LocalTime: " + s, e);
        }
    }

    private LocalDateTime getLocalDateTime(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) {
            return null;
        }
        String value = s.indexOf(' ') >= 0 ? s.replace(' ', 'T') : s;
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (DateTimeParseException e2) {
                throw new SQLException("Bad value for type LocalDateTime: " + s, e2);
            }
        }
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
}
