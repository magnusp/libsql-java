package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
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
 * Unsupported optional features throw {@link SQLFeatureNotSupportedException}, and {@link
 * #getBinaryStream}, {@link #getCharacterStream}, {@link #getBlob}, and {@link #getClob}
 * materialize Hrana values locally.
 */
public class LibsqlResultSet implements ResultSet {

    private final Statement statement;
    private final List<Col> cols;
    private final List<Row> rows;
    private final String sql;
    private final LibsqlConnection connection;
    private final boolean closeStatementOnClose;
    private final JdbcTypeMapping.JdbcType[] declaredTypes;
    private int currentRowIndex = -1;
    private int lastColumnIndex = 0;
    private boolean closed = false;
    private int fetchSize = 0;
    private LibsqlResultSetMetaData metadata;

    // O(1) column label lookup; the map is built lazily on first use.
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
        this.declaredTypes = JdbcTypeMapping.declaredTypes(this.cols);
    }

    JdbcTypeMapping.JdbcType[] declaredTypes() {
        return declaredTypes;
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
        closeDetached();
        if (statement != null
                && (closeStatementOnClose
                        || (statement instanceof LibsqlStatement s && s.isCloseOnCompletion()))) {
            statement.close();
        }
    }

    /**
     * Marks this result set closed without calling back into the statement. The owning statement
     * calls this while it already holds its own lock, so closing a result set never re-enters it and
     * never observes the statement as reopened.
     */
    void closeDetached() {
        this.closed = true;
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
        return stringValue(getCurrentValue(columnIndex));
    }

    private static String stringValue(Value val) {
        return switch (val) {
            case Value.Null n -> null;
            case Value.Integer i -> Long.toString(i.value());
            case Value.Float f -> SqliteRealText.format(f.value());
            case Value.Text t -> t.value();
            case Value.Blob b -> new String(b.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    /**
     * Converts every storage class through {@link #getInt(int)}, matching sqlite-jdbc, except that
     * the text values {@code true} and {@code false} are parsed as booleans. NULL and values whose
     * integer coercion is zero are false: numeric text such as {@code "0"}, REAL values between -1
     * and 1, and empty or non-numeric blobs. Integers, REAL values truncated to a nonzero integer,
     * and text or blob numeric prefixes such as {@code "2"} or {@code x'313233'} are true.
     */
    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return booleanValue(getCurrentValue(columnIndex));
    }

    private static boolean booleanValue(Value value) {
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
        return (int) toLong(getCurrentValue(columnIndex));
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return toLong(getCurrentValue(columnIndex));
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return (float) toDouble(getCurrentValue(columnIndex));
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
            case Value.Text t -> SqliteNumericText.parseLong(t.value());
            case Value.Blob b ->
                    SqliteNumericText.parseLong(SqliteNumericText.blobAsText(b.bytes()));
        };
    }

    private static double toDouble(Value val) {
        return switch (val) {
            case Value.Null n -> 0.0;
            case Value.Integer i -> (double) i.value();
            case Value.Float f -> f.value();
            case Value.Text t -> SqliteNumericText.parseDouble(t.value());
            case Value.Blob b ->
                    SqliteNumericText.parseDouble(SqliteNumericText.blobAsText(b.bytes()));
        };
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return getBigDecimal(columnIndex);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return bytesValue(getCurrentValue(columnIndex));
    }

    private static byte[] bytesValue(Value val) {
        return switch (val) {
            case Value.Null n -> null;
            case Value.Blob b -> b.bytes();
            case Value.Text t -> t.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case Value.Integer i -> Long.toString(i.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case Value.Float f -> SqliteRealText.format(f.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return dateValue(getCurrentValue(columnIndex));
    }

    private static Date dateValue(Value value) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "Date");
        return dateTime == null ? null : Date.valueOf(dateTime.toLocalDate());
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return timeValue(getCurrentValue(columnIndex));
    }

    private static Time timeValue(Value value) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "Time");
        return dateTime == null ? null : Time.valueOf(dateTime.toLocalTime());
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return timestampValue(getCurrentValue(columnIndex));
    }

    private static Timestamp timestampValue(Value value) throws SQLException {
        LocalDateTime dateTime = dateTimeValue(value, "Timestamp");
        return dateTime == null ? null : Timestamp.valueOf(dateTime);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return bigDecimalValue(getCurrentValue(columnIndex));
    }

    private static BigDecimal bigDecimalValue(Value value) throws SQLException {
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

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        Value value = getCurrentValue(columnIndex);
        if (value instanceof Value.Null) {
            return null;
        }
        JdbcTypeMapping.JdbcType type = declaredTypes[columnIndex - 1];
        if (type == null) {
            type = JdbcTypeMapping.fallback(value);
        }
        // Shortcuts for a value already stored as the declared Java box. They mirror the storage
        // switch trunk used, so a matching column costs one class test and one type test.
        if (value instanceof Value.Integer integer && type.objectClass() == Integer.class) {
            return checkedInt(integer.value(), type.sqlType());
        }
        if (value instanceof Value.Float floating) {
            if (type.objectClass() == Float.class) {
                return (float) floating.value();
            }
            if (type.objectClass() == Double.class) {
                return floating.value();
            }
        }
        if (value instanceof Value.Text text && type.objectClass() == String.class) {
            return text.value();
        }
        return objectValue(value, type);
    }

    private static Object objectValue(Value value, JdbcTypeMapping.JdbcType type) throws SQLException {
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
     * Converts every declared type outside the numeric and text fast path. It stays out of {@link
     * #getObject} so the common reads stay small enough for the JIT to inline and scalar-replace
     * their boxes.
     */
    private static Object convertedObject(Value value, JdbcTypeMapping.JdbcType type) throws SQLException {
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
        if (objectClass == Clob.class) {
            return clobValue(value);
        }
        if (objectClass == Blob.class) {
            return blobValue(value);
        }
        // NCLOB and DATALINK resolve to their declared wrapper types, but the wrapper getters
        // arrive in PR-3, so the value keeps its storage-class representation until then.
        return storedObject(value);
    }

    private static Object storedObject(Value value) {
        return switch (value) {
            case Value.Null n -> null;
            case Value.Integer i -> i.value() >= Integer.MIN_VALUE && i.value() <= Integer.MAX_VALUE
                    ? (Object) (int) i.value()
                    : (Object) i.value();
            case Value.Float f -> f.value();
            case Value.Text t -> t.value();
            case Value.Blob b -> b.bytes();
        };
    }

    private static Integer checkedInt(Value value, int sqlType) throws SQLException {
        return checkedInt(toLong(value), sqlType);
    }

    private static Integer checkedInt(long number, int sqlType) throws SQLException {
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new SQLException(
                    "Value " + number + " does not fit the declared " + sqlTypeName(sqlType) + " type");
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

    private static void rejectTypeMap(Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw new SQLFeatureNotSupportedException("Type maps are not supported");
        }
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkClosed();
        if (columnLabel == null) {
            throw new SQLException("column label cannot be null");
        }
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

    @Override public InputStream getAsciiStream(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override @Deprecated @SuppressWarnings("deprecation") public InputStream getUnicodeStream(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] bytes = getBytes(columnIndex);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }
    @Override public InputStream getAsciiStream(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override @Deprecated @SuppressWarnings("deprecation") public InputStream getUnicodeStream(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public InputStream getBinaryStream(String columnLabel) throws SQLException { return getBinaryStream(findColumn(columnLabel)); }
    @Override public SQLWarning getWarnings() throws SQLException { return null; }
    @Override public void clearWarnings() throws SQLException {}
    @Override public String getCursorName() throws SQLException { return null; }
    @Override public Reader getCharacterStream(int columnIndex) throws SQLException {
        String string = getString(columnIndex);
        return string == null ? null : new StringReader(string);
    }
    @Override public Reader getCharacterStream(String columnLabel) throws SQLException { return getCharacterStream(findColumn(columnLabel)); }
    @Override public void beforeFirst() throws SQLException { throw new SQLException("Forward only"); }
    @Override public void afterLast() throws SQLException { throw new SQLException("Forward only"); }
    @Override public boolean first() throws SQLException { throw new SQLException("Forward only"); }
    @Override public boolean last() throws SQLException { throw new SQLException("Forward only"); }
    @Override public boolean absolute(int row) throws SQLException { throw new SQLException("Forward only"); }
    @Override public boolean relative(int rows) throws SQLException { throw new SQLException("Forward only"); }
    @Override public boolean previous() throws SQLException { throw new SQLException("Forward only"); }
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        if (direction != FETCH_FORWARD && direction != FETCH_REVERSE && direction != FETCH_UNKNOWN) {
            throw new SQLException(
                    "Unknown fetch direction "
                            + direction
                            + ". Must be one of FETCH_FORWARD, FETCH_REVERSE, or FETCH_UNKNOWN");
        }
    }
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
    @Override public void updateNull(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBoolean(int columnIndex, boolean x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateByte(int columnIndex, byte x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateShort(int columnIndex, short x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateInt(int columnIndex, int x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateLong(int columnIndex, long x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateFloat(int columnIndex, float x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateDouble(int columnIndex, double x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateString(int columnIndex, String x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBytes(int columnIndex, byte[] x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateDate(int columnIndex, Date x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateTime(int columnIndex, Time x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateObject(int columnIndex, Object x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNull(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBoolean(String columnLabel, boolean x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateByte(String columnLabel, byte x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateShort(String columnLabel, short x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateInt(String columnLabel, int x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateLong(String columnLabel, long x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateFloat(String columnLabel, float x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateDouble(String columnLabel, double x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateString(String columnLabel, String x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBytes(String columnLabel, byte[] x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateDate(String columnLabel, Date x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateTime(String columnLabel, Time x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateObject(String columnLabel, Object x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void insertRow() throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateRow() throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void deleteRow() throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void refreshRow() throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void cancelRowUpdates() throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void moveToInsertRow() throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void moveToCurrentRow() throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        rejectTypeMap(map);
        return getObject(columnIndex);
    }
    @Override public Ref getRef(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public Blob getBlob(int columnIndex) throws SQLException {
        return blobValue(getCurrentValue(columnIndex));
    }
    private static Blob blobValue(Value value) {
        byte[] bytes = bytesValue(value);
        return bytes == null ? null : new LibsqlBlob(bytes);
    }
    @Override public Clob getClob(int columnIndex) throws SQLException {
        return clobValue(getCurrentValue(columnIndex));
    }
    private static Clob clobValue(Value value) {
        String string = stringValue(value);
        return string == null ? null : new LibsqlClob(string);
    }
    @Override public Array getArray(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        rejectTypeMap(map);
        return getObject(columnLabel);
    }
    @Override public Ref getRef(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public Blob getBlob(String columnLabel) throws SQLException { return getBlob(findColumn(columnLabel)); }
    @Override public Clob getClob(String columnLabel) throws SQLException { return getClob(findColumn(columnLabel)); }
    @Override public Array getArray(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public Date getDate(int columnIndex, Calendar cal) throws SQLException { return getDate(columnIndex); }
    @Override public Date getDate(String columnLabel, Calendar cal) throws SQLException { return getDate(columnLabel); }
    @Override public Time getTime(int columnIndex, Calendar cal) throws SQLException { return getTime(columnIndex); }
    @Override public Time getTime(String columnLabel, Calendar cal) throws SQLException { return getTime(columnLabel); }
    @Override public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException { return getTimestamp(columnIndex); }
    @Override public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException { return getTimestamp(columnLabel); }
    @Override public URL getURL(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public URL getURL(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateRef(int columnIndex, Ref x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateRef(String columnLabel, Ref x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBlob(int columnIndex, Blob x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBlob(String columnLabel, Blob x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateClob(int columnIndex, Clob x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateClob(String columnLabel, Clob x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateArray(int columnIndex, Array x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateArray(String columnLabel, Array x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public RowId getRowId(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public RowId getRowId(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateRowId(int columnIndex, RowId x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateRowId(String columnLabel, RowId x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public int getHoldability() throws SQLException { return CLOSE_CURSORS_AT_COMMIT; }
    @Override public void updateNString(int columnIndex, String nString) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNString(String columnLabel, String nString) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNClob(int columnIndex, NClob nClob) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNClob(String columnLabel, NClob nClob) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public NClob getNClob(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public NClob getNClob(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public SQLXML getSQLXML(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public SQLXML getSQLXML(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public String getNString(int columnIndex) throws SQLException { return getString(columnIndex); }
    @Override public String getNString(String columnLabel) throws SQLException { return getString(columnLabel); }
    @Override public Reader getNCharacterStream(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public Reader getNCharacterStream(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateClob(int columnIndex, Reader reader, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateClob(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateClob(int columnIndex, Reader reader) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateClob(String columnLabel, Reader reader) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNClob(int columnIndex, Reader reader) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public void updateNClob(String columnLabel, Reader reader) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }

    /**
     * Returns a column value converted to the requested Java type.
     *
     * <p>The numeric and text conversions match sqlite-jdbc 3.53.4.0. Date and time conversions
     * follow the SQLite storage-class conventions instead of parsing the value's text form.
     * Numeric wrapper targets are gated on the current value's SQLite storage class; any other
     * storage class raises {@code Bad value for type Integer} (or the corresponding target type
     * name):
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
     *   <td>epoch millis</td><td>Julian day</td><td>date/time text</td><td>bad value</td>
     *   <td>{@code null}</td>
     * </tr>
     * <tr>
     *   <td>{@code Short}, {@code Byte}, any other type</td>
     *   <td colspan="5">unsupported</td>
     * </tr>
     * </table>
     *
     * <p>INTEGER date and time values are milliseconds since the Unix epoch, FLOAT values are
     * Julian days, and TEXT values use the SQLite date and time formats, including a bare date and
     * a bare time. A bare time uses SQLite's 2000-01-01 default date. The SQLite TEXT format is
     * {@code yyyy-MM-dd HH:mm:ss.SSS}, with optional fractional digits and an optional time zone
     * suffix. All six date and time targets are built from the same storage-class value, so
     * {@code getObject(column, LocalDate.class)} and {@code getDate(column)} agree.
     *
     * <p>{@code Short} and {@code Byte} are rejected rather than narrowed from the numeric
     * targets, preserving the sqlite-jdbc 3.53.4.0 contract.
     *
     * @throws SQLException if {@code type} is {@code null}, a numeric target reads a value from
     *     any storage class other than INTEGER or FLOAT, a date or time target reads a BLOB or
     *     unparseable TEXT, or the value cannot be converted
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
        LocalDateTime value = dateTimeValue(getCurrentValue(columnIndex), "LocalDate");
        return value == null ? null : value.toLocalDate();
    }

    private LocalTime getLocalTime(int columnIndex) throws SQLException {
        LocalDateTime value = dateTimeValue(getCurrentValue(columnIndex), "LocalTime");
        return value == null ? null : value.toLocalTime();
    }

    private LocalDateTime getLocalDateTime(int columnIndex) throws SQLException {
        return dateTimeValue(getCurrentValue(columnIndex), "LocalDateTime");
    }

    /**
     * Reads a value as a local date and time using SQLite storage-class conventions: INTEGER is
     * epoch milliseconds, FLOAT is a Julian day, and TEXT is a SQLite date/time string.
     */
    private static LocalDateTime dateTimeValue(Value value, String targetType) throws SQLException {
        return SqliteDateTimeValues.from(value, targetType);
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
