package tech.libsql.hrana.codec;

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
    void testDeserializePipelineResponse() throws IOException {
        String json = """
        {
          "baton": "baton-12345",
          "base_url": null,
          "results": [
            {
              "type": "ok",
              "response": {
                "type": "execute",
                "result": {
                  "cols": [{"name": "id", "decltype": "INT"}, {"name": "msg", "decltype": "TEXT"}],
                  "rows": [
                    [{"type": "integer", "value": "42"}, {"type": "text", "value": "world"}],
                    [{"type": "null"}, {"type": "blob", "base64": "AQID"}]
                  ],
                  "affected_row_count": 0,
                  "last_insert_rowid": "100"
                }
              }
            }
          ]
        }
        """;

        PipelineRespBody resp = codec.deserializePipelineResponse(json.getBytes(StandardCharsets.UTF_8));

        assertThat(resp.baton()).isEqualTo("baton-12345");
        assertThat(resp.baseUrl()).isNull();
        assertThat(resp.results()).hasSize(1);

        StreamResult result = resp.results().get(0);
        assertThat(result).isInstanceOf(StreamResult.Ok.class);

        StreamResponse streamResponse = ((StreamResult.Ok) result).response();
        assertThat(streamResponse).isInstanceOf(StreamResponse.Execute.class);

        StmtResult stmtResult = ((StreamResponse.Execute) streamResponse).result();
        assertThat(stmtResult.cols()).hasSize(2);
        assertThat(stmtResult.cols().get(0).name()).isEqualTo("id");
        assertThat(stmtResult.cols().get(0).decltype()).isEqualTo("INT");

        assertThat(stmtResult.rows()).hasSize(2);
        assertThat(stmtResult.rows().get(0).values().get(0)).isEqualTo(new Value.Integer(42L));
        assertThat(stmtResult.rows().get(0).values().get(1)).isEqualTo(new Value.Text("world"));
        assertThat(stmtResult.rows().get(1).values().get(0)).isEqualTo(Value.ofNull());
        assertThat(stmtResult.rows().get(1).values().get(1)).isEqualTo(new Value.Blob(new byte[]{1, 2, 3}));
        assertThat(stmtResult.lastInsertRowid()).isEqualTo(100L);
    }
}
