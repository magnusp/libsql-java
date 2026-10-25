package io.github.magnusp.libsql.hrana.codec;

/** A message sent by the server over a Hrana 3 WebSocket connection. */
public sealed interface WsServerMsg {

    record HelloOk() implements WsServerMsg {
        public static final HelloOk INSTANCE = new HelloOk();
    }

    record HelloError(HranaError error) implements WsServerMsg {}

    record ResponseOk(int requestId, WsResponse response) implements WsServerMsg {}

    record ResponseError(int requestId, HranaError error) implements WsServerMsg {}
}
