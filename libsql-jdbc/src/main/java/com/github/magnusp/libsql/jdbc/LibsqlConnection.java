package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.client.LibsqlClientConfig;
import com.github.magnusp.libsql.client.LibsqlException;
import com.github.magnusp.libsql.client.LibsqlHttpClient;
import com.github.magnusp.libsql.hrana.codec.Batch;
import com.github.magnusp.libsql.hrana.codec.BatchStep;
import com.github.magnusp.libsql.hrana.codec.PipelineReqBody;
import com.github.magnusp.libsql.hrana.codec.PipelineRespBody;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.StreamRequest;
import com.github.magnusp.libsql.hrana.codec.StreamResponse;
import com.github.magnusp.libsql.hrana.codec.StreamResult;

import java.net.URI;
import java.sql.Array;
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

public class LibsqlConnection implements Connection {

    private final LibsqlClientConfig config;
    private final LibsqlHttpClient client;

    private boolean autoCommit = true;
    private boolean readOnly = false;
    private int transactionIsolation = TRANSACTION_SERIALIZABLE;
    private boolean closed = false;
    private TransactionMode transactionMode = TransactionMode.DEFERRED;
    private LibsqlDatabaseMetaData meta;

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
        if (closed) {
            throw new SQLException("Connection is closed");
        }
    }

    public StmtResult executeStatement(Stmt stmt) throws SQLException {
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
                    case StreamResult.Error err -> throw new SQLException(err.error().message());
                };
            }
        } catch (LibsqlException e) {
            throw LibsqlErrorMapper.toSQLException(e);
        }
    }

    public int[] executeBatch(List<Stmt> statements) throws SQLException {
        checkClosed();
        if (statements == null || statements.isEmpty()) {
            return new int[0];
        }

        try {
            List<BatchStep> steps = new ArrayList<>(statements.size());
            for (Stmt stmt : statements) {
                steps.add(new BatchStep(stmt));
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

    private int[] extractBatchResults(PipelineRespBody resp, int expectedSize) throws SQLException {
        if (resp.results().isEmpty()) {
            throw new SQLException("Empty response for batch execution");
        }
        StreamResult sr = resp.results().get(0);
        if (sr instanceof StreamResult.Error err) {
            throw new SQLException(err.error().message());
        }
        StreamResponse response = ((StreamResult.Ok) sr).response();
        if (!(response instanceof StreamResponse.BatchResp batchResp)) {
            throw new SQLException("Unexpected batch response: " + response);
        }

        int[] updateCounts = new int[expectedSize];
        for (int i = 0; i < expectedSize; i++) {
            if (batchResp.result().stepErrors().containsKey(i)) {
                updateCounts[i] = Statement.EXECUTE_FAILED;
            } else if (batchResp.result().stepResults().containsKey(i)) {
                StmtResult r = batchResp.result().stepResults().get(i);
                updateCounts[i] = (int) r.affectedRowCount();
            } else {
                updateCounts[i] = Statement.SUCCESS_NO_INFO;
            }
        }
        return updateCounts;
    }

    private void ensureTransactionStarted() throws SQLException {
        if (baton == null) {
            beginTransaction();
        }
    }

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
            throw new SQLException(error.error().message());
        }
        if (!(result instanceof StreamResult.Ok ok)
                || !(ok.response() instanceof StreamResponse.Execute)) {
            closeStreamSilently();
            throw new SQLException("Unexpected response: " + result);
        }
    }

    private void updateStreamState(PipelineRespBody resp) {
        this.baton = resp.baton();
        if (resp.baseUrl() != null && !resp.baseUrl().isBlank()) {
            this.baseUrl = URI.create(resp.baseUrl());
        }
    }

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
     * Matches xerial sqlite-jdbc 3.53.4.0:
     *
     * <ul>
     *   <li>Disabling auto-commit immediately begins a transaction in the configured
     *       {@link TransactionMode}. The default is {@link TransactionMode#DEFERRED}, so an idle
     *       non-auto-commit connection holds no lock until its first statement.
     *   <li>{@link #commit()} and {@link #rollback()} end the current transaction and immediately
     *       begin the next one, so a non-auto-commit connection always has a transaction open.
     *   <li>Re-enabling auto-commit commits the open transaction and ends the Hrana transaction
     *       stream.
     * </ul>
     *
     * <p>Use {@link #setTransactionMode(TransactionMode)} to select {@code IMMEDIATE} or {@code
     * EXCLUSIVE}, which acquire the write lock when the transaction begins.
     */
    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkClosed();
        if (this.autoCommit == autoCommit) return;
        if (autoCommit) {
            endTransaction("COMMIT", false);
            this.autoCommit = true;
        } else {
            this.autoCommit = false;
            beginTransaction();
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkClosed();
        return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        checkClosed();
        if (autoCommit) {
            throw new SQLException("Cannot commit when in autoCommit mode");
        }
        endTransaction("COMMIT", true);
    }

    @Override
    public void rollback() throws SQLException {
        checkClosed();
        if (autoCommit) {
            throw new SQLException("Cannot rollback when in autoCommit mode");
        }
        endTransaction("ROLLBACK", true);
    }

    private void endTransaction(String sql, boolean rebegin) throws SQLException {
        if (baton == null) {
            if (rebegin) {
                throw new SQLException("No transaction is active");
            }
            return;
        }

        List<StreamRequest> requests = new ArrayList<>(2);
        requests.add(new StreamRequest.Execute(new Stmt(sql)));
        if (rebegin) {
            requests.add(new StreamRequest.Execute(new Stmt(transactionMode.beginSql())));
        }

        PipelineRespBody resp;
        try {
            resp = client.sendPipeline(new PipelineReqBody(baton, requests), baseUrl);
        } catch (LibsqlException e) {
            throw LibsqlErrorMapper.toSQLException(e);
        }

        if (resp.results().isEmpty()) {
            throw new SQLException("Empty result received from server");
        }
        StreamResult result = resp.results().get(0);
        if (result instanceof StreamResult.Error error) {
            throw new SQLException(error.error().message());
        }
        if (!(result instanceof StreamResult.Ok ok)
                || !(ok.response() instanceof StreamResponse.Execute)) {
            throw new SQLException("Unexpected response: " + result);
        }

        if (!rebegin) {
            updateStreamState(resp);
            closeStreamSilently();
            return;
        }

        if (resp.results().size() < 2) {
            closeStreamSilently();
            throw new SQLException("Missing response for transaction begin");
        }
        StreamResult begin = resp.results().get(1);
        if (begin instanceof StreamResult.Error error) {
            if (resp.baton() != null) {
                this.baton = resp.baton();
            }
            closeStreamSilently();
            throw new SQLException(error.error().message());
        }
        if (!(begin instanceof StreamResult.Ok beginOk)
                || !(beginOk.response() instanceof StreamResponse.Execute)) {
            if (resp.baton() != null) {
                this.baton = resp.baton();
            }
            closeStreamSilently();
            throw new SQLException("Unexpected response: " + begin);
        }
        updateStreamState(resp);
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            closeStreamSilently();
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    /**
     * Returns the metadata for this connection, creating it on first use and reusing it afterwards,
     * matching xerial sqlite-jdbc 3.53.4.0. The instance is cached for the lifetime of the
     * connection, so repeated calls return the same object and separate connections get separate
     * instances. Calling this on a closed connection throws.
     */
    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkClosed();
        if (meta == null) {
            meta = new LibsqlDatabaseMetaData(this);
        }
        return meta;
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkClosed();
        this.readOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkClosed();
        return readOnly;
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
        checkClosed();
        if (mode == null) {
            throw new IllegalArgumentException("Transaction mode cannot be null");
        }
        this.transactionMode = mode;
    }

    public void setTransactionMode(String mode) throws SQLException {
        setTransactionMode(TransactionMode.from(mode));
    }

    public TransactionMode getTransactionMode() throws SQLException {
        checkClosed();
        return transactionMode;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkClosed();
        this.transactionIsolation = level;
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkClosed();
        return transactionIsolation;
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
        return Map.of();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {}

    @Override
    public void setHoldability(int holdability) throws SQLException {}

    @Override
    public int getHoldability() throws SQLException {
        return 1; // HOLD_CURSORS_OVER_COMMIT
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new SQLException("Savepoints not supported yet");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLException("Savepoints not supported yet");
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLException("Savepoints not supported yet");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLException("Savepoints not supported yet");
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
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
        if (closed) return false;
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
