package io.github.magnusp.libsql.hrana.codec;

/**
 * A message sent by the client over a Hrana 3 WebSocket connection.
 *
 * <p>The {@code hello} message authenticates the connection; every other message is a numbered
 * request whose response carries the same {@code request_id}. See the Hrana 3 specification,
 * "Hrana over WebSocket".
 */
public sealed interface WsClientMsg {

    /** Authenticates (or re-authenticates) the connection. A null JWT means no authentication. */
    record Hello(String jwt) implements WsClientMsg {}

    /** Carries a numbered request; the server answers with a matching {@code response_ok/error}. */
    record Request(int requestId, WsRequest request) implements WsClientMsg {}
}
