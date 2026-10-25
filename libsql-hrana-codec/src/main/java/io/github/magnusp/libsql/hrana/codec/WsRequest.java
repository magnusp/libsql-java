package io.github.magnusp.libsql.hrana.codec;

/**
 * The payload of a Hrana 3 WebSocket {@code request} message.
 *
 * <p>Unlike the HTTP variant, a stream is opened once per WebSocket connection and then reused for
 * an arbitrary number of execute and batch requests.
 */
public sealed interface WsRequest {

    record OpenStream(int streamId) implements WsRequest {}

    record CloseStream(int streamId) implements WsRequest {}

    record Execute(int streamId, Stmt stmt) implements WsRequest {}

    // The component type is qualified because this nested record is itself named Batch and would
    // otherwise shadow the top-level Batch type.
    record Batch(int streamId, io.github.magnusp.libsql.hrana.codec.Batch batch) implements WsRequest {}
}
