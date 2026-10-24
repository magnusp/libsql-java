package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.client.LibsqlClientConfig;
import com.github.magnusp.libsql.client.LibsqlException;
import com.github.magnusp.libsql.client.LibsqlHttpClient;
import com.github.magnusp.libsql.hrana.codec.Batch;
import com.github.magnusp.libsql.hrana.codec.BatchCond;
import com.github.magnusp.libsql.hrana.codec.BatchStep;
import com.github.magnusp.libsql.hrana.codec.HranaError;
import com.github.magnusp.libsql.hrana.codec.PipelineReqBody;
import com.github.magnusp.libsql.hrana.codec.PipelineRespBody;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.StreamRequest;
import com.github.magnusp.libsql.hrana.codec.StreamResponse;
import com.github.magnusp.libsql.hrana.codec.StreamResult;

import java.net.URI;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * A JDBC connection whose state is serialized on one connection-level lock, the way sqlite-jdbc
 * serializes every call on its {@code DB} object.
 *
 * <p>Hrana over HTTP requires serialized requests per stream and consumes a baton once, so each
 * pipeline round trip and each transaction transition ({@link #setAutoCommit(boolean)}, {@link
 * #commit()}, {@link #rollback()}, {@link #close()}) runs while holding {@link #stateLock}.
 * Without it, two statements on one non-auto-commit connection can read the same baton, a commit
 * can land between another thread's begin and its send, and a close can leave a stream open.
 *
 * <p><strong>Lock ordering.</strong> A statement's {@code stateLock} is never held across a call
 * into the connection, so the only possible order is statement lock, then connection lock. {@code
 * LibsqlStatement.checkClosed()} releases its statement lock before calling {@link
 * #checkClosed()}, and both execute paths call {@link #executeStatement(Stmt)} and {@link
 * #executeBatchResults(List)} after their statement critical sections have exited. The connection
 * never calls back into a statement, so the order cannot invert and the two locks cannot deadlock.
 */
public class LibsqlConnection implements Connection {

    private final LibsqlClientConfig config;
    private final LibsqlHttpClient client;

    /** Guards every field below and every pipeline round trip. See the class javadoc. */
    private final Object stateLock = new Object();

    private boolean autoCommit = true;
    private boolean readOnly = false;
    private int transactionIsolation = TRANSACTION_SERIALIZABLE;
    private boolean closed = false;
    private TransactionMode transactionMode = TransactionMode.DEFERRED;
    private LibsqlDatabaseMetaData meta;
    private int nextSavepointId = 0;
    private boolean transactionStartedBySavepoint = false;
    private final List<String> savepointNames = new ArrayList<>();

    /**
     * Transaction begin mode, matching xerial sqlite-jdbc's
     * {@code SQLiteConfig.TransactionMode}.
     */
    public enum TransactionMode {
        DEFERRED("BEGIN"),
        IMMEDIATE("BEGIN IMMEDIATE"),
        EXCLUSIVE("BEGIN EXCLUSIVE");

        private final String beginSql;

        TransactionMode(String beginSql) {
            this.beginSql = beginSql;
        }

        String beginSql() {
            return beginSql;
        }

        public static TransactionMode from(String mode) {
            try {
                return valueOf(mode.toUpperCase());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Unknown transaction mode: " + mode, e);
            }
        }
    }

    // Interactive stream state
    private String baton = null;
    private URI baseUrl = null;

    public LibsqlConnection(LibsqlClientConfig config, LibsqlHttpClient client) {
        this.config = config;
        this.client = client;
    }

    public LibsqlClientConfig getConfig() {
        return config;
    }

    public void checkClosed() throws SQLException {
        synchronized (stateLock) {
            if (closed) {
                throw new SQLException("Connection is closed");
            }
        }
    }

    public StmtResult executeStatement(Stmt stmt) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            try {
                if (autoCommit) {
                    // One-shot execution with single-roundtrip close (prevents Bug 1 128-stream 10s stall)
                    return client.executeOneShot(stmt);
                } else {
                    ensureTransactionStarted();
                    PipelineReqBody req = new PipelineReqBody(baton, List.of(new StreamRequest.Execute(stmt)));
                    PipelineRespBody resp = client.sendPipeline(req, baseUrl);
                    updateStreamState(resp);

                    if (resp.results().isEmpty()) {
                        throw new SQLException("Empty result received from server");
                    }
                    StreamResult sr = resp.results().get(0);
                    return switch (sr) {
                        case StreamResult.Ok ok -> {
                            if (ok.response() instanceof StreamResponse.Execute exec) {
                                yield exec.result();
                            }
                            throw new SQLException("Unexpected response: " + ok.response());
                        }
                        case StreamResult.Error err -> throw LibsqlErrorMapper.toSQLException(err.error());
                    };
                }
            } catch (LibsqlException e) {
                throw LibsqlErrorMapper.toSQLException(e);
            }
        }
    }

    public int[] executeBatch(List<Stmt> statements) throws SQLException {
        List<StmtResult> results = executeBatchResults(statements);
        int[] updateCounts = new int[results.size()];
        for (int i = 0; i < results.size(); i++) {
            updateCounts[i] = (int) results.get(i).affectedRowCount();
        }
        return updateCounts;
    }

    /**
     * Executes a batch and returns the {@code BatchResult} step results, one per statement. The
     * caller derives both the update counts and the generated keys from these results, so the
     * driver needs no follow-up query and no extra round trip.
     */
    List<StmtResult> executeBatchResults(List<Stmt> statements) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            if (statements == null || statements.isEmpty()) {
                return List.of();
            }

            try {
                List<BatchStep> steps = new ArrayList<>(statements.size());
                for (int i = 0; i < statements.size(); i++) {
                    BatchCond condition = i == 0 ? null : new BatchCond.StepOk(i - 1);
                    steps.add(new BatchStep(condition, statements.get(i)));
                }
                Batch batch = new Batch(steps);

                if (autoCommit) {
                    // One-shot batch
                    PipelineReqBody req = new PipelineReqBody(null, List.of(
                            new StreamRequest.BatchReq(batch),
                            StreamRequest.Close.INSTANCE
                    ));
                    PipelineRespBody resp = client.sendPipeline(req, baseUrl);
                    return extractBatchResults(resp, statements.size());
                } else {
                    ensureTransactionStarted();
                    PipelineReqBody req = new PipelineReqBody(baton, List.of(new StreamRequest.BatchReq(batch)));
                    PipelineRespBody resp = client.sendPipeline(req, baseUrl);
                    updateStreamState(resp);
                    return extractBatchResults(resp, statements.size());
                }
            } catch (LibsqlException e) {
                throw LibsqlErrorMapper.toSQLException(e);
            }
        }
    }

    private List<StmtResult> extractBatchResults(PipelineRespBody resp, int expectedSize)
            throws SQLException {
        if (resp.results().isEmpty()) {
            throw new SQLException("Empty response for batch execution");
        }
        StreamResult sr = resp.results().get(0);
        if (sr instanceof StreamResult.Error err) {
            throw LibsqlErrorMapper.toSQLException(err.error());
        }
        StreamResponse response = ((StreamResult.Ok) sr).response();
        if (!(response instanceof StreamResponse.BatchResp batchResp)) {
            throw new SQLException("Unexpected batch response: " + response);
        }

        List<StmtResult> results = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            HranaError error = batchResp.result().stepErrors().get(i);
            if (error != null) {
                // JDBC stops the batch at the first failure and reports only the steps that ran.
                // The mapped exception is the cause, so SQLState and vendor code survive too.
                SQLException mapped = LibsqlErrorMapper.toSQLException(error);
                throw new BatchUpdateException(
                        error.message(),
                        mapped.getSQLState(),
                        mapped.getErrorCode(),
                        steppedUpdateCounts(results),
                        mapped);
            }
            StmtResult result = batchResp.result().stepResults().get(i);
            if (result == null) {
                // A skipped step is legitimate only after a failed step, which the loop has
                // already thrown on. Here the server executed the step but reported nothing.
                throw new SQLException(
                        "Protocol error: batch step " + i + " has neither a result nor an error");
            }
            results.add(result);
        }
        return results;
    }

    private static int[] steppedUpdateCounts(List<StmtResult> results) {
        int[] updateCounts = new int[results.size()];
        for (int i = 0; i < results.size(); i++) {
            updateCounts[i] = (int) results.get(i).affectedRowCount();
        }
        return updateCounts;
    }

    private void ensureTransactionStarted() throws SQLException {
        if (beginPending()) {
            beginTransaction();
        }
    }

    /**
     * The begin-pending state is derived rather than stored: auto-commit is off and no stream is
     * open, so the next statement must begin the transaction. A separate boolean would be a second
     * source of truth for a fact the baton already carries.
     *
     * <p>Callers hold {@link #stateLock}.
     */
    private boolean beginPending() {
        return !autoCommit && baton == null;
    }

    /** Callers hold {@link #stateLock}. */
    private void beginTransaction() throws SQLException {
        PipelineRespBody resp;
        try {
            PipelineReqBody req = new PipelineReqBody(null,
                    List.of(new StreamRequest.Execute(new Stmt(transactionMode.beginSql()))));
            resp = client.sendPipeline(req, baseUrl);
        } catch (LibsqlException e) {
            throw LibsqlErrorMapper.toSQLException(e);
        }
        updateStreamState(resp);
        if (resp.results().isEmpty()) {
            closeStreamSilently();
            throw new SQLException("Empty result received from server");
        }
        StreamResult result = resp.results().get(0);
        if (result instanceof StreamResult.Error error) {
            closeStreamSilently();
            throw LibsqlErrorMapper.toSQLException(error.error());
        }
        if (!(result instanceof StreamResult.Ok ok)
                || !(ok.response() instanceof StreamResponse.Execute)) {
            closeStreamSilently();
            throw new SQLException("Unexpected response: " + result);
        }
    }

    /** Callers hold {@link #stateLock}. */
    private void updateStreamState(PipelineRespBody resp) {
        this.baton = resp.baton();
        if (resp.baseUrl() != null && !resp.baseUrl().isBlank()) {
            this.baseUrl = URI.create(resp.baseUrl());
        }
    }

    /** Callers hold {@link #stateLock}. */
    private void closeStreamSilently() {
        if (baton != null) {
            try {
                client.sendPipeline(new PipelineReqBody(baton, List.of(StreamRequest.Close.INSTANCE)), baseUrl);
            } catch (Exception ignored) {
            }
            this.baton = null;
        }
    }

    LibsqlStatement createStatementInternal() throws SQLException {
        checkClosed();
        return new LibsqlStatement(this);
    }

    @Override
    public Statement createStatement() throws SQLException {
        return createStatementInternal();
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();
        return new LibsqlPreparedStatement(this, sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLException("CallableStatement not supported");
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return sql;
    }

    /**
     * Matches the observable JDBC behaviour of xerial sqlite-jdbc 3.53.4.0 while keeping the
     * transaction begin lazy:
     *
     * <ul>
     *   <li>Disabling auto-commit only records that the next statement must begin a transaction in
     *       the configured {@link TransactionMode}. No request is sent, so an idle non-auto-commit
     *       connection holds no Hrana stream and cannot hit sqld's idle stream expiry.
     *   <li>{@link #commit()} and {@link #rollback()} end the open transaction and close its stream
     *       without beginning the next one. A commit or rollback with no work sent nothing to the
     *       server, as in xerial.
     *   <li>Re-enabling auto-commit commits the open transaction and ends the Hrana transaction
     *       stream.
     * </ul>
     *
     * <p>This diverges from xerial only in when the server sees the begin: an {@code IMMEDIATE} or
     * {@code EXCLUSIVE} transaction no longer takes its write lock until the next statement. The
     * protocol requires it, because Hrana over HTTP expires idle streams.
     *
     * <p>Use {@link #setTransactionMode(TransactionMode)} to select {@code IMMEDIATE} or {@code
     * EXCLUSIVE}, which acquire the write lock when the transaction begins.
     */
    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            if (this.autoCommit == autoCommit) return;
            if (autoCommit) {
                endTransaction("COMMIT");
                this.autoCommit = true;
            } else {
                this.autoCommit = false;
            }
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            return autoCommit;
        }
    }

    @Override
    public void commit() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            if (autoCommit) {
                throw new SQLException("Cannot commit when in autoCommit mode");
            }
            endTransaction("COMMIT");
        }
    }

    @Override
    public void rollback() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            if (autoCommit) {
                throw new SQLException("Cannot rollback when in autoCommit mode");
            }
            endTransaction("ROLLBACK");
        }
    }

    /** Callers hold {@link #stateLock}. */
    private void endTransaction(String sql) throws SQLException {
        if (beginPending()) {
            // No transaction has started, so there is nothing to send and nothing to close.
            return;
        }

        PipelineRespBody resp;
        try {
            PipelineReqBody req = new PipelineReqBody(baton,
                    List.of(new StreamRequest.Execute(new Stmt(sql))));
            resp = client.sendPipeline(req, baseUrl);
        } catch (LibsqlException e) {
            throw LibsqlErrorMapper.toSQLException(e);
        }
        updateStreamState(resp);

        if (resp.results().isEmpty()) {
            throw new SQLException("Empty result received from server");
        }
        StreamResult result = resp.results().get(0);
        if (result instanceof StreamResult.Error error) {
            throw LibsqlErrorMapper.toSQLException(error.error());
        }
        if (!(result instanceof StreamResult.Ok ok)
                || !(ok.response() instanceof StreamResponse.Execute)) {
            throw new SQLException("Unexpected response: " + result);
        }

        // The server committed or rolled back, so the stream has no state left worth holding.
        // Closing it lets an idle connection drop its server-side stream. On an error above the
        // baton stays, so the caller can retry or roll back on the same stream.
        clearSavepoints();
        closeStreamSilently();
    }

    @Override
    public void close() throws SQLException {
        synchronized (stateLock) {
            if (!closed) {
                closed = true;
                clearSavepoints();
                closeStreamSilently();
            }
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        synchronized (stateLock) {
            return closed;
        }
    }

    /**
     * Returns the metadata for this connection, creating it on first use and reusing it afterwards,
     * matching xerial sqlite-jdbc 3.53.4.0. The instance is cached for the lifetime of the
     * connection, so repeated calls return the same object and separate connections get separate
     * instances. Calling this on a closed connection throws.
     */
    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            if (meta == null) {
                meta = new LibsqlDatabaseMetaData(this);
            }
            return meta;
        }
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            this.readOnly = readOnly;
        }
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            return readOnly;
        }
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {}

    @Override
    public String getCatalog() throws SQLException {
        return null;
    }

    /**
     * Selects the mode used by the next transaction begin. Matches xerial sqlite-jdbc's {@code
     * SQLiteConfig.setTransactionMode}; the default is {@link TransactionMode#DEFERRED}.
     */
    public void setTransactionMode(TransactionMode mode) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            if (mode == null) {
                throw new IllegalArgumentException("Transaction mode cannot be null");
            }
            this.transactionMode = mode;
        }
    }

    public void setTransactionMode(String mode) throws SQLException {
        setTransactionMode(TransactionMode.from(mode));
    }

    public TransactionMode getTransactionMode() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            return transactionMode;
        }
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            this.transactionIsolation = level;
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            return transactionIsolation;
        }
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {}

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareCall(sql);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        throw new SQLFeatureNotSupportedException("Type maps are not supported");
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException("Type maps are not supported");
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkClosed();
        checkHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        checkClosed();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    private static void checkHoldability(int holdability) throws SQLException {
        if (holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw new SQLException("SQLite only supports CLOSE_CURSORS_AT_COMMIT");
        }
    }

    /**
     * Creates an unnamed savepoint. When the connection is in auto-commit mode this starts a
     * transaction, matching xerial sqlite-jdbc 3.53.4.0 and SQLite itself: a {@code SAVEPOINT}
     * issued outside a transaction behaves as {@code BEGIN DEFERRED}, so the savepoint is sent on
     * a new Hrana stream without a preceding {@code BEGIN} and releasing that outermost savepoint
     * later commits. Inside a transaction begun by {@link #setAutoCommit(boolean)} the savepoint
     * is nested in the lazily begun transaction instead.
     */
    @Override
    public Savepoint setSavepoint() throws SQLException {
        return setSavepointInternal(null);
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            if (name == null || name.isBlank()) {
                throw new SQLException("Savepoint name cannot be null or empty");
            }
        }
        return setSavepointInternal(name);
    }

    private Savepoint setSavepointInternal(String name) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            LibsqlSavepoint savepoint = new LibsqlSavepoint(++nextSavepointId, name);
            String sql = "SAVEPOINT " + quoteSavepointName(savepoint.sqlName());
            if (autoCommit) {
                try {
                    executeTransactionStatement(sql);
                } catch (SQLException e) {
                    // No transaction was started, so the fresh stream holds no state.
                    closeStreamSilently();
                    throw e;
                }
                autoCommit = false;
                transactionStartedBySavepoint = true;
                savepointNames.clear();
                savepointNames.add(savepoint.sqlName());
            } else {
                ensureTransactionStarted();
                executeTransactionStatement(sql);
                savepointNames.add(savepoint.sqlName());
            }
            return savepoint;
        }
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            String sqlName = savepointSqlName(savepoint);
            if (autoCommit) {
                throw new SQLException("Cannot rollback to a savepoint when in autoCommit mode");
            }
            ensureTransactionStarted();
            executeTransactionStatement("ROLLBACK TO SAVEPOINT " + quoteSavepointName(sqlName));
            int index = savepointNames.lastIndexOf(sqlName);
            if (index >= 0) {
                // Rolling back cancels the savepoints created after the target; the target
                // itself stays active.
                savepointNames.subList(index + 1, savepointNames.size()).clear();
            }
        }
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            String sqlName = savepointSqlName(savepoint);
            if (autoCommit) {
                throw new SQLException("Cannot release a savepoint when in autoCommit mode");
            }
            ensureTransactionStarted();
            executeTransactionStatement("RELEASE SAVEPOINT " + quoteSavepointName(sqlName));
            int index = savepointNames.lastIndexOf(sqlName);
            if (index >= 0) {
                boolean outermost = index == 0;
                savepointNames.subList(index, savepointNames.size()).clear();
                if (outermost && transactionStartedBySavepoint) {
                    // Releasing the savepoint that started the transaction commits it, so the
                    // stream holds no transaction state and the next statement lazily begins a
                    // new one, as after a commit.
                    transactionStartedBySavepoint = false;
                    savepointNames.clear();
                    closeStreamSilently();
                }
            }
        }
    }

    /** Callers hold {@link #stateLock}. */
    private void executeTransactionStatement(String sql) throws SQLException {
        PipelineRespBody resp;
        try {
            PipelineReqBody req = new PipelineReqBody(baton, List.of(new StreamRequest.Execute(new Stmt(sql))));
            resp = client.sendPipeline(req, baseUrl);
        } catch (LibsqlException e) {
            throw LibsqlErrorMapper.toSQLException(e);
        }
        updateStreamState(resp);
        if (resp.results().isEmpty()) {
            throw new SQLException("Empty result received from server");
        }
        StreamResult result = resp.results().get(0);
        if (result instanceof StreamResult.Error error) {
            throw LibsqlErrorMapper.toSQLException(error.error());
        }
        if (!(result instanceof StreamResult.Ok ok)
                || !(ok.response() instanceof StreamResponse.Execute)) {
            throw new SQLException("Unexpected response: " + result);
        }
    }

    private static String savepointSqlName(Savepoint savepoint) throws SQLException {
        if (savepoint == null) {
            throw new SQLException("Savepoint cannot be null");
        }
        if (savepoint instanceof LibsqlSavepoint libsqlSavepoint) {
            return libsqlSavepoint.sqlName();
        }
        try {
            String name = savepoint.getSavepointName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (SQLException ignored) {
            // An unnamed foreign savepoint has no name; its id identifies it below.
        }
        try {
            return "SQLITE_SAVEPOINT_" + savepoint.getSavepointId();
        } catch (SQLException e) {
            throw new SQLException("Savepoint has no usable name or id", e);
        }
    }

    private static String quoteSavepointName(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** Callers hold {@link #stateLock}. */
    private void clearSavepoints() {
        savepointNames.clear();
        transactionStartedBySavepoint = false;
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkClosed();
        checkHoldability(resultSetHoldability);
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkClosed();
        checkHoldability(resultSetHoldability);
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkClosed();
        checkHoldability(resultSetHoldability);
        return prepareCall(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public Clob createClob() throws SQLException { throw new SQLFeatureNotSupportedException("createClob is not supported"); }
    @Override
    public Blob createBlob() throws SQLException { throw new SQLFeatureNotSupportedException("createBlob is not supported"); }
    @Override
    public NClob createNClob() throws SQLException { throw new SQLFeatureNotSupportedException("createNClob is not supported"); }
    @Override
    public SQLXML createSQLXML() throws SQLException { throw new SQLFeatureNotSupportedException("createSQLXML is not supported"); }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        synchronized (stateLock) {
            if (closed) return false;
        }
        try {
            executeStatement(new Stmt("SELECT 1", null, List.of(), List.of(), false));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {}

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {}

    @Override
    public String getClientInfo(String name) throws SQLException { return null; }

    @Override
    public Properties getClientInfo() throws SQLException { return new Properties(); }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException { throw new UnsupportedOperationException(); }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException { throw new UnsupportedOperationException(); }

    @Override
    public void setSchema(String schema) throws SQLException {}

    @Override
    public String getSchema() throws SQLException { return null; }

    @Override
    public void abort(Executor executor) throws SQLException {
        close();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {}

    @Override
    public int getNetworkTimeout() throws SQLException { return 0; }

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
