package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class LibsqlStatement implements Statement {

    protected final LibsqlConnection connection;
    protected LibsqlResultSet currentResultSet = null;
    protected long updateCount = -1;
    protected boolean closed = false;
    protected boolean closeOnCompletion = false;
    protected final List<Stmt> batchStatements = new ArrayList<>();
    private ResultSet generatedKeys = null;

    public LibsqlStatement(LibsqlConnection connection) {
        this.connection = connection;
    }

    protected void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("Statement is closed");
        }
        connection.checkClosed();
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        StmtResult result = executeSql(sql);
        if (result.cols() == null || result.cols().isEmpty()) {
            throw new SQLException("Query did not produce a ResultSet: " + sql);
        }
        this.currentResultSet = new LibsqlResultSet(this, result, sql, false);
        this.updateCount = -1;
        return currentResultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        execute(sql);
        return (int) updateCount;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        StmtResult result = executeSql(sql);
        updateGeneratedKeys(sql, result);
        if (result.cols() != null && !result.cols().isEmpty()) {
            this.currentResultSet = new LibsqlResultSet(this, result, sql, false);
            this.updateCount = -1;
            return true;
        } else {
            this.currentResultSet = null;
            this.updateCount = result.affectedRowCount();
            return false;
        }
    }

    private StmtResult executeSql(String sql) throws SQLException {
        checkClosed();
        closeCurrentResultSet();
        checkClosed();
        return connection.executeStatement(new Stmt(sql));
    }

    protected void closeCurrentResultSet() throws SQLException {
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
        }
    }

    protected void updateGeneratedKeys(String sql, StmtResult result) throws SQLException {
        clearGeneratedKeys();
        if (sql != null && result != null && result.lastInsertRowid() != null && isInsertSql(sql)) {
            long rowid = result.lastInsertRowid();
            StmtResult keys = new StmtResult(
                    List.of(new Col("last_insert_rowid()", null)),
                    List.of(new Row(List.of(Value.of(rowid)))),
                    0,
                    rowid);
            generatedKeys = new LibsqlResultSet(this, keys);
        }
    }

    protected void clearGeneratedKeys() throws SQLException {
        if (generatedKeys != null) {
            generatedKeys.close();
            generatedKeys = null;
        }
    }

    private static boolean isInsertSql(String sql) {
        int start = skipWhitespace(sql, 0);
        if (keywordAt(sql, start, "INSERT") || keywordAt(sql, start, "REPLACE")) {
            return true;
        }
        if (!keywordAt(sql, start, "WITH")) {
            return false;
        }
        int afterWith = start + "WITH".length();
        if (afterWith >= sql.length() || !Character.isWhitespace(sql.charAt(afterWith))) {
            return false;
        }

        int depth = 0;
        int i = afterWith;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length()
                        && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(sql.length(), i + 2);
            } else if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
            } else if (c == '[') {
                i++;
                while (i < sql.length() && sql.charAt(i) != ']') {
                    i++;
                }
                if (i < sql.length()) {
                    i++;
                }
            } else if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                i++;
                if (depth == 0) {
                    int next = skipWhitespace(sql, i);
                    if (keywordAt(sql, next, "INSERT") || keywordAt(sql, next, "REPLACE")) {
                        return true;
                    }
                }
            } else {
                i++;
            }
        }
        return false;
    }

    private static int skipWhitespace(String sql, int start) {
        int i = start;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    private static boolean keywordAt(String sql, int index, String keyword) {
        return index + keyword.length() <= sql.length()
                && sql.regionMatches(true, index, keyword, 0, keyword.length());
    }

    private ResultSet emptyGeneratedKeys() {
        StmtResult empty = new StmtResult(
                List.of(new Col("1", null)),
                List.of(),
                0,
                null);
        return new LibsqlResultSet(this, empty);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkClosed();
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkClosed();
        return (int) updateCount;
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkClosed();
        return updateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        checkClosed();
        closeCurrentResultSet();
        updateCount = -1;
        return false;
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        checkClosed();
        batchStatements.add(new Stmt(sql));
    }

    @Override
    public void clearBatch() throws SQLException {
        checkClosed();
        batchStatements.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        checkClosed();
        try {
            return connection.executeBatch(batchStatements);
        } finally {
            clearBatch();
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkClosed();
        return connection;
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            clearGeneratedKeys();
            if (currentResultSet != null) {
                currentResultSet.close();
                currentResultSet = null;
            }
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override public int getMaxFieldSize() throws SQLException { return 0; }
    @Override public void setMaxFieldSize(int max) throws SQLException {}
    @Override public int getMaxRows() throws SQLException { return 0; }
    @Override public void setMaxRows(int max) throws SQLException {}
    @Override public void setEscapeProcessing(boolean enable) throws SQLException {}
    @Override public int getQueryTimeout() throws SQLException { return 0; }
    @Override public void setQueryTimeout(int seconds) throws SQLException {}
    @Override public void cancel() throws SQLException {}
    @Override public SQLWarning getWarnings() throws SQLException { return null; }
    @Override public void clearWarnings() throws SQLException {}
    @Override public void setCursorName(String name) throws SQLException {}
    @Override public void setFetchDirection(int direction) throws SQLException {}
    @Override public int getFetchDirection() throws SQLException { return ResultSet.FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) throws SQLException {}
    @Override public int getFetchSize() throws SQLException { return 0; }
    @Override public int getResultSetConcurrency() throws SQLException { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getResultSetType() throws SQLException { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public boolean getMoreResults(int current) throws SQLException { return getMoreResults(); }
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkClosed();
        if (generatedKeys == null) {
            generatedKeys = emptyGeneratedKeys();
        }
        return generatedKeys;
    }
    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, int[] columnIndexes) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, String[] columnNames) throws SQLException { return executeUpdate(sql); }
    @Override public boolean execute(String sql, int autoGeneratedKeys) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, int[] columnIndexes) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, String[] columnNames) throws SQLException { return execute(sql); }
    @Override public int getResultSetHoldability() throws SQLException { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public void setPoolable(boolean poolable) throws SQLException {}
    @Override public boolean isPoolable() throws SQLException { return false; }
    @Override public void closeOnCompletion() throws SQLException { closeOnCompletion = true; }
    @Override public boolean isCloseOnCompletion() throws SQLException { return closeOnCompletion; }

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
