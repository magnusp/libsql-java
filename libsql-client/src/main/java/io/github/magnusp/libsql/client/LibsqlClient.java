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
 * interactive stream. The JDBC driver uses the on-stream style for every statement, so auto-commit
 * retains SQLite session state such as {@code last_insert_rowid()} and {@code PRAGMA} settings, and
 * manual transactions stay open between calls.
 *
 * <p>Implementations track their own stream state. {@link #hasOpenStream()} tells a caller whether
 * the lazily opened stream still exists, which matters after the server expires an idle stream.
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
