package com.github.magnusp.libsql.hrana.codec;

import com.github.magnusp.libsql.hrana.proto.HranaProto;
import com.github.magnusp.libsql.hrana.proto.HranaWsProto;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hrana 3 WebSocket message codec for the Protobuf encoding ({@code hrana3-protobuf}
 * subprotocol).
 *
 * <p>Every client or server message is a complete {@code hrana.ws.ClientMsg} or {@code
 * hrana.ws.ServerMsg} serialized as one binary WebSocket frame.
 */
public final class HranaWsProtobufCodec implements HranaWsCodec {

    @Override
    public boolean isBinary() {
        return true;
    }

    @Override
    public byte[] serialize(WsClientMsg msg) throws IOException {
        HranaWsProto.ClientMsg.Builder clientMsg = HranaWsProto.ClientMsg.newBuilder();
        switch (msg) {
            case WsClientMsg.Hello hello -> {
                HranaWsProto.HelloMsg.Builder helloMsg = HranaWsProto.HelloMsg.newBuilder();
                if (hello.jwt() != null) {
                    helloMsg.setJwt(hello.jwt());
                }
                clientMsg.setHello(helloMsg);
            }
            case WsClientMsg.Request request -> {
                HranaWsProto.RequestMsg.Builder requestMsg = HranaWsProto.RequestMsg.newBuilder()
                        .setRequestId(request.requestId());
                switch (request.request()) {
                    case WsRequest.OpenStream open -> requestMsg.setOpenStream(
                            HranaWsProto.OpenStreamReq.newBuilder()
                                    .setStreamId(open.streamId()));
                    case WsRequest.CloseStream close -> requestMsg.setCloseStream(
                            HranaWsProto.CloseStreamReq.newBuilder()
                                    .setStreamId(close.streamId()));
                    case WsRequest.Execute execute -> requestMsg.setExecute(
                            HranaWsProto.ExecuteReq.newBuilder()
                                    .setStreamId(execute.streamId())
                                    .setStmt(toProto(execute.stmt())));
                    case WsRequest.Batch batch -> requestMsg.setBatch(
                            HranaWsProto.BatchReq.newBuilder()
                                    .setStreamId(batch.streamId())
                                    .setBatch(toProto(batch.batch())));
                }
                clientMsg.setRequest(requestMsg);
            }
        }
        return clientMsg.build().toByteArray();
    }

    @Override
    public WsServerMsg deserialize(byte[] payload) throws IOException {
        HranaWsProto.ServerMsg serverMsg = HranaWsProto.ServerMsg.parseFrom(payload);
        return switch (serverMsg.getMsgCase()) {
            case HELLO_OK -> WsServerMsg.HelloOk.INSTANCE;
            case HELLO_ERROR -> new WsServerMsg.HelloError(
                    fromProto(serverMsg.getHelloError().getError()));
            case RESPONSE_OK -> new WsServerMsg.ResponseOk(
                    serverMsg.getResponseOk().getRequestId(),
                    fromProto(serverMsg.getResponseOk()));
            case RESPONSE_ERROR -> new WsServerMsg.ResponseError(
                    serverMsg.getResponseError().getRequestId(),
                    fromProto(serverMsg.getResponseError().getError()));
            case MSG_NOT_SET -> throw new IOException(
                    "Hrana protobuf server message has no msg case");
        };
    }

    // -------------------------------------------------------------
    // Client structures
    // -------------------------------------------------------------

    private static HranaProto.Stmt toProto(Stmt stmt) {
        HranaProto.Stmt.Builder out = HranaProto.Stmt.newBuilder();
        if (stmt.sql() != null) {
            out.setSql(stmt.sql());
        }
        if (stmt.sqlId() != null) {
            out.setSqlId(stmt.sqlId());
        }
        if (stmt.args() != null) {
            for (Value arg : stmt.args()) {
                out.addArgs(toProto(arg));
            }
        }
        if (stmt.namedArgs() != null) {
            for (NamedArg namedArg : stmt.namedArgs()) {
                out.addNamedArgs(HranaProto.NamedArg.newBuilder()
                        .setName(namedArg.name())
                        .setValue(toProto(namedArg.value())));
            }
        }
        if (stmt.wantRows() != null) {
            out.setWantRows(stmt.wantRows());
        }
        return out.build();
    }

    private static HranaProto.Value toProto(Value value) {
        HranaProto.Value.Builder out = HranaProto.Value.newBuilder();
        switch (value) {
            case Value.Null ignored -> out.setNull(HranaProto.Value.Null.getDefaultInstance());
            case Value.Integer integer -> out.setInteger(integer.value());
            case Value.Float floating -> out.setFloat(floating.value());
            case Value.Text text -> out.setText(text.value());
            case Value.Blob blob -> out.setBlob(ByteString.copyFrom(blob.bytes()));
        }
        return out.build();
    }

    private static HranaProto.Batch toProto(Batch batch) {
        HranaProto.Batch.Builder out = HranaProto.Batch.newBuilder();
        for (BatchStep step : batch.steps()) {
            HranaProto.BatchStep.Builder stepMsg = HranaProto.BatchStep.newBuilder()
                    .setStmt(toProto(step.stmt()));
            if (step.condition() != null) {
                stepMsg.setCondition(toProto(step.condition()));
            }
            out.addSteps(stepMsg);
        }
        return out.build();
    }

    private static HranaProto.BatchCond toProto(BatchCond condition) {
        HranaProto.BatchCond.Builder out = HranaProto.BatchCond.newBuilder();
        switch (condition) {
            case BatchCond.StepOk stepOk -> out.setStepOk(stepOk.step());
            case BatchCond.StepError stepError -> out.setStepError(stepError.step());
            case BatchCond.Not not -> out.setNot(toProto(not.cond()));
            case BatchCond.And and -> out.setAnd(toProtoCondList(and.conds()));
            case BatchCond.Or or -> out.setOr(toProtoCondList(or.conds()));
            case BatchCond.IsAutocommit ignored -> out.setIsAutocommit(
                    HranaProto.BatchCond.IsAutocommit.getDefaultInstance());
        }
        return out.build();
    }

    private static HranaProto.BatchCond.CondList toProtoCondList(List<BatchCond> conditions) {
        HranaProto.BatchCond.CondList.Builder out = HranaProto.BatchCond.CondList.newBuilder();
        for (BatchCond condition : conditions) {
            out.addConds(toProto(condition));
        }
        return out.build();
    }

    // -------------------------------------------------------------
    // Server structures
    // -------------------------------------------------------------

    private static WsResponse fromProto(HranaWsProto.ResponseOkMsg responseMsg) throws IOException {
        return switch (responseMsg.getResponseCase()) {
            case OPEN_STREAM -> WsResponse.OpenStream.INSTANCE;
            case CLOSE_STREAM -> WsResponse.CloseStream.INSTANCE;
            case EXECUTE -> new WsResponse.Execute(
                    fromProto(responseMsg.getExecute().getResult()));
            case BATCH -> new WsResponse.Batch(
                    fromProto(responseMsg.getBatch().getResult()));
            case RESPONSE_NOT_SET -> throw new IOException(
                    "Hrana protobuf response has no response case");
            default -> throw new IOException("Unsupported Hrana protobuf response: "
                    + responseMsg.getResponseCase());
        };
    }

    private static StmtResult fromProto(HranaProto.StmtResult result) throws IOException {
        List<Col> cols = new ArrayList<>(result.getColsCount());
        for (HranaProto.Col col : result.getColsList()) {
            cols.add(fromProto(col));
        }
        List<Row> rows = new ArrayList<>(result.getRowsCount());
        for (HranaProto.Row row : result.getRowsList()) {
            rows.add(fromProto(row));
        }
        Long lastInsertRowid = result.hasLastInsertRowid()
                ? result.getLastInsertRowid()
                : null;
        return new StmtResult(cols, rows, result.getAffectedRowCount(), lastInsertRowid);
    }

    private static Col fromProto(HranaProto.Col col) {
        return new Col(
                col.hasName() ? col.getName() : null,
                col.hasDecltype() ? col.getDecltype() : null);
    }

    private static Row fromProto(HranaProto.Row row) throws IOException {
        List<Value> values = new ArrayList<>(row.getValuesCount());
        for (HranaProto.Value value : row.getValuesList()) {
            values.add(fromProto(value));
        }
        return new Row(values);
    }

    private static BatchResult fromProto(HranaProto.BatchResult result) throws IOException {
        Map<Integer, StmtResult> stepResults = new LinkedHashMap<>();
        for (Map.Entry<Integer, HranaProto.StmtResult> entry
                : result.getStepResultsMap().entrySet()) {
            stepResults.put(entry.getKey(), fromProto(entry.getValue()));
        }
        Map<Integer, HranaError> stepErrors = new LinkedHashMap<>();
        for (Map.Entry<Integer, HranaProto.Error> entry
                : result.getStepErrorsMap().entrySet()) {
            stepErrors.put(entry.getKey(), fromProto(entry.getValue()));
        }
        return new BatchResult(stepResults, stepErrors);
    }

    private static HranaError fromProto(HranaProto.Error error) {
        return new HranaError(
                error.getMessage(),
                error.hasCode() ? error.getCode() : null);
    }

    private static Value fromProto(HranaProto.Value value) throws IOException {
        return switch (value.getValueCase()) {
            case NULL -> Value.Null.INSTANCE;
            case INTEGER -> new Value.Integer(value.getInteger());
            case FLOAT -> new Value.Float(value.getFloat());
            case TEXT -> new Value.Text(value.getText());
            case BLOB -> new Value.Blob(value.getBlob().toByteArray());
            case VALUE_NOT_SET -> throw new IOException(
                    "Hrana protobuf Value has no value case");
        };
    }
}
