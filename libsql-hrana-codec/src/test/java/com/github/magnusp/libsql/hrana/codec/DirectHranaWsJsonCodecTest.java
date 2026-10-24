package com.github.magnusp.libsql.hrana.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectHranaWsJsonCodecTest {

    private final DirectHranaJsonCodec codec = new DirectHranaJsonCodec();

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void codecUsesTextFrames() {
        assertThat(codec).isInstanceOf(HranaWsCodec.class);
        assertThat(codec.isBinary()).isFalse();
    }

    @Test
    void serializesHello() throws IOException {
        assertThat(text(codec.serialize(new WsClientMsg.Hello("jwt-token"))))
                .isEqualTo("{\"type\":\"hello\",\"jwt\":\"jwt-token\"}");
        assertThat(text(codec.serialize(new WsClientMsg.Hello(null))))
                .isEqualTo("{\"type\":\"hello\",\"jwt\":null}");
    }

    @Test
    void serializesOpenStreamRequest() throws IOException {
        assertThat(text(codec.serialize(new WsClientMsg.Request(7, new WsRequest.OpenStream(3)))))
                .isEqualTo("{\"type\":\"request\",\"request_id\":7,"
                        + "\"request\":{\"type\":\"open_stream\",\"stream_id\":3}}");
    }

    @Test
    void serializesCloseStreamRequest() throws IOException {
        assertThat(text(codec.serialize(new WsClientMsg.Request(8, new WsRequest.CloseStream(3)))))
                .isEqualTo("{\"type\":\"request\",\"request_id\":8,"
                        + "\"request\":{\"type\":\"close_stream\",\"stream_id\":3}}");
    }

    @Test
    void serializesExecuteWithPositionalAndNamedArgs() throws IOException {
        Stmt stmt = new Stmt(
                "SELECT :a, :b",
                null,
                List.of(Value.of(42L), Value.of("text \"quoted\"")),
                List.of(new NamedArg(":a", Value.of(1.5)), new NamedArg(":b", new Value.Blob(new byte[] {1, 2, 3}))),
                true);

        String json = text(codec.serialize(new WsClientMsg.Request(9, new WsRequest.Execute(4, stmt))));

        assertThat(json).isEqualTo("{\"type\":\"request\",\"request_id\":9,"
                + "\"request\":{\"type\":\"execute\",\"stream_id\":4,\"stmt\":{"
                + "\"sql\":\"SELECT :a, :b\","
                + "\"args\":["
                + "{\"type\":\"integer\",\"value\":\"42\"},"
                + "{\"type\":\"text\",\"value\":\"text \\\"quoted\\\"\"}],"
                + "\"named_args\":["
                + "{\"name\":\":a\",\"value\":{\"type\":\"float\",\"value\":1.5}},"
                + "{\"name\":\":b\",\"value\":{\"type\":\"blob\",\"base64\":\"AQID\"}}],"
                + "\"want_rows\":true}}}");
    }

    @Test
    void serializesBatchRequest() throws IOException {
        Batch batch = new Batch(List.of(
                new BatchStep(new Stmt("BEGIN")),
                new BatchStep(new BatchCond.StepOk(0), new Stmt("INSERT INTO t VALUES (?)", List.of(Value.of(1L)))),
                new BatchStep(new BatchCond.Not(BatchCond.IsAutocommit.INSTANCE), new Stmt("COMMIT"))));

        String json = text(codec.serialize(new WsClientMsg.Request(10, new WsRequest.Batch(5, batch))));

        assertThat(json).isEqualTo("{\"type\":\"request\",\"request_id\":10,"
                + "\"request\":{\"type\":\"batch\",\"stream_id\":5,\"batch\":{\"steps\":["
                + "{\"stmt\":{\"sql\":\"BEGIN\",\"want_rows\":true}},"
                + "{\"condition\":{\"type\":\"ok\",\"step\":0},"
                + "\"stmt\":{\"sql\":\"INSERT INTO t VALUES (?)\","
                + "\"args\":[{\"type\":\"integer\",\"value\":\"1\"}],\"want_rows\":true}},"
                + "{\"condition\":{\"type\":\"not\",\"cond\":{\"type\":\"is_autocommit\"}},"
                + "\"stmt\":{\"sql\":\"COMMIT\",\"want_rows\":true}}]}}}");
    }

    @Test
    void deserializesHelloOk() throws IOException {
        assertThat(codec.deserialize(utf8("{\"type\":\"hello_ok\"}")))
                .isEqualTo(WsServerMsg.HelloOk.INSTANCE);
    }

    @Test
    void deserializesHelloError() throws IOException {
        assertThat(codec.deserialize(utf8(
                        "{\"type\":\"hello_error\",\"error\":{\"message\":\"bad jwt\",\"code\":\"AUTH\"}}")))
                .isEqualTo(new WsServerMsg.HelloError(new HranaError("bad jwt", "AUTH")));
    }

    @Test
    void deserializesOpenAndCloseStreamResponses() throws IOException {
        assertThat(codec.deserialize(utf8(
                        "{\"type\":\"response_ok\",\"request_id\":1,"
                                + "\"response\":{\"type\":\"open_stream\"}}")))
                .isEqualTo(new WsServerMsg.ResponseOk(1, WsResponse.OpenStream.INSTANCE));

        assertThat(codec.deserialize(utf8(
                        "{\"type\":\"response_ok\",\"request_id\":2,"
                                + "\"response\":{\"type\":\"close_stream\"}}")))
                .isEqualTo(new WsServerMsg.ResponseOk(2, WsResponse.CloseStream.INSTANCE));
    }

    @Test
    void deserializesExecuteResponse() throws IOException {
        String json = """
                {"type":"response_ok","request_id":11,"response":{"type":"execute","result":{
                  "cols":[{"name":"id","decltype":"INTEGER"},{"name":"name","decltype":"TEXT"}],
                  "rows":[[{"type":"integer","value":"1"},{"type":"text","value":"Alice"}],
                          [{"type":"null"},{"type":"blob","base64":"AQID"}]],
                  "affected_row_count":2,
                  "last_insert_rowid":"1",
                  "rows_read":5,
                  "rows_written":2,
                  "query_duration_ms":0.5
                }}}""";

        StmtResult expected = new StmtResult(
                List.of(new Col("id", "INTEGER"), new Col("name", "TEXT")),
                List.of(
                        new Row(List.of(new Value.Integer(1), new Value.Text("Alice"))),
                        new Row(List.of(Value.ofNull(), new Value.Blob(new byte[] {1, 2, 3})))),
                2,
                1L);

        assertThat(codec.deserialize(utf8(json)))
                .isEqualTo(new WsServerMsg.ResponseOk(11, new WsResponse.Execute(expected)));
    }

    @Test
    void deserializesBatchResponse() throws IOException {
        String json = """
                {"type":"response_ok","request_id":12,"response":{"type":"batch","result":{
                  "step_results":[{"cols":[],"rows":[],"affected_row_count":1,"last_insert_rowid":null},null],
                  "step_errors":[null,{"message":"UNIQUE constraint failed: t.id","code":"SQLITE_CONSTRAINT"}]
                }}}""";

        BatchResult expected = new BatchResult(
                Map.of(0, new StmtResult(List.of(), List.of(), 1, null)),
                Map.of(1, new HranaError("UNIQUE constraint failed: t.id", "SQLITE_CONSTRAINT")));

        assertThat(codec.deserialize(utf8(json)))
                .isEqualTo(new WsServerMsg.ResponseOk(12, new WsResponse.Batch(expected)));
    }

    @Test
    void deserializesResponseError() throws IOException {
        assertThat(codec.deserialize(utf8(
                        "{\"type\":\"response_error\",\"request_id\":13,"
                                + "\"error\":{\"message\":\"no such table: t\",\"code\":\"SQLITE_ERROR\"}}")))
                .isEqualTo(new WsServerMsg.ResponseError(
                        13, new HranaError("no such table: t", "SQLITE_ERROR")));
    }

    @Test
    void ignoresUnknownFields() throws IOException {
        assertThat(codec.deserialize(utf8(
                        "{\"type\":\"hello_ok\",\"future_field\":{\"nested\":[1,2,3]}}")))
                .isEqualTo(WsServerMsg.HelloOk.INSTANCE);
    }

    @Test
    void rejectsUnknownMessageTypes() {
        assertThatThrownBy(() -> codec.deserialize(utf8("{\"type\":\"mystery\"}")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("mystery");
        assertThatThrownBy(() -> codec.deserialize(utf8("{}")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("type");
    }
}
