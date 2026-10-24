package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Statement over the Hrana transport.
 *
 * <p>The lifecycle matches sqlite-jdbc 3.53.4.0 rather than strict JDBC, because the driver is a
 * drop-in replacement for that driver:
 *
 * <ul>
 *   <li>{@link #getResultSet()} hands out the current result set once. {@link
 *       #executeQuery(String)} already consumes that request, so calling {@code getResultSet}
 *       after it throws {@code ResultSet already requested}. A second call after {@link
 *       #execute(String)} throws the same error. If there is no current result set the method
 *       returns {@code null}.
 *   <li>{@link #getMoreResults(int)} supports only {@link Statement#CLOSE_CURRENT_RESULT}.
 *       {@link Statement#KEEP_CURRENT_RESULT} and {@link Statement#CLOSE_ALL_RESULTS} throw
 *       {@link SQLFeatureNotSupportedException} naming both constants, and any other value throws
 *       {@code SQLException("Invalid argument")}. The method always returns {@code false}.
 *   <li>Closing a result set closes the statement when {@link #closeOnCompletion()} was called,
 *       per {@code java.sql}. sqlite-jdbc 3.53.4.0 does the same, including for prepared
 *       statements.
 *   <li>{@link #closeOnCompletion()} and {@link #isCloseOnCompletion()} throw {@code
 *       SQLException("Statement is closed")} after the statement is closed.
 *   <li>{@link #close()} closes the current result set and generated-key results.
 *   <li>Optional statement settings are pinned per setter. {@link #setMaxRows(int)} stores a limit
 *       and drops the excess rows from the result set, matching {@code java.sql} and sqlite-jdbc
 *       3.53.4.0. {@link #setEscapeProcessing(boolean)} stores the java.sql flag, which starts
 *       enabled: while it is enabled, SQL that contains a JDBC escape sequence throws {@link
 *       SQLFeatureNotSupportedException} rather than reaching sqld untranslated. {@link
 *       #setMaxFieldSize(int)} and {@link #setQueryTimeout(int)} accept the zero that means no
 *       limit and throw for a limit the driver cannot enforce. {@link #setCursorName(String)}
 *       always throws: Hrana has no positioned cursor. sqlite-jdbc 3.53.4.0 instead accepts the
 *       last three silently, and its query timeout drives SQLite's busy timeout, which is a lock
 *       wait rather than a query deadline.
 *   <li>Matching xerial's execute-after-close behavior, {@link #execute(String)}, {@link
 *       #executeQuery(String)}, {@link #executeUpdate(String)}, and {@link #getConnection()} keep
 *       working after {@link #close()}. This deliberately copies a likely xerial bug for drop-in
 *       compatibility; see the issue #47 contract decision. A result set produced after close is
 *       returned closed, so a closed statement never holds an open result set.
 *   <li>Every other post-close transition throws {@code SQLException("Statement is closed")}, per
 *       {@code java.sql}: {@link #addBatch(String)}, {@link #getGeneratedKeys()}, and {@link
 *       #getResultSet()}, including the {@code getResultSet} call that follows an execute on the
 *       closed statement. sqlite-jdbc 3.53.4.0 diverges: it accepts {@code addBatch}, returns a
 *       fresh generated-key result set, and reopens its internal pointer so {@code getResultSet}
 *       hands out a live result set. The driver follows {@code java.sql}; see issue #87.
 *   <li>{@link #close()} and the execute methods are safe when called concurrently. State
 *       transitions share one lock; when close wins a race, the execute closes and discards its
 *       result set instead of attaching it to the closed statement.
 * </ul>
 */
public class LibsqlStatement implements Statement {

    protected final LibsqlConnection connection;
    private final Object stateLock = new Object();
    protected LibsqlResultSet currentResultSet = null;
    protected long updateCount = -1;
    protected boolean closed = false;
    protected boolean closeOnCompletion = false;
    protected final List<Stmt> batchStatements = new ArrayList<>();
    private ResultSet generatedKeys = null;
    private boolean resultSetRequested = false;
    private boolean closing = false;
    private int fetchSize = 0;
    private int maxRows = 0;
    private boolean escapeProcessing = true;

    private static final Set<String> STATEMENT_KEYWORDS =
            Set.of("SELECT", "VALUES", "INSERT", "REPLACE", "UPDATE", "DELETE");

    public LibsqlStatement(LibsqlConnection connection) {
        this.connection = connection;
    }

    /**
     * Test seam. Called after the closed check and before a result set is attached to the
     * statement, so a test can force a close to interleave with execute.
     */
    protected void beforeResultSetAssignment() throws SQLException {}

    protected void checkClosed() throws SQLException {
        synchronized (stateLock) {
            if (closed) {
                throw new SQLException("Statement is closed");
            }
        }
        if (connection != null) {
            connection.checkClosed();
        }
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        StmtResult result = executeSql(sql);
        if (result.cols() == null || result.cols().isEmpty()) {
            throw new SQLException("Query did not produce a ResultSet: " + sql);
        }
        LibsqlResultSet resultSet = newQueryResult(sql, result);
        assignResultSet(resultSet, result, sql, true);
        return resultSet;
    }

    /** Builds the result set both query paths attach, so the two cannot drift apart. */
    protected LibsqlResultSet newQueryResult(String sql, StmtResult result) {
        return new LibsqlResultSet(this, limitRows(result), sql, false);
    }

    protected void assignResultSet(LibsqlResultSet resultSet, boolean requested)
            throws SQLException {
        assignResultSet(resultSet, null, null, requested);
    }

    protected void assignResultSet(
            LibsqlResultSet resultSet, StmtResult result, String sql, boolean requested)
            throws SQLException {
        attachResult(resultSet, result, sql, requested);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        execute(sql);
        return snapshotUpdateCount();
    }

    /**
     * Reads the update count under {@link #stateLock}, the lock every write to the field holds.
     * The execute paths that return a count use this instead of reading the field directly, so the
     * value they hand back has the same happens-before edge as {@link #getUpdateCount()}.
     */
    protected int snapshotUpdateCount() {
        synchronized (stateLock) {
            return (int) updateCount;
        }
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        return executeResult(sql, executeSql(sql));
    }

    protected boolean executeResult(String sql, StmtResult result) throws SQLException {
        boolean hasResultSet = result.cols() != null && !result.cols().isEmpty();
        LibsqlResultSet resultSet = hasResultSet ? newQueryResult(sql, result) : null;
        return attachResult(resultSet, result, sql, false);
    }

    /**
     * Attaches {@code resultSet} as the current result in one transition shared by the explicit
     * {@code executeQuery} paths and the {@code execute} paths. When close wins the race, the result
     * set is discarded instead of attached, so a result set produced after {@link #close()} is
     * handed out closed and the statement stays closed.
     */
    protected boolean attachResult(
            LibsqlResultSet resultSet, StmtResult result, String sql, boolean requested)
            throws SQLException {
        if (resultSet != null && !isClosed()) {
            beforeResultSetAssignment();
        }
        synchronized (stateLock) {
            if (closed) {
                discardCurrentResult(resultSet, resultSet == null ? result.affectedRowCount() : -1);
                return resultSet != null;
            }
            updateGeneratedKeys(sql, result);
            currentResultSet = resultSet;
            updateCount = resultSet == null ? result.affectedRowCount() : -1;
            resultSetRequested = requested;
            return resultSet != null;
        }
    }

    /**
     * Clears the current result because the statement is closed. The result set closes without the
     * close-on-completion callback, and nothing temporarily reopens the statement.
     */
    private void discardCurrentResult(LibsqlResultSet resultSet, long updateCountWhenNoResultSet)
            throws SQLException {
        if (resultSet != null) {
            resultSet.closeDetached();
        }
        currentResultSet = null;
        resultSetRequested = false;
        updateCount = updateCountWhenNoResultSet;
        clearGeneratedKeys();
    }

    private StmtResult executeSql(String sql) throws SQLException {
        checkEscapeProcessing(sql);
        beginExecution();
        return connection.executeStatement(statement(sql));
    }

    /**
     * Builds a statement with SQL NULL for every placeholder a plain {@code Statement} leaves
     * unbound, matching SQLite, where an unset parameter reads as NULL. Hrana rejects an argument
     * list shorter than the SQL placeholders, so the nulls must be sent explicitly.
     */
    static Stmt statement(String sql) {
        if (sql == null || sql.indexOf('?') < 0) {
            return new Stmt(sql);
        }
        int placeholders = SqlTokenizer.placeholderCount(sql);
        if (placeholders == 0) {
            return new Stmt(sql);
        }
        List<Value> args = new ArrayList<>(placeholders);
        for (int i = 0; i < placeholders; i++) {
            args.add(Value.ofNull());
        }
        return new Stmt(sql, args);
    }

    /**
     * Rejects SQL that needs JDBC escape substitution while escape processing is on. The driver
     * sends SQL to sqld verbatim, so a sequence such as {@code {fn abs(-1)}} would fail as SQLite
     * syntax instead of being translated. Turning escape processing off is how a caller states
     * that it sends raw SQL.
     */
    protected void checkEscapeProcessing(String sql) throws SQLException {
        boolean enabled;
        synchronized (stateLock) {
            enabled = escapeProcessing;
        }
        if (enabled && JdbcEscapes.contains(sql)) {
            throw new SQLFeatureNotSupportedException("JDBC escape sequences are not supported");
        }
    }

    /**
     * Drops rows above the {@link #setMaxRows(int)} limit. java.sql requires the excess rows to be
     * dropped silently from the result set, not from the statement that produced it, so the server
     * still runs the full query.
     */
    protected StmtResult limitRows(StmtResult result) {
        int limit;
        synchronized (stateLock) {
            limit = maxRows;
        }
        if (limit <= 0 || result == null || result.rows() == null || result.rows().size() <= limit) {
            return result;
        }
        return new StmtResult(
                result.cols(),
                new ArrayList<>(result.rows().subList(0, limit)),
                result.affectedRowCount(),
                result.lastInsertRowid());
    }

    /**
     * Resets the state every execution path starts from: the previous result set is closed and the
     * previous generated keys are discarded. Without this, a path that does not produce keys of
     * its own would report the keys of the statement before it.
     */
    protected void beginExecution() throws SQLException {
        synchronized (stateLock) {
            closeCurrentResultSet();
            clearGeneratedKeys();
            updateCount = -1;
        }
    }

    protected void closeCurrentResultSet() throws SQLException {
        synchronized (stateLock) {
            LibsqlResultSet resultSet = currentResultSet;
            currentResultSet = null;
            resultSetRequested = false;
            if (resultSet != null) {
                resultSet.closeDetached();
            }
        }
    }

    protected void updateGeneratedKeys(String sql, StmtResult result) throws SQLException {
        synchronized (stateLock) {
            clearGeneratedKeys();
            if (sql != null
                    && result != null
                    && result.lastInsertRowid() != null
                    && isInsertSql(sql)) {
                generatedKeys = generatedKeysResultSet(List.of(result.lastInsertRowid()));
            }
        }
    }

    /**
     * Builds the generated keys for a batch from the {@code BatchResult} step results. Each insert
     * step contributes one row, matching the java.sql shape of a result set with one row per
     * generated key. Steps that are not inserts contribute nothing because Hrana leaves {@code
     * last_insert_rowid} undefined for them.
     */
    private void updateBatchGeneratedKeys(List<Stmt> statements, List<StmtResult> results)
            throws SQLException {
        synchronized (stateLock) {
            clearGeneratedKeys();
            List<Long> rowids = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                StmtResult result = results.get(i);
                String sql = statements.get(i).sql();
                if (result != null
                        && result.lastInsertRowid() != null
                        && sql != null
                        && isInsertSql(sql)) {
                    rowids.add(result.lastInsertRowid());
                }
            }
            if (!rowids.isEmpty()) {
                generatedKeys = generatedKeysResultSet(rowids);
            }
        }
    }

    private LibsqlResultSet generatedKeysResultSet(List<Long> rowids) {
        List<Row> rows = new ArrayList<>(rowids.size());
        for (Long rowid : rowids) {
            rows.add(new Row(List.of(Value.of(rowid))));
        }
        StmtResult keys = new StmtResult(
                List.of(new Col("last_insert_rowid()", null)),
                rows,
                0,
                rowids.get(rowids.size() - 1));
        return new LibsqlResultSet(this, keys);
    }

    protected void clearGeneratedKeys() throws SQLException {
        synchronized (stateLock) {
            ResultSet keys = generatedKeys;
            generatedKeys = null;
            if (keys instanceof LibsqlResultSet libsqlKeys) {
                libsqlKeys.closeDetached();
            } else if (keys != null) {
                keys.close();
            }
        }
    }

    private static boolean isInsertSql(String sql) {
        List<SqlTokenizer.Token> tokens = SqlTokenizer.tokenize(sql);
        if (tokens == null) {
            return false;
        }
        String first = SqlTokenizer.wordAt(tokens, 0, 0);
        if ("INSERT".equals(first) || "REPLACE".equals(first)) {
            return true;
        }
        if (!"WITH".equals(first)) {
            return false;
        }
        int index = SqlTokenizer.indexOfAnyWord(tokens, 1, tokens.size(), 0, STATEMENT_KEYWORDS);
        String statement = SqlTokenizer.wordAt(tokens, index, 0);
        return "INSERT".equals(statement) || "REPLACE".equals(statement);
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
        synchronized (stateLock) {
            checkClosed();
            if (currentResultSet == null) {
                return null;
            }
            if (resultSetRequested) {
                throw new SQLException("ResultSet already requested");
            }
            resultSetRequested = true;
            return currentResultSet;
        }
    }

    @Override
    public int getUpdateCount() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            return (int) updateCount;
        }
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            return updateCount;
        }
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            closeCurrentResultSet();
            updateCount = -1;
            return false;
        }
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        checkClosed();
        checkEscapeProcessing(sql);
        batchStatements.add(statement(sql));
    }

    @Override
    public void clearBatch() throws SQLException {
        checkClosed();
        batchStatements.clear();
    }

    /**
     * Executes the batch and reports one update count per step. The generated keys and the update
     * count are rebuilt from the step results, so neither can leak from an earlier execution. The
     * update count is the total affected rows across the batch; JDBC leaves {@code getUpdateCount}
     * unspecified after a batch, and xerial leaves the previous value in place. A step that returns
     * a result set throws {@link BatchUpdateException} with the counts of the steps up to and
     * including it, per {@code java.sql}.
     */
    @Override
    public int[] executeBatch() throws SQLException {
        checkClosed();
        List<Stmt> statements = new ArrayList<>(batchStatements);
        try {
            beginExecution();
            List<StmtResult> results = connection.executeBatchResults(statements);
            int[] updateCounts = new int[results.size()];
            long affectedRows = 0;
            for (int i = 0; i < results.size(); i++) {
                StmtResult result = results.get(i);
                if (result.cols() != null && !result.cols().isEmpty()) {
                    throw new BatchUpdateException(
                            "batch entry " + i + ": query returns results",
                            java.util.Arrays.copyOf(updateCounts, i + 1));
                }
                updateCounts[i] = (int) result.affectedRowCount();
                affectedRows += result.affectedRowCount();
            }
            synchronized (stateLock) {
                updateBatchGeneratedKeys(statements, results);
                updateCount = affectedRows;
                resultSetRequested = false;
            }
            return updateCounts;
        } finally {
            clearBatch();
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        synchronized (stateLock) {
            if (closing) {
                return;
            }
            closing = true;
            try {
                LibsqlResultSet resultSet = currentResultSet;
                currentResultSet = null;
                resultSetRequested = false;
                clearGeneratedKeys();
                if (resultSet != null) {
                    resultSet.closeDetached();
                }
            } finally {
                closed = true;
                closing = false;
            }
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        synchronized (stateLock) {
            return closed;
        }
    }

    @Override public int getMaxFieldSize() throws SQLException { return 0; }
    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        if (max < 0) {
            throw new SQLException("max field size must be >= 0");
        }
        if (max > 0) {
            throw new SQLFeatureNotSupportedException("Column size limits are not supported");
        }
    }
    @Override
    public int getMaxRows() throws SQLException {
        synchronized (stateLock) {
            return maxRows;
        }
    }
    @Override
    public void setMaxRows(int max) throws SQLException {
        if (max < 0) {
            throw new SQLException("max row count must be >= 0");
        }
        synchronized (stateLock) {
            maxRows = max;
        }
    }
    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        synchronized (stateLock) {
            escapeProcessing = enable;
        }
    }
    @Override public int getQueryTimeout() throws SQLException { return 0; }
    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        if (seconds < 0) {
            throw new SQLException("query timeout must be >= 0");
        }
        if (seconds > 0) {
            throw new SQLFeatureNotSupportedException("Query timeouts are not supported");
        }
    }
    @Override public void cancel() throws SQLException {}
    @Override public SQLWarning getWarnings() throws SQLException { return null; }
    @Override public void clearWarnings() throws SQLException {}
    @Override
    public void setCursorName(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException("Cursor names are not supported");
    }
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        if (direction != ResultSet.FETCH_FORWARD
                && direction != ResultSet.FETCH_REVERSE
                && direction != ResultSet.FETCH_UNKNOWN) {
            throw new SQLException(
                    "Unknown fetch direction "
                            + direction
                            + ". Must be one of FETCH_FORWARD, FETCH_REVERSE, or FETCH_UNKNOWN");
        }
    }

    @Override public int getFetchDirection() throws SQLException { return ResultSet.FETCH_FORWARD; }
    @Override
    public void setFetchSize(int rows) throws SQLException {
        if (rows < 0) {
            throw new SQLException("fetch size " + rows + " out of bounds 0");
        }
        synchronized (stateLock) {
            fetchSize = rows;
        }
    }

    @Override
    public int getFetchSize() throws SQLException {
        synchronized (stateLock) {
            return fetchSize;
        }
    }
    @Override public int getResultSetConcurrency() throws SQLException { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getResultSetType() throws SQLException { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override
    public boolean getMoreResults(int current) throws SQLException {
        checkClosed();
        if (current == Statement.KEEP_CURRENT_RESULT || current == Statement.CLOSE_ALL_RESULTS) {
            throw new SQLFeatureNotSupportedException(
                    "Argument not supported: Statement.KEEP_CURRENT_RESULT or Statement.CLOSE_ALL_RESULTS");
        }
        if (current != Statement.CLOSE_CURRENT_RESULT) {
            throw new SQLException("Invalid argument");
        }
        return getMoreResults();
    }
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            if (generatedKeys == null) {
                generatedKeys = emptyGeneratedKeys();
            }
            return generatedKeys;
        }
    }
    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, int[] columnIndexes) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, String[] columnNames) throws SQLException { return executeUpdate(sql); }
    @Override public boolean execute(String sql, int autoGeneratedKeys) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, int[] columnIndexes) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, String[] columnNames) throws SQLException { return execute(sql); }
    @Override public int getResultSetHoldability() throws SQLException { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public void setPoolable(boolean poolable) throws SQLException {}
    @Override public boolean isPoolable() throws SQLException { return false; }
    @Override
    public void closeOnCompletion() throws SQLException {
        synchronized (stateLock) {
            checkClosedForCompletion();
            closeOnCompletion = true;
        }
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        synchronized (stateLock) {
            checkClosedForCompletion();
            return closeOnCompletion;
        }
    }

    private void checkClosedForCompletion() throws SQLException {
        if (closed) {
            throw new SQLException("statement is closed");
        }
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
