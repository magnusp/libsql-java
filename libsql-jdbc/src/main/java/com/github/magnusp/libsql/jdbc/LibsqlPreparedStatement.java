package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.io.ByteArrayOutputStream;
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
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Prepared statement over the Hrana transport.
 *
 * <p>Post-close transitions follow {@link LibsqlStatement}: {@link #addBatch()} and {@link
 * #getGeneratedKeys()} throw after {@link #close()}. A prepared execute already throws once closed,
 * so there is no post-close execute that could expose a result set. sqlite-jdbc 3.53.4.0 instead
 * returns generated keys, and rejects {@code addBatch()} with a different message; see issue #87.
 *
 * <p>The stream and {@link Blob}/{@link Clob} setters convert to a TEXT or BLOB value locally. An
 * unset parameter, including one released by {@link #clearParameters()}, binds SQL NULL. Parameter
 * metadata lives in {@link ParameterMetaDataView}.
 */
public class LibsqlPreparedStatement extends LibsqlStatement implements PreparedStatement, ParameterMetaData {

    private final String sql;
    private final boolean returnsResultSet;
    private final int parameterCount;
    private final ParameterMetaDataView parameterMetaData;
    // Sequential list indexed by JDBC parameter position; no map or boxing overhead.
    private final List<Value> parameters = new ArrayList<>();

    public LibsqlPreparedStatement(LibsqlConnection connection, String sql) {
        super(connection);
        this.sql = sql;
        this.returnsResultSet = returnsResultSet(sql);
        this.parameterCount = SqlTokenizer.placeholderCount(sql);
        this.parameterMetaData = new ParameterMetaDataView(this, parameterCount);
    }

    private void setParam(int parameterIndex, Value value) throws SQLException {
        checkClosed();
        int idx = parameterIndex - 1;
        while (parameters.size() <= idx) {
            parameters.add(Value.ofNull());
        }
        parameters.set(idx, value);
    }

    private Stmt buildCurrentStmt() {
        List<Value> args = new ArrayList<>(parameters);
        while (args.size() < parameterCount) {
            // An unset parameter, including one released by clearParameters, binds SQL NULL.
            args.add(Value.ofNull());
        }
        return new Stmt(sql, args);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkClosed();
        if (!returnsResultSet) {
            throw new SQLException("Query does not return results");
        }
        StmtResult result = executeCurrent();
        if (result.cols() == null || result.cols().isEmpty()) {
            throw new SQLException("Query does not return results");
        }
        LibsqlResultSet resultSet = newQueryResult(sql, result);
        assignResultSet(resultSet, result, sql, true);
        return resultSet;
    }

    @Override
    public int executeUpdate() throws SQLException {
        checkClosed();
        if (returnsResultSet) {
            throw new SQLException("Query returns results");
        }
        execute();
        return snapshotUpdateCount();
    }

    private static boolean returnsResultSet(String sql) {
        if (sql == null) {
            return false;
        }
        List<SqlTokenizer.Token> tokens = SqlTokenizer.tokenize(sql);
        if (tokens == null) {
            return false;
        }
        String first = SqlTokenizer.wordAt(tokens, 0, 0);
        if (first == null) {
            return false;
        }
        return switch (first) {
            case "SELECT", "VALUES", "EXPLAIN" -> true;
            case "PRAGMA" -> pragmaReturnsResultSet(tokens);
            case "INSERT", "UPDATE", "DELETE", "REPLACE" -> hasTopLevelReturning(tokens, 1);
            case "WITH" -> withReturnsResultSet(tokens);
            default -> false;
        };
    }

    private static boolean pragmaReturnsResultSet(List<SqlTokenizer.Token> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            if (SqlTokenizer.isSymbolAt(tokens, i, "=", 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean withReturnsResultSet(List<SqlTokenizer.Token> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            String word = SqlTokenizer.wordAt(tokens, i, 0);
            if (word == null) {
                continue;
            }
            switch (word) {
                case "SELECT", "VALUES" -> {
                    return true;
                }
                case "INSERT", "UPDATE", "DELETE", "REPLACE" -> {
                    return hasTopLevelReturning(tokens, i + 1);
                }
                default -> {
                }
            }
        }
        return false;
    }

    private static boolean hasTopLevelReturning(List<SqlTokenizer.Token> tokens, int from) {
        for (int i = from; i < tokens.size(); i++) {
            if (SqlTokenizer.isWordAt(tokens, i, 0, "RETURNING")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean execute() throws SQLException {
        return executeResult(sql, executeCurrent());
    }

    private StmtResult executeCurrent() throws SQLException {
        checkEscapeProcessing(sql);
        checkClosed();
        beginExecution();
        return connection.executeStatement(buildCurrentStmt());
    }

    @Override
    public void addBatch() throws SQLException {
        checkClosed();
        batchStatements.add(buildCurrentStmt());
    }

    @Override
    public void clearParameters() throws SQLException {
        checkClosed();
        parameters.clear();
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        setParam(parameterIndex, Value.ofNull());
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        setParam(parameterIndex, Value.of(x ? 1L : 0L));
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        setParam(parameterIndex, Value.of((long) x));
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        setParam(parameterIndex, Value.of((long) x));
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        setParam(parameterIndex, Value.of((long) x));
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        setParam(parameterIndex, Value.of(x));
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        setParam(parameterIndex, Value.of((double) x));
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        setParam(parameterIndex, Value.of(x));
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        setParam(parameterIndex, x == null ? Value.ofNull() : Value.of(x.toString()));
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        setParam(parameterIndex, Value.of(x));
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        setParam(parameterIndex, Value.of(x));
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        setParam(parameterIndex, x == null ? Value.ofNull() : Value.of(x.toString()));
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        setParam(parameterIndex, x == null ? Value.ofNull() : Value.of(x.toString()));
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        setParam(parameterIndex, x == null ? Value.ofNull() : Value.of(x.toString()));
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        setParam(parameterIndex, JdbcValueConverter.toValue(x));
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setParam(parameterIndex, JdbcValueConverter.toValue(x, targetSqlType));
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        // Hrana has no scale or length for a parameter; the target type still decides the value.
        setParam(parameterIndex, JdbcValueConverter.toValue(x, targetSqlType));
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setParam(parameterIndex, asciiText(readBytes(x, length)));
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setParam(parameterIndex, utf8Text(readBytes(x, length)));
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setParam(parameterIndex, Value.of(readBytes(x, length)));
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        setParam(parameterIndex, Value.of(readCharacters(reader, length)));
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        throw new SQLFeatureNotSupportedException("Ref parameters are not supported");
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        setParam(parameterIndex, Value.of(x == null ? null : readAllBytes(x.getBinaryStream())));
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        setParam(parameterIndex, Value.of(x == null ? null : readAllCharacters(x.getCharacterStream())));
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        throw new SQLFeatureNotSupportedException("Array parameters are not supported");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return currentResultSet != null ? currentResultSet.getMetaData() : null;
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        setDate(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        setTime(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        setTimestamp(parameterIndex, x);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        setNull(parameterIndex, sqlType);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        setString(parameterIndex, x != null ? x.toString() : null);
    }

    @Override public ParameterMetaData getParameterMetaData() throws SQLException { checkClosed(); return parameterMetaData; }

    // ParameterMetaData stays on the statement for instanceof compatibility; the view holds the checks.
    @Override public int getParameterCount() throws SQLException { return parameterMetaData.getParameterCount(); }
    @Override public int isNullable(int param) throws SQLException { return parameterMetaData.isNullable(param); }
    @Override public boolean isSigned(int param) throws SQLException { return parameterMetaData.isSigned(param); }
    @Override public int getPrecision(int param) throws SQLException { return parameterMetaData.getPrecision(param); }
    @Override public int getScale(int param) throws SQLException { return parameterMetaData.getScale(param); }
    @Override public int getParameterType(int param) throws SQLException { return parameterMetaData.getParameterType(param); }
    @Override public String getParameterTypeName(int param) throws SQLException { return parameterMetaData.getParameterTypeName(param); }
    @Override public String getParameterClassName(int param) throws SQLException { return parameterMetaData.getParameterClassName(param); }
    @Override public int getParameterMode(int param) throws SQLException { return parameterMetaData.getParameterMode(param); }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        throw new SQLFeatureNotSupportedException("RowId parameters are not supported");
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        setString(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        setCharacterStream(parameterIndex, value, length);
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        setClob(parameterIndex, (Clob) value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setParam(parameterIndex, Value.of(readCharacters(reader, length)));
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        setParam(parameterIndex, Value.of(readBytes(inputStream, length)));
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setClob(parameterIndex, reader, length);
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLXML parameters are not supported");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setParam(parameterIndex, asciiText(readBytes(x, length)));
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setParam(parameterIndex, Value.of(readBytes(x, length)));
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        setParam(parameterIndex, Value.of(readCharacters(reader, length)));
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        setParam(parameterIndex, asciiText(readAllBytes(x)));
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        setParam(parameterIndex, Value.of(readAllBytes(x)));
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        setParam(parameterIndex, Value.of(readAllCharacters(reader)));
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        setCharacterStream(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        setParam(parameterIndex, Value.of(readAllCharacters(reader)));
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        setParam(parameterIndex, Value.of(readAllBytes(inputStream)));
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        setClob(parameterIndex, reader);
    }

    private static Value asciiText(byte[] bytes) {
        return Value.of(bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII));
    }

    private static Value utf8Text(byte[] bytes) {
        return Value.of(bytes == null ? null : new String(bytes, StandardCharsets.UTF_8));
    }

    private static byte[] readAllBytes(InputStream in) throws SQLException {
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

    private static byte[] readBytes(InputStream in, long length) throws SQLException {
        if (in == null) {
            return null;
        }
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new SQLException("Data must have a length between 0 and Integer.MAX_VALUE");
        }
        byte[] bytes = new byte[(int) length];
        int total = 0;
        try {
            while (total < bytes.length) {
                int read = in.read(bytes, total, bytes.length - total);
                if (read < 0) {
                    throw new SQLException("End of stream reached before " + length + " bytes were read");
                }
                total += read;
            }
        } catch (IOException e) {
            throw new SQLException("Cannot read from binary stream: " + e.getMessage(), e);
        }
        return bytes;
    }

    private static String readAllCharacters(Reader reader) throws SQLException {
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

    private static String readCharacters(Reader reader, long length) throws SQLException {
        if (reader == null) {
            return null;
        }
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new SQLException("Data must have a length between 0 and Integer.MAX_VALUE");
        }
        StringBuilder text = new StringBuilder((int) Math.min(length, 8192));
        char[] buffer = new char[8192];
        long remaining = length;
        try {
            while (remaining > 0) {
                int read = reader.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (read < 0) {
                    throw new SQLException(
                            "End of stream reached before " + length + " characters were read");
                }
                text.append(buffer, 0, read);
                remaining -= read;
            }
        } catch (IOException e) {
            throw new SQLException("Cannot read from character stream: " + e.getMessage(), e);
        }
        return text.toString();
    }
}
