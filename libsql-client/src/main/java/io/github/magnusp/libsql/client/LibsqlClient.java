package io.github.magnusp.libsql.client;

import io.github.magnusp.libsql.hrana.codec.Batch;
import io.github.magnusp.libsql.hrana.codec.BatchResult;
import io.github.magnusp.libsql.hrana.codec.Stmt;
import io.github.magnusp.libsql.hrana.codec.StmtResult;

/**
 * The transport-independent client seam used by the JDBC driver.
 *
 * <p>Both transports expose the same two execution styles. A <em>one-shot</em> call opens a stream,
 * runs the work and closes the stream in as few round trips as possible, so it leaves no server-side
 * state behind and needs no explicit cleanup. An <em>on-stream</em> call reuses a lazily opened
 * interactive stream, which is what a transaction needs: the server keeps the transaction open
 * between calls.
 *
 * <p>Implementations track their own stream state. {@link #hasOpenStream()} is the single source of
 * truth for whether a transaction begin is still pending.
 */
public interface LibsqlClient extends AutoCloseable {

    StmtResult executeOneShot(Stmt stmt) throws LibsqlException;

    StmtResult executeOnStream(Stmt stmt) throws LibsqlException;

    BatchResult executeBatchOneShot(Batch batch) throws LibsqlException;

    BatchResult executeBatchOnStream(Batch batch) throws LibsqlException;

    boolean hasOpenStream();

    /** Closes the interactive stream, if one is open, and releases its server-side state. */
    void closeStream() throws LibsqlException;

    @Override
    void close();
}
