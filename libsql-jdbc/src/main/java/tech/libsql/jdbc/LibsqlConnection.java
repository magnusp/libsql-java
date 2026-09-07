package tech.libsql.jdbc;

import tech.libsql.client.LibsqlClientConfig;
import tech.libsql.client.LibsqlException;
import tech.libsql.client.LibsqlHttpClient;
import tech.libsql.hrana.codec.Batch;
import tech.libsql.hrana.codec.BatchStep;
import tech.libsql.hrana.codec.PipelineReqBody;
import tech.libsql.hrana.codec.PipelineRespBody;
import tech.libsql.hrana.codec.Stmt;
import tech.libsql.hrana.codec.StmtResult;
import tech.libsql.hrana.codec.StreamRequest;
import tech.libsql.hrana.codec.StreamResponse;
import tech.libsql.hrana.codec.StreamResult;

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

    // Interactive stream state
    private String baton = null;
    private URI baseUrl = null;

    public LibsqlConnection(LibsqlClientConfig config, LibsqlHttpClient client) {
        this.config = config;
        this.client = client;
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
            // Mitigation for Bug 2: Read-write transactions start with BEGIN IMMEDIATE
            String beginSql = readOnly ? "BEGIN DEFERRED" : "BEGIN IMMEDIATE";
            PipelineReqBody req = new PipelineReqBody(null, List.of(new StreamRequest.Execute(new Stmt(beginSql))));
            PipelineRespBody resp = client.sendPipeline(req, baseUrl);
            updateStreamState(resp);
            if (resp.results().isEmpty() || resp.results().get(0) instanceof StreamResult.Error) {
                closeStreamSilently();
                throw new SQLException("Failed to begin transaction");
            }
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

    @Override
    public Statement createStatement() throws SQLException {
        checkClosed();
        return new LibsqlStatement(this);
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

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkClosed();
        if (this.autoCommit == autoCommit) return;
        if (!this.autoCommit && autoCommit) {
            // Committing active transaction
            commit();
        }
        this.autoCommit = autoCommit;
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
        if (baton != null) {
            try {
                PipelineReqBody req = new PipelineReqBody(baton, List.of(
                        new StreamRequest.Execute(new Stmt("COMMIT")),
                        StreamRequest.Close.INSTANCE
                ));
                PipelineRespBody resp = client.sendPipeline(req, baseUrl);
                this.baton = null;
            } catch (LibsqlException e) {
                closeStreamSilently();
                throw LibsqlErrorMapper.toSQLException(e);
            }
        }
    }

    @Override
    public void rollback() throws SQLException {
        checkClosed();
        if (autoCommit) {
            throw new SQLException("Cannot rollback when in autoCommit mode");
        }
        if (baton != null) {
            try {
                PipelineReqBody req = new PipelineReqBody(baton, List.of(
                        new StreamRequest.Execute(new Stmt("ROLLBACK")),
                        StreamRequest.Close.INSTANCE
                ));
                client.sendPipeline(req, baseUrl);
            } catch (Exception ignored) {
            } finally {
                this.baton = null;
            }
        }
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

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkClosed();
        return new LibsqlDatabaseMetaData(this);
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
    public Clob createClob() throws SQLException { throw new UnsupportedOperationException(); }
    @Override
    public Blob createBlob() throws SQLException { throw new UnsupportedOperationException(); }
    @Override
    public NClob createNClob() throws SQLException { throw new UnsupportedOperationException(); }
    @Override
    public SQLXML createSQLXML() throws SQLException { throw new UnsupportedOperationException(); }

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
