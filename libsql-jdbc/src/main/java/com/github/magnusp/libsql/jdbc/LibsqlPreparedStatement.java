package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
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
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class LibsqlPreparedStatement extends LibsqlStatement implements PreparedStatement {

    private final String sql;
    // Mitigation for Bug 7: Sequential ArrayList instead of Map to eliminate Integer autoboxing overhead
    private final List<Value> parameters = new ArrayList<>();

    public LibsqlPreparedStatement(LibsqlConnection connection, String sql) {
        super(connection);
        this.sql = sql;
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
        return new Stmt(sql, new ArrayList<>(parameters));
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        execute();
        if (currentResultSet == null) {
            throw new SQLException("PreparedStatement did not produce a ResultSet: " + sql);
        }
        return currentResultSet;
    }

    @Override
    public int executeUpdate() throws SQLException {
        execute();
        return (int) updateCount;
    }

    @Override
    public boolean execute() throws SQLException {
        checkClosed();
        StmtResult result = connection.executeStatement(buildCurrentStmt());
        if (result.cols() != null && !result.cols().isEmpty()) {
            this.currentResultSet = new LibsqlResultSet(this, result);
            this.updateCount = -1;
            return true;
        } else {
            this.currentResultSet = null;
            this.updateCount = result.affectedRowCount();
            return false;
        }
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
        if (x == null) {
            setNull(parameterIndex, 0);
        } else if (x instanceof String s) {
            setString(parameterIndex, s);
        } else if (x instanceof Long l) {
            setLong(parameterIndex, l);
        } else if (x instanceof Integer i) {
            setInt(parameterIndex, i);
        } else if (x instanceof Double d) {
            setDouble(parameterIndex, d);
        } else if (x instanceof Float f) {
            setFloat(parameterIndex, f);
        } else if (x instanceof Boolean b) {
            setBoolean(parameterIndex, b);
        } else if (x instanceof byte[] bytes) {
            setBytes(parameterIndex, bytes);
        } else if (x instanceof BigDecimal bd) {
            setBigDecimal(parameterIndex, bd);
        } else {
            setString(parameterIndex, x.toString());
        }
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override @Deprecated @SuppressWarnings("deprecation") public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setRef(int parameterIndex, Ref x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setBlob(int parameterIndex, Blob x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setClob(int parameterIndex, Clob x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setArray(int parameterIndex, Array x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSetMetaData getMetaData() throws SQLException { return currentResultSet != null ? currentResultSet.getMetaData() : null; }
    @Override public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException { setDate(parameterIndex, x); }
    @Override public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException { setTime(parameterIndex, x); }
    @Override public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException { setTimestamp(parameterIndex, x); }
    @Override public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException { setNull(parameterIndex, sqlType); }
    @Override public void setURL(int parameterIndex, URL x) throws SQLException { setString(parameterIndex, x != null ? x.toString() : null); }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setRowId(int parameterIndex, RowId x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setNString(int parameterIndex, String value) throws SQLException { setString(parameterIndex, value); }
    @Override public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setNClob(int parameterIndex, NClob value) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setClob(int parameterIndex, Reader reader, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setClob(int parameterIndex, Reader reader) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public void setNClob(int parameterIndex, Reader reader) throws SQLException { throw new UnsupportedOperationException(); }
}
