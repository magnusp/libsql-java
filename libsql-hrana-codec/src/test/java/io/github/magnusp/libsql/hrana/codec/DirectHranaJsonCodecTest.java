package io.github.magnusp.libsql.hrana.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class DirectHranaJsonCodecTest {

    private final DirectHranaJsonCodec codec = new DirectHranaJsonCodec();

    @Test
    void testSerializePipelineRequestOneShot() throws IOException {
        Stmt stmt = new Stmt("SELECT ? as num, ? as greeting", List.of(Value.of(9223372036854775807L), Value.of("hello \"world\"\nnewline")));
        PipelineReqBody req = new PipelineReqBody(
                null,
                List.of(
                        new StreamRequest.Execute(stmt),
                        StreamRequest.Close.INSTANCE
                )
        );

        byte[] jsonBytes = codec.serializePipelineRequest(req);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);

        assertThat(json).contains("\"baton\":null");
        assertThat(json).contains("\"type\":\"execute\"");
        assertThat(json).contains("\"type\":\"close\"");
        // 64-bit integer serialized as string
        assertThat(json).contains("\"value\":\"9223372036854775807\"");
        assertThat(json).contains("\"value\":\"hello \\\"world\\\"\\nnewline\"");
    }

    @Test
    void testSerializeSupplementaryCharactersAsUtf8() throws IOException {
        String supplementary = "\uD840\uDC40 and \uD880\uDC99";
        Stmt stmt = new Stmt("SELECT '" + supplementary + "'", List.of(Value.of(supplementary)));
        PipelineReqBody req = new PipelineReqBody(null, List.of(new StreamRequest.Execute(stmt)));

        byte[] jsonBytes = codec.serializePipelineRequest(req);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);

        assertThat(json).contains("\"sql\":\"SELECT '" + supplementary + "'\"");
        assertThat(json).contains("\"value\":\"" + supplementary + "\"");
        assertThat(json).doesNotContain("?");
    }

}
