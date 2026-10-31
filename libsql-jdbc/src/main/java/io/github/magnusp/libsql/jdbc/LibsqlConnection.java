package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.client.LibsqlClient;
import io.github.magnusp.libsql.client.LibsqlClientConfig;
import io.github.magnusp.libsql.client.LibsqlException;
import io.github.magnusp.libsql.hrana.codec.Batch;
import io.github.magnusp.libsql.hrana.codec.BatchCond;
import io.github.magnusp.libsql.hrana.codec.BatchResult;
import io.github.magnusp.libsql.hrana.codec.BatchStep;
import io.github.magnusp.libsql.hrana.codec.HranaError;
import io.github.magnusp.libsql.hrana.codec.Stmt;
import io.github.magnusp.libsql.hrana.codec.StmtResult;

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
import java.util.function.Supplier;

/**
 * A JDBC connection whose state is serialized on one connection-level lock, the way sqlite-jdbc
 * serializes every call on its {@code DB} object.
 *
 * <p>Both transports serialize requests on one interactive stream, and Hrana over HTTP consumes a
 * baton once, so each round trip and each transaction transition ({@link #setAutoCommit(boolean)},
 * {@link #commit()}, {@link #rollback()}, {@link #close()}) runs while holding {@link #stateLock}.
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
    private final LibsqlClient client;

    /** The JDBC URL the caller connected with, kept for {@code DatabaseMetaData.getURL()}. */
    private final String jdbcUrl;

    /** Guards every field below and every transport round trip. See the class javadoc. */
    private final Object stateLock = new Object();

    private boolean autoCommit = true;
    private boolean readOnly = false;
    private int transactionIsolation = TRANSACTION_SERIALIZABLE;
    private boolean closed = false;
    private TransactionMode transactionMode = TransactionMode.DEFERRED;
    private LibsqlDatabaseMetaData meta;
    private int nextSavepointId = 0;
    private boolean transactionActive = false;
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
                return valueOf(mode.toUpperCase(java.util.Locale.ROOT));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Unknown transaction mode: " + mode, e);
            }
        }
    }

    // The client owns the Hrana stream. Auto-commit keeps one open between statements so session
    // state survives; a manual transaction tracks its begin explicitly because the stream may
    // already be open for auto-commit when the transaction starts.

    public LibsqlConnection(LibsqlClientConfig config, LibsqlClient client) {
        this(config, client, null);
    }

    public LibsqlConnection(LibsqlClientConfig config, LibsqlClient client, String jdbcUrl) {
        this.config = config;
        this.client = client;
        this.jdbcUrl = jdbcUrl;
    }

    public LibsqlClientConfig getConfig() {
        return config;
    }

    /** The JDBC URL used to open this connection, or the transport endpoint for test doubles. */
    String getJdbcUrl() {
        return jdbcUrl != null ? jdbcUrl : (config != null ? config.url() : null);
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
            if (autoCommit) {
                return sendAutoCommit(stmt);
            }
            ensureTransactionStarted();
            StmtResult result = sendOnStream(stmt);
            requireTransactionStream();
            return result;
        }
    }

    public int[] executeBatch(List<Stmt> statements) throws SQLException {
        return steppedUpdateCounts(executeBatchResults(statements));
    }

    /** Executes a batch and returns one step result per statement, so counts and keys need no follow-up query. */
    List<StmtResult> executeBatchResults(List<Stmt> statements) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            if (statements == null || statements.isEmpty()) {
                return List.of();
            }
            List<BatchStep> steps = new ArrayList<>(statements.size());
            for (int i = 0; i < statements.size(); i++) {
                BatchCond condition = i == 0 ? null : new BatchCond.StepOk(i - 1);
                steps.add(new BatchStep(condition, statements.get(i)));
            }
            Batch batch = new Batch(steps);
            BatchResult result;
            if (autoCommit) {
                result = sendBatchAutoCommit(batch);
            } else {
                ensureTransactionStarted();
                result = sendBatchOnStream(batch);
                requireTransactionStream();
            }
            return extractBatchResults(result, statements.size());
        }
    }

    private List<StmtResult> extractBatchResults(BatchResult batchResult, int expectedSize) throws SQLException {
        List<StmtResult> results = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            HranaError error = batchResult.stepErrors().get(i);
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
            StmtResult result = batchResult.stepResults().get(i);
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

    /** Maps a transport failure into the JDBC exception hierarchy. */
    private <T> T sending(Supplier<T> call) throws SQLException {
        try {
            return call.get();
        } catch (LibsqlException e) {
            throw LibsqlErrorMapper.toSQLException(e);
        }
    }

    /**
     * Sends an auto-commit statement on the connection's session stream. sqld expires idle streams
     * (around 10 seconds); when it rejects the baton, the statement did not run, so the driver drops
     * the stale stream and retries once on a fresh one. Auto-commit has no transaction to lose.
     * Session state set before the expiry is gone, by design.
     *
     * <p>Callers hold {@link #stateLock}.
     */
    private StmtResult sendAutoCommit(Stmt stmt) throws SQLException {
        try {
            return client.executeOnStream(stmt);
        } catch (LibsqlException e) {
            if (!isExpiredStream(e)) {
                throw LibsqlErrorMapper.toSQLException(e);
            }
            closeStreamQuietly();
            try {
                return client.executeOnStream(stmt);
            } catch (LibsqlException retry) {
                throw LibsqlErrorMapper.toSQLException(retry);
            }
        }
    }

    /** Auto-commit counterpart of {@link #sendAutoCommit(Stmt)}, with the same one retry. */
    private BatchResult sendBatchAutoCommit(Batch batch) throws SQLException {
        try {
            return client.executeBatchOnStream(batch);
        } catch (LibsqlException e) {
            if (!isExpiredStream(e)) {
                throw LibsqlErrorMapper.toSQLException(e);
            }
            closeStreamQuietly();
            try {
                return client.executeBatchOnStream(batch);
            } catch (LibsqlException retry) {
                throw LibsqlErrorMapper.toSQLException(retry);
            }
        }
    }

    private static boolean isExpiredStream(LibsqlException e) {
        String code = e.getCode();
        if (code != null) {
            String upper = code.toUpperCase(java.util.Locale.ROOT);
            if (upper.equals("STREAM_EXPIRED")
                    || upper.equals("BATON_EXPIRED")
                    || upper.equals("STALE_BATON")) {
                return true;
            }
        }
        String message = e.getMessage();
        return message != null
                && message.toLowerCase(java.util.Locale.ROOT).contains("stream has expired");
    }

    /** Stream sends. Callers hold {@link #stateLock}. */
    private StmtResult sendOnStream(Stmt stmt) throws SQLException {
        return sending(() -> client.executeOnStream(stmt));
    }

    private BatchResult sendBatchOnStream(Batch batch) throws SQLException {
        return sending(() -> client.executeBatchOnStream(batch));
    }

    /**
     * Fails the transaction when the server closed its stream anyway. Executing the next statement
     * on a fresh stream would silently run it outside the transaction the caller asked for.
     * Callers hold {@link #stateLock}.
     */
    private void requireTransactionStream() throws SQLException {
        if (!client.hasOpenStream()) {
            transactionActive = false;
            clearSavepoints();
            throw new SQLException("The server closed the transaction stream");
        }
    }

    /**
     * Begins the manual transaction lazily. An auto-commit stream may already be open when the
     * first transactional statement arrives, and that stream may have expired while the connection
     * was idle. The begin did not run in that case, so drop the stale stream and retry once.
     *
     * <p>Callers hold {@link #stateLock}.
     */
    private void ensureTransactionStarted() throws SQLException {
        if (transactionActive) return;
        Stmt begin = new Stmt(transactionMode.beginSql());
        try {
            sendBegin(begin);
        } catch (LibsqlException e) {
            if (!isExpiredStream(e)) {
                closeStreamQuietly();
                throw LibsqlErrorMapper.toSQLException(e);
            }
            closeStreamQuietly();
            try {
                sendBegin(begin);
            } catch (LibsqlException retry) {
                closeStreamQuietly();
                throw LibsqlErrorMapper.toSQLException(retry);
            }
        } catch (SQLException e) {
            // The begin failed, so the fresh stream holds no transaction state.
            closeStreamQuietly();
            throw e;
        }
    }

    /** Callers hold {@link #stateLock}. */
    private void sendBegin(Stmt begin) throws SQLException {
        client.executeOnStream(begin);
        if (!client.hasOpenStream()) {
            throw new SQLException("The server closed the stream before the transaction began");
        }
        transactionActive = true;
    }

    /** Callers hold {@link #stateLock}. */
    private void closeStreamQuietly() {
        if (client == null) {
            // Only a connection built directly with no transport (as some tests do) reaches this.
            return;
        }
        try {
            client.closeStream();
        } catch (LibsqlException ignored) {
            // Closing a stream is best effort: the transaction is already over or the transport is
            // gone, and the server reaps the stream either way.
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
     *       the configured {@link TransactionMode}. No request is sent. An auto-commit stream that
     *       was already open stays open and the begin runs on it, so session state set in
     *       auto-commit survives into the transaction.
     *   <li>{@link #commit()} and {@link #rollback()} end the open transaction and close its stream
     *       without beginning the next one. A commit or rollback with no work sent nothing to the
     *       server, as in xerial.
     *   <li>Re-enabling auto-commit commits the open transaction and ends the Hrana transaction
     *       stream. The next auto-commit statement opens a fresh session stream.
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
            requireManualCommit("commit");
            endTransaction("COMMIT");
        }
    }

    @Override
    public void rollback() throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            requireManualCommit("rollback");
            endTransaction("ROLLBACK");
        }
    }

    /** Callers hold {@link #stateLock}. */
    private void endTransaction(String sql) throws SQLException {
        if (!transactionActive) {
            // No transaction has started, so there is nothing to send and nothing to close.
            return;
        }

        sendOnStream(new Stmt(sql));

        // The server committed or rolled back, so the stream has no state left worth holding.
        // Closing it lets an idle connection drop its server-side stream. On an error above the
        // stream stays, so the caller can retry or roll back on the same stream.
        transactionActive = false;
        clearSavepoints();
        closeStreamQuietly();
    }

    @Override
    public void close() throws SQLException {
        synchronized (stateLock) {
            if (!closed) {
                closed = true;
                transactionActive = false;
                clearSavepoints();
                closeStreamQuietly();
                if (client != null) {
                    client.close();
                }
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
     * Creates a savepoint. When the connection is in auto-commit mode this starts a transaction,
     * matching xerial sqlite-jdbc 3.53.4.0 and SQLite itself; inside a transaction begun by {@link
     * #setAutoCommit(boolean)} the savepoint is nested in the lazily begun transaction instead.
     */
    @Override
    public Savepoint setSavepoint() throws SQLException {
        return setSavepointInternal(null);
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        checkClosed();
        if (name == null || name.isBlank()) {
            throw new SQLException("Savepoint name cannot be null or empty");
        }
        return setSavepointInternal(name);
    }

    private Savepoint setSavepointInternal(String name) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            LibsqlSavepoint savepoint = new LibsqlSavepoint(++nextSavepointId, name);
            String sqlName = savepoint.sqlName();
            Stmt stmt = new Stmt("SAVEPOINT " + quoteSavepointName(sqlName));
            if (autoCommit) {
                beginSavepointTransaction(stmt, sqlName);
            } else {
                ensureTransactionStarted();
                sendOnStream(stmt);
                savepointNames.add(sqlName);
            }
            return savepoint;
        }
    }

    /**
     * Starts the transaction an auto-commit savepoint owns. SQLite gives the savepoint the begin,
     * so no {@code BEGIN} is sent; releasing this savepoint later commits the transaction and
     * closes the stream. Callers hold {@link #stateLock}.
     */
    private void beginSavepointTransaction(Stmt stmt, String sqlName) throws SQLException {
        try {
            sendOnStream(stmt);
            requireTransactionStream();
        } catch (SQLException e) {
            // No transaction was started, so the fresh stream holds no state.
            closeStreamQuietly();
            throw e;
        }
        autoCommit = false;
        transactionActive = true;
        transactionStartedBySavepoint = true;
        savepointNames.clear();
        savepointNames.add(sqlName);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            String sqlName = LibsqlSavepoint.sqlNameOf(savepoint);
            requireManualCommit("rollback to a savepoint");
            ensureTransactionStarted();
            sendOnStream(new Stmt("ROLLBACK TO SAVEPOINT " + quoteSavepointName(sqlName)));
            discardSavepointsAfter(sqlName);
        }
    }

    /** Cancels the savepoints created after the target, which stays active. Callers hold {@link #stateLock}. */
    private void discardSavepointsAfter(String sqlName) {
        int index = savepointNames.lastIndexOf(sqlName);
        if (index >= 0) {
            savepointNames.subList(index + 1, savepointNames.size()).clear();
        }
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        synchronized (stateLock) {
            checkClosed();
            String sqlName = LibsqlSavepoint.sqlNameOf(savepoint);
            requireManualCommit("release a savepoint");
            ensureTransactionStarted();
            sendOnStream(new Stmt("RELEASE SAVEPOINT " + quoteSavepointName(sqlName)));
            closeTransactionOwnedBySavepoint(sqlName);
        }
    }

    /**
     * Drops the released savepoint and the ones nested inside it. A duplicated name resolves to the
     * later savepoint, so only releasing the outermost one closes the stream: that release commits
     * the transaction the savepoint started. Callers hold {@link #stateLock}.
     */
    private void closeTransactionOwnedBySavepoint(String sqlName) {
        int index = savepointNames.lastIndexOf(sqlName);
        if (index < 0) return;
        savepointNames.subList(index, savepointNames.size()).clear();
        if (index > 0 || !transactionStartedBySavepoint) return;
        transactionStartedBySavepoint = false;
        transactionActive = false;
        closeStreamQuietly();
    }

    /**
     * Rejects work JDBC allows only with auto-commit off. The action finishes the message, so
     * commit, rollback, and savepoint release each keep their own wording. Callers hold {@link
     * #stateLock}.
     */
    private void requireManualCommit(String action) throws SQLException {
        if (autoCommit) {
            throw new SQLException("Cannot " + action + " when in autoCommit mode");
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
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException { throw new SQLFeatureNotSupportedException("createArrayOf is not supported"); }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException { throw new SQLFeatureNotSupportedException("createStruct is not supported"); }

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
