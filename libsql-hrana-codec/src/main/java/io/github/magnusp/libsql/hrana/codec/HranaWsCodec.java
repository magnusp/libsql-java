package io.github.magnusp.libsql.hrana.codec;

import java.io.IOException;

/**
 * SPI for serializing and deserializing Hrana 3 WebSocket messages.
 *
 * <p>One instance handles one frame at a time. With the JSON encoding every message is a complete
 * UTF-8 JSON text frame; with the Protobuf encoding it is a binary frame.
 */
public interface HranaWsCodec {

    /** Whether messages must be sent as binary frames ({@code true}) or text frames. */
    boolean isBinary();

    byte[] serialize(WsClientMsg msg) throws IOException;

    WsServerMsg deserialize(byte[] payload) throws IOException;
}
