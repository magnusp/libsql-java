package com.github.magnusp.libsql.hrana.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Pluggable SPI for serializing and deserializing Hrana v3 JSON payloads.
 */
public interface HranaJsonCodec {

    void serializePipelineRequest(PipelineReqBody req, OutputStream out) throws IOException;

    byte[] serializePipelineRequest(PipelineReqBody req) throws IOException;

    PipelineRespBody deserializePipelineResponse(InputStream in) throws IOException;

    PipelineRespBody deserializePipelineResponse(byte[] bytes) throws IOException;

    HranaError deserializeError(InputStream in) throws IOException;
}
