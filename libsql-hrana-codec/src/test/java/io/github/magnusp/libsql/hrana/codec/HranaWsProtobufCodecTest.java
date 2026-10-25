package io.github.magnusp.libsql.hrana.codec;

import io.github.magnusp.libsql.hrana.proto.HranaProto;
import io.github.magnusp.libsql.hrana.proto.HranaWsProto;
import com.google.protobuf.ByteString;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HranaWsProtobufCodecTest {

    private final HranaWsProtobufCodec codec = new HranaWsProtobufCodec();

    private HranaWsProto.ClientMsg serialize(WsClientMsg msg) throws IOException {
        return HranaWsProto.ClientMsg.parseFrom(codec.serialize(msg));
    }

    private WsServerMsg deserialize(HranaWsProto.ServerMsg msg) throws IOException {
        return codec.deserialize(msg.toByteArray());
    }

    private static ByteString bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return ByteString.copyFrom(bytes);
    }

    @Test
    void codecUsesBinaryFrames() {
        assertThat(codec).isInstanceOf(HranaWsCodec.class);
        assertThat(codec.isBinary()).isTrue();
    }

    @Test
    void serializesHelloWithJwt() throws IOException {
        HranaWsProto.ClientMsg msg = serialize(new WsClientMsg.Hello("jwt-token"));

        assertThat(msg.getMsgCase()).isEqualTo(HranaWsProto.ClientMsg.MsgCase.HELLO);
        assertThat(msg.getHello().hasJwt()).isTrue();
        assertThat(msg.getHello().getJwt()).isEqualTo("jwt-token");
    }

    @Test
    void serializesHelloWithoutJwtWhenNull() throws IOException {
        HranaWsProto.ClientMsg msg = serialize(new WsClientMsg.Hello(null));

        assertThat(msg.getMsgCase()).isEqualTo(HranaWsProto.ClientMsg.MsgCase.HELLO);
        assertThat(msg.getHello().hasJwt()).isFalse();
    }

    @Test
    void serializesOpenAndCloseStreamRequests() throws IOException {
        HranaWsProto.RequestMsg open = serialize(
                new WsClientMsg.Request(7, new WsRequest.OpenStream(3))).getRequest();
        assertThat(open.getRequestId()).isEqualTo(7);
        assertThat(open.getRequestCase()).isEqualTo(HranaWsProto.RequestMsg.RequestCase.OPEN_STREAM);
        assertThat(open.getOpenStream().getStreamId()).isEqualTo(3);

        HranaWsProto.RequestMsg close = serialize(
                new WsClientMsg.Request(8, new WsRequest.CloseStream(4))).getRequest();
        assertThat(close.getRequestId()).isEqualTo(8);
        assertThat(close.getRequestCase()).isEqualTo(HranaWsProto.RequestMsg.RequestCase.CLOSE_STREAM);
        assertThat(close.getCloseStream().getStreamId()).isEqualTo(4);
    }

    @Test
    void serializesExecuteWithEveryValueTypeAndBothArgStyles() throws IOException {
        Stmt stmt = new Stmt(
                "SELECT :a",
                12,
                List.of(
                        new Value.Integer(Long.MIN_VALUE),
                        new Value.Integer(Long.MAX_VALUE),
                        new Value.Float(1.5),
                        new Value.Text("h\u00e9llo \u4e16\u754c \ud83d\ude00"),
                        Value.Null.INSTANCE,
                        new Value.Blob(new byte[0]),
                        new Value.Blob(new byte[] {1, 2, 3})),
                List.of(
                        new NamedArg(":a", new Value.Text("named")),
                        new NamedArg(":b", new Value.Integer(7))),
                false);

        HranaWsProto.RequestMsg request = serialize(
                new WsClientMsg.Request(9, new WsRequest.Execute(5, stmt))).getRequest();

        assertThat(request.getRequestCase()).isEqualTo(HranaWsProto.RequestMsg.RequestCase.EXECUTE);
        assertThat(request.getExecute().getStreamId()).isEqualTo(5);

        HranaProto.Stmt protoStmt = request.getExecute().getStmt();
        assertThat(protoStmt.hasSql()).isTrue();
        assertThat(protoStmt.getSql()).isEqualTo("SELECT :a");
        assertThat(protoStmt.hasSqlId()).isTrue();
        assertThat(protoStmt.getSqlId()).isEqualTo(12);
        assertThat(protoStmt.hasWantRows()).isTrue();
        assertThat(protoStmt.getWantRows()).isFalse();
        assertThat(protoStmt.getArgsCount()).isEqualTo(7);

        assertThat(protoStmt.getArgs(0).getValueCase())
                .isEqualTo(HranaProto.Value.ValueCase.INTEGER);
        assertThat(protoStmt.getArgs(0).getInteger()).isEqualTo(Long.MIN_VALUE);
        assertThat(protoStmt.getArgs(1).getInteger()).isEqualTo(Long.MAX_VALUE);
        assertThat(protoStmt.getArgs(2).getValueCase())
                .isEqualTo(HranaProto.Value.ValueCase.FLOAT);
        assertThat(protoStmt.getArgs(2).getFloat()).isEqualTo(1.5);
        assertThat(protoStmt.getArgs(3).getText()).isEqualTo("h\u00e9llo \u4e16\u754c \ud83d\ude00");
        assertThat(protoStmt.getArgs(4).getValueCase())
                .isEqualTo(HranaProto.Value.ValueCase.NULL);
        assertThat(protoStmt.getArgs(5).getValueCase())
                .isEqualTo(HranaProto.Value.ValueCase.BLOB);
        assertThat(protoStmt.getArgs(5).getBlob()).isEqualTo(ByteString.EMPTY);
        assertThat(protoStmt.getArgs(6).getBlob()).isEqualTo(bytes(1, 2, 3));

        assertThat(protoStmt.getNamedArgsCount()).isEqualTo(2);
        assertThat(protoStmt.getNamedArgs(0).getName()).isEqualTo(":a");
        assertThat(protoStmt.getNamedArgs(0).getValue().getText()).isEqualTo("named");
        assertThat(protoStmt.getNamedArgs(1).getName()).isEqualTo(":b");
        assertThat(protoStmt.getNamedArgs(1).getValue().getInteger()).isEqualTo(7);
    }

    @Test
    void serializesBatchWithEveryConditionAndAnUnconditionalStep() throws IOException {
        Batch batch = new Batch(List.of(
                new BatchStep(new BatchCond.StepOk(0), new Stmt("BEGIN")),
                new BatchStep(new BatchCond.StepError(1), new Stmt("ROLLBACK")),
                new BatchStep(new BatchCond.Not(new BatchCond.StepOk(2)), new Stmt("COMMIT")),
                new BatchStep(new BatchCond.And(List.of(
                        new BatchCond.StepOk(0), new BatchCond.StepOk(1))), new Stmt("INSERT 1")),
                new BatchStep(new BatchCond.Or(List.of(
                        new BatchCond.StepError(0), BatchCond.IsAutocommit.INSTANCE)), new Stmt("INSERT 2")),
                new BatchStep(BatchCond.IsAutocommit.INSTANCE, new Stmt("INSERT 3")),
                new BatchStep(new Stmt("SELECT 1"))));

        HranaWsProto.RequestMsg request = serialize(
                new WsClientMsg.Request(10, new WsRequest.Batch(6, batch))).getRequest();

        assertThat(request.getRequestCase()).isEqualTo(HranaWsProto.RequestMsg.RequestCase.BATCH);
        assertThat(request.getBatch().getStreamId()).isEqualTo(6);

        HranaProto.Batch protoBatch = request.getBatch().getBatch();
        assertThat(protoBatch.getStepsCount()).isEqualTo(7);
        assertThat(protoBatch.getSteps(0).getStmt().getSql()).isEqualTo("BEGIN");
        assertThat(protoBatch.getSteps(6).hasCondition()).isFalse();

        HranaProto.BatchCond stepOk = protoBatch.getSteps(0).getCondition();
        assertThat(stepOk.getCondCase()).isEqualTo(HranaProto.BatchCond.CondCase.STEP_OK);
        assertThat(stepOk.getStepOk()).isEqualTo(0);

        HranaProto.BatchCond stepError = protoBatch.getSteps(1).getCondition();
        assertThat(stepError.getCondCase()).isEqualTo(HranaProto.BatchCond.CondCase.STEP_ERROR);
        assertThat(stepError.getStepError()).isEqualTo(1);

        HranaProto.BatchCond not = protoBatch.getSteps(2).getCondition();
        assertThat(not.getCondCase()).isEqualTo(HranaProto.BatchCond.CondCase.NOT);
        assertThat(not.getNot().getCondCase()).isEqualTo(HranaProto.BatchCond.CondCase.STEP_OK);

        HranaProto.BatchCond and = protoBatch.getSteps(3).getCondition();
        assertThat(and.getCondCase()).isEqualTo(HranaProto.BatchCond.CondCase.AND);
        assertThat(and.getAnd().getCondsCount()).isEqualTo(2);
        assertThat(and.getAnd().getConds(1).getStepOk()).isEqualTo(1);

        HranaProto.BatchCond or = protoBatch.getSteps(4).getCondition();
        assertThat(or.getCondCase()).isEqualTo(HranaProto.BatchCond.CondCase.OR);
        assertThat(or.getOr().getCondsCount()).isEqualTo(2);
        assertThat(or.getOr().getConds(0).getCondCase())
                .isEqualTo(HranaProto.BatchCond.CondCase.STEP_ERROR);
        assertThat(or.getOr().getConds(1).getCondCase())
                .isEqualTo(HranaProto.BatchCond.CondCase.IS_AUTOCOMMIT);

        assertThat(protoBatch.getSteps(5).getCondition().getCondCase())
                .isEqualTo(HranaProto.BatchCond.CondCase.IS_AUTOCOMMIT);
    }

    @Test
    void deserializesHelloOk() throws IOException {
        WsServerMsg msg = deserialize(HranaWsProto.ServerMsg.newBuilder()
                .setHelloOk(HranaWsProto.HelloOkMsg.getDefaultInstance())
                .build());

        assertThat(msg).isEqualTo(WsServerMsg.HelloOk.INSTANCE);
    }

    @Test
    void deserializesHelloErrorWithAndWithoutCode() throws IOException {
        WsServerMsg withCode = deserialize(HranaWsProto.ServerMsg.newBuilder()
                .setHelloError(HranaWsProto.HelloErrorMsg.newBuilder()
                        .setError(HranaProto.Error.newBuilder()
                                .setMessage("bad jwt")
                                .setCode("AUTH")))
                .build());
        assertThat(withCode).isEqualTo(
                new WsServerMsg.HelloError(new HranaError("bad jwt", "AUTH")));

        WsServerMsg withoutCode = deserialize(HranaWsProto.ServerMsg.newBuilder()
                .setHelloError(HranaWsProto.HelloErrorMsg.newBuilder()
                        .setError(HranaProto.Error.newBuilder().setMessage("bad jwt")))
                .build());
        assertThat(withoutCode).isEqualTo(
                new WsServerMsg.HelloError(new HranaError("bad jwt", null)));
    }

    @Test
    void deserializesOpenAndCloseStreamResponses() throws IOException {
        assertThat(deserialize(HranaWsProto.ServerMsg.newBuilder()
                        .setResponseOk(HranaWsProto.ResponseOkMsg.newBuilder()
                                .setRequestId(1)
                                .setOpenStream(HranaWsProto.OpenStreamResp.getDefaultInstance()))
                        .build()))
                .isEqualTo(new WsServerMsg.ResponseOk(1, WsResponse.OpenStream.INSTANCE));

        assertThat(deserialize(HranaWsProto.ServerMsg.newBuilder()
                        .setResponseOk(HranaWsProto.ResponseOkMsg.newBuilder()
                                .setRequestId(2)
                                .setCloseStream(HranaWsProto.CloseStreamResp.getDefaultInstance()))
                        .build()))
                .isEqualTo(new WsServerMsg.ResponseOk(2, WsResponse.CloseStream.INSTANCE));
    }

    @Test
    void deserializesExecuteResponseWithEveryValueType() throws IOException {
        HranaProto.StmtResult result = HranaProto.StmtResult.newBuilder()
                .addCols(HranaProto.Col.newBuilder().setName("id").setDecltype("INTEGER"))
                .addCols(HranaProto.Col.newBuilder().setName("value"))
                .addRows(HranaProto.Row.newBuilder()
                        .addValues(HranaProto.Value.newBuilder().setNull(
                                HranaProto.Value.Null.getDefaultInstance()))
                        .addValues(HranaProto.Value.newBuilder().setInteger(Long.MIN_VALUE))
                        .addValues(HranaProto.Value.newBuilder().setInteger(Long.MAX_VALUE))
                        .addValues(HranaProto.Value.newBuilder().setFloat(1.5))
                        .addValues(HranaProto.Value.newBuilder().setText(
                                "h\u00e9llo \u4e16\u754c \ud83d\ude00")))
                .addRows(HranaProto.Row.newBuilder()
                        .addValues(HranaProto.Value.newBuilder().setBlob(ByteString.EMPTY))
                        .addValues(HranaProto.Value.newBuilder().setBlob(bytes(1, 2, 3))))
                .setAffectedRowCount(2)
                .build();

        WsServerMsg msg = deserialize(HranaWsProto.ServerMsg.newBuilder()
                .setResponseOk(HranaWsProto.ResponseOkMsg.newBuilder()
                        .setRequestId(11)
                        .setExecute(HranaWsProto.ExecuteResp.newBuilder().setResult(result)))
                .build());

        StmtResult expected = new StmtResult(
                List.of(new Col("id", "INTEGER"), new Col("value", null)),
                List.of(
                        new Row(List.of(
                                Value.Null.INSTANCE,
                                new Value.Integer(Long.MIN_VALUE),
                                new Value.Integer(Long.MAX_VALUE),
                                new Value.Float(1.5),
                                new Value.Text("h\u00e9llo \u4e16\u754c \ud83d\ude00"))),
                        new Row(List.of(
                                new Value.Blob(new byte[0]),
                                new Value.Blob(new byte[] {1, 2, 3})))),
                2,
                null);

        assertThat(msg).isEqualTo(new WsServerMsg.ResponseOk(11, new WsResponse.Execute(expected)));
    }

    @Test
    void deserializesExecuteResponseWithLastInsertRowid() throws IOException {
        HranaProto.StmtResult result = HranaProto.StmtResult.newBuilder()
                .setAffectedRowCount(1)
                .setLastInsertRowid(Long.MIN_VALUE)
                .build();

        WsServerMsg msg = deserialize(HranaWsProto.ServerMsg.newBuilder()
                .setResponseOk(HranaWsProto.ResponseOkMsg.newBuilder()
                        .setRequestId(12)
                        .setExecute(HranaWsProto.ExecuteResp.newBuilder().setResult(result)))
                .build());

        assertThat(msg).isEqualTo(new WsServerMsg.ResponseOk(
                12,
                new WsResponse.Execute(
                        new StmtResult(List.of(), List.of(), 1, Long.MIN_VALUE))));
    }

    @Test
    void deserializesBatchResponseMaps() throws IOException {
        HranaProto.BatchResult result = HranaProto.BatchResult.newBuilder()
                .putStepResults(0, HranaProto.StmtResult.newBuilder()
                        .setAffectedRowCount(1)
                        .setLastInsertRowid(42)
                        .build())
                .putStepErrors(1, HranaProto.Error.newBuilder()
                        .setMessage("UNIQUE constraint failed: t.id")
                        .setCode("SQLITE_CONSTRAINT")
                        .build())
                .build();

        WsServerMsg msg = deserialize(HranaWsProto.ServerMsg.newBuilder()
                .setResponseOk(HranaWsProto.ResponseOkMsg.newBuilder()
                        .setRequestId(13)
                        .setBatch(HranaWsProto.BatchResp.newBuilder().setResult(result)))
                .build());

        BatchResult expected = new BatchResult(
                Map.of(0, new StmtResult(List.of(), List.of(), 1, 42L)),
                Map.of(1, new HranaError("UNIQUE constraint failed: t.id", "SQLITE_CONSTRAINT")));

        assertThat(msg).isEqualTo(new WsServerMsg.ResponseOk(13, new WsResponse.Batch(expected)));
    }

    @Test
    void deserializesResponseError() throws IOException {
        WsServerMsg msg = deserialize(HranaWsProto.ServerMsg.newBuilder()
                .setResponseError(HranaWsProto.ResponseErrorMsg.newBuilder()
                        .setRequestId(14)
                        .setError(HranaProto.Error.newBuilder()
                                .setMessage("no such table: t")
                                .setCode("SQLITE_ERROR")))
                .build());

        assertThat(msg).isEqualTo(new WsServerMsg.ResponseError(
                14, new HranaError("no such table: t", "SQLITE_ERROR")));
    }

    @Test
    void rejectsMessagesWithoutAPayload() {
        HranaWsProto.ServerMsg empty = HranaWsProto.ServerMsg.getDefaultInstance();
        assertThatThrownBy(() -> deserialize(empty))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("msg case");

        HranaProto.Value valueWithoutCase = HranaProto.Value.getDefaultInstance();
        HranaProto.StmtResult result = HranaProto.StmtResult.newBuilder()
                .addRows(HranaProto.Row.newBuilder().addValues(valueWithoutCase))
                .build();
        HranaWsProto.ServerMsg response = HranaWsProto.ServerMsg.newBuilder()
                .setResponseOk(HranaWsProto.ResponseOkMsg.newBuilder()
                        .setRequestId(15)
                        .setExecute(HranaWsProto.ExecuteResp.newBuilder().setResult(result)))
                .build();
        assertThatThrownBy(() -> deserialize(response))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("value case");
    }

    @Test
    void serializesUtf8TextWithoutReplacement() throws IOException {
        String unicode = "gr\u00fc\u00dfe \u65e5\u672c\u8a9e \u20ac";
        HranaWsProto.ClientMsg msg = serialize(
                new WsClientMsg.Request(16, new WsRequest.Execute(1, new Stmt(
                        "SELECT ?",
                        null,
                        List.of(new Value.Text(unicode)),
                        List.of(),
                        true))));

        String decoded = msg.getRequest().getExecute().getStmt().getArgs(0).getText();
        assertThat(decoded).isEqualTo(unicode);
        assertThat(decoded.getBytes(StandardCharsets.UTF_8))
                .isEqualTo(unicode.getBytes(StandardCharsets.UTF_8));
    }
}
