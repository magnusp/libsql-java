package io.github.magnusp.libsql.hrana.codec;

/** The payload of a successful Hrana 3 WebSocket response. Its type matches the request type. */
public sealed interface WsResponse {

    record OpenStream() implements WsResponse {
        public static final OpenStream INSTANCE = new OpenStream();
    }

    record CloseStream() implements WsResponse {
        public static final CloseStream INSTANCE = new CloseStream();
    }

    record Execute(StmtResult result) implements WsResponse {}

    record Batch(BatchResult result) implements WsResponse {}
}
