package com.github.magnusp.libsql.hrana.codec;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JacksonHranaJsonCodec implements HranaJsonCodec {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final Base64.Encoder B64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    @Override
    public byte[] serializePipelineRequest(PipelineReqBody req) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        serializePipelineRequest(req, baos);
        return baos.toByteArray();
    }

    @Override
    public void serializePipelineRequest(PipelineReqBody req, OutputStream out) throws IOException {
        try (JsonGenerator g = JSON_FACTORY.createGenerator(out)) {
            g.writeStartObject();
            if (req.baton() != null) {
                g.writeStringField("baton", req.baton());
            } else {
                g.writeNullField("baton");
            }

            g.writeArrayFieldStart("requests");
            for (StreamRequest streamReq : req.requests()) {
                writeStreamRequest(g, streamReq);
            }
            g.writeEndArray();

            g.writeEndObject();
        }
    }

    private void writeStreamRequest(JsonGenerator g, StreamRequest req) throws IOException {
        g.writeStartObject();
        switch (req) {
            case StreamRequest.Close c -> g.writeStringField("type", "close");
            case StreamRequest.Execute exec -> {
                g.writeStringField("type", "execute");
                g.writeFieldName("stmt");
                writeStmt(g, exec.stmt());
            }
            case StreamRequest.BatchReq b -> {
                g.writeStringField("type", "batch");
                g.writeFieldName("batch");
                writeBatch(g, b.batch());
            }
            case StreamRequest.Sequence seq -> {
                g.writeStringField("type", "sequence");
                if (seq.sql() != null) g.writeStringField("sql", seq.sql());
                if (seq.sqlId() != null) g.writeNumberField("sql_id", seq.sqlId());
            }
            case StreamRequest.Describe d -> {
                g.writeStringField("type", "describe");
                if (d.sql() != null) g.writeStringField("sql", d.sql());
                if (d.sqlId() != null) g.writeNumberField("sql_id", d.sqlId());
            }
            case StreamRequest.StoreSql s -> {
                g.writeStringField("type", "store_sql");
                g.writeNumberField("sql_id", s.sqlId());
                g.writeStringField("sql", s.sql());
            }
            case StreamRequest.CloseSql c -> {
                g.writeStringField("type", "close_sql");
                g.writeNumberField("sql_id", c.sqlId());
            }
            case StreamRequest.GetAutocommit a -> g.writeStringField("type", "get_autocommit");
        }
        g.writeEndObject();
    }

    private void writeStmt(JsonGenerator g, Stmt stmt) throws IOException {
        g.writeStartObject();
        if (stmt.sql() != null) g.writeStringField("sql", stmt.sql());
        if (stmt.sqlId() != null) g.writeNumberField("sql_id", stmt.sqlId());

        if (stmt.args() != null && !stmt.args().isEmpty()) {
            g.writeArrayFieldStart("args");
            for (Value arg : stmt.args()) {
                writeValue(g, arg);
            }
            g.writeEndArray();
        }

        if (stmt.namedArgs() != null && !stmt.namedArgs().isEmpty()) {
            g.writeArrayFieldStart("named_args");
            for (NamedArg namedArg : stmt.namedArgs()) {
                g.writeStartObject();
                g.writeStringField("name", namedArg.name());
                g.writeFieldName("value");
                writeValue(g, namedArg.value());
                g.writeEndObject();
            }
            g.writeEndArray();
        }

        if (stmt.wantRows() != null) {
            g.writeBooleanField("want_rows", stmt.wantRows());
        }
        g.writeEndObject();
    }

    private void writeValue(JsonGenerator g, Value value) throws IOException {
        g.writeStartObject();
        switch (value) {
            case Value.Null n -> g.writeStringField("type", "null");
            case Value.Integer i -> {
                g.writeStringField("type", "integer");
                // Per Hrana spec: 64-bit integer encoded as string
                g.writeStringField("value", Long.toString(i.value()));
            }
            case Value.Float f -> {
                g.writeStringField("type", "float");
                g.writeNumberField("value", f.value());
            }
            case Value.Text t -> {
                g.writeStringField("type", "text");
                g.writeStringField("value", t.value());
            }
            case Value.Blob b -> {
                g.writeStringField("type", "blob");
                g.writeStringField("base64", B64_ENCODER.encodeToString(b.bytes()));
            }
        }
        g.writeEndObject();
    }

    private void writeBatch(JsonGenerator g, Batch batch) throws IOException {
        g.writeStartObject();
        g.writeArrayFieldStart("steps");
        for (BatchStep step : batch.steps()) {
            g.writeStartObject();
            if (step.condition() != null) {
                g.writeFieldName("condition");
                writeBatchCond(g, step.condition());
            }
            g.writeFieldName("stmt");
            writeStmt(g, step.stmt());
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeEndObject();
    }

    private void writeBatchCond(JsonGenerator g, BatchCond cond) throws IOException {
        g.writeStartObject();
        switch (cond) {
            case BatchCond.StepOk ok -> {
                g.writeStringField("type", "ok");
                g.writeNumberField("step", ok.step());
            }
            case BatchCond.StepError err -> {
                g.writeStringField("type", "error");
                g.writeNumberField("step", err.step());
            }
            case BatchCond.Not not -> {
                g.writeStringField("type", "not");
                g.writeFieldName("cond");
                writeBatchCond(g, not.cond());
            }
            case BatchCond.And and -> {
                g.writeStringField("type", "and");
                g.writeArrayFieldStart("conds");
                for (BatchCond c : and.conds()) writeBatchCond(g, c);
                g.writeEndArray();
            }
            case BatchCond.Or or -> {
                g.writeStringField("type", "or");
                g.writeArrayFieldStart("conds");
                for (BatchCond c : or.conds()) writeBatchCond(g, c);
                g.writeEndArray();
            }
            case BatchCond.IsAutocommit a -> g.writeStringField("type", "is_autocommit");
        }
        g.writeEndObject();
    }

    @Override
    public PipelineRespBody deserializePipelineResponse(byte[] bytes) throws IOException {
        return deserializePipelineResponse(new ByteArrayInputStream(bytes));
    }

    @Override
    public PipelineRespBody deserializePipelineResponse(InputStream in) throws IOException {
        try (JsonParser p = JSON_FACTORY.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected START_OBJECT for PipelineRespBody");
            }

            String baton = null;
            String baseUrl = null;
            List<StreamResult> results = new ArrayList<>();

            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "baton" -> baton = (p.currentToken() == JsonToken.VALUE_NULL) ? null : p.getText();
                    case "base_url" -> baseUrl = (p.currentToken() == JsonToken.VALUE_NULL) ? null : p.getText();
                    case "results" -> {
                        if (p.currentToken() == JsonToken.START_ARRAY) {
                            while (p.nextToken() != JsonToken.END_ARRAY) {
                                results.add(readStreamResult(p));
                            }
                        } else {
                            p.skipChildren();
                        }
                    }
                    default -> p.skipChildren();
                }
            }

            return new PipelineRespBody(baton, baseUrl, results);
        }
    }

    private StreamResult readStreamResult(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw new IOException("Expected START_OBJECT for StreamResult");
        }

        String type = null;
        StreamResponse response = null;
        HranaError error = null;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "type" -> type = p.getText();
                case "response" -> response = readStreamResponse(p);
                case "error" -> error = readError(p);
                default -> p.skipChildren();
            }
        }

        if ("ok".equals(type)) {
            return new StreamResult.Ok(response);
        } else if ("error".equals(type)) {
            return new StreamResult.Error(error);
        } else {
            throw new IOException("Unknown StreamResult type: " + type);
        }
    }

    private StreamResponse readStreamResponse(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw new IOException("Expected START_OBJECT for StreamResponse");
        }

        String type = null;
        StmtResult stmtResult = null;
        BatchResult batchResult = null;
        DescribeResult describeResult = null;
        boolean isAutocommit = false;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "type" -> type = p.getText();
                case "result" -> {
                    // Could be StmtResult or BatchResult or DescribeResult depending on context
                    // We'll read based on current type or parse lazily
                    if ("execute".equals(type)) {
                        stmtResult = readStmtResult(p);
                    } else if ("batch".equals(type)) {
                        batchResult = readBatchResult(p);
                    } else if ("describe".equals(type)) {
                        describeResult = readDescribeResult(p);
                    } else {
                        // type hasn't been parsed yet, look ahead or inspect
                        p.skipChildren();
                    }
                }
                case "is_autocommit" -> isAutocommit = p.getBooleanValue();
                default -> p.skipChildren();
            }
        }

        if (type == null) throw new IOException("StreamResponse missing type field");
        return switch (type) {
            case "close" -> StreamResponse.Close.INSTANCE;
            case "execute" -> new StreamResponse.Execute(stmtResult);
            case "batch" -> new StreamResponse.BatchResp(batchResult);
            case "sequence" -> StreamResponse.Sequence.INSTANCE;
            case "describe" -> new StreamResponse.Describe(describeResult);
            case "store_sql" -> StreamResponse.StoreSql.INSTANCE;
            case "close_sql" -> StreamResponse.CloseSql.INSTANCE;
            case "get_autocommit" -> new StreamResponse.GetAutocommit(isAutocommit);
            default -> throw new IOException("Unsupported StreamResponse type: " + type);
        };
    }

    private StmtResult readStmtResult(JsonParser p) throws IOException {
        List<Col> cols = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        long affectedRowCount = 0;
        Long lastInsertRowid = null;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "cols" -> {
                    if (p.currentToken() == JsonToken.START_ARRAY) {
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            cols.add(readCol(p));
                        }
                    } else {
                        p.skipChildren();
                    }
                }
                case "rows" -> {
                    if (p.currentToken() == JsonToken.START_ARRAY) {
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            rows.add(readRow(p));
                        }
                    } else {
                        p.skipChildren();
                    }
                }
                case "affected_row_count" -> affectedRowCount = p.getLongValue();
                case "last_insert_rowid" -> {
                    if (p.currentToken() != JsonToken.VALUE_NULL) {
                        lastInsertRowid = Long.parseLong(p.getText());
                    }
                }
                default -> p.skipChildren();
            }
        }
        return new StmtResult(cols, rows, affectedRowCount, lastInsertRowid);
    }

    private Col readCol(JsonParser p) throws IOException {
        String name = null;
        String decltype = null;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "name" -> name = p.getText();
                case "decltype" -> decltype = p.getText();
                default -> p.skipChildren();
            }
        }
        return new Col(name, decltype);
    }

    private Row readRow(JsonParser p) throws IOException {
        List<Value> values = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            values.add(readValue(p));
        }
        return new Row(values);
    }

    private Value readValue(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw new IOException("Expected START_OBJECT for Value");
        }

        String type = null;
        String stringVal = null;
        double floatVal = 0.0;
        String base64Val = null;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "type" -> type = p.getText();
                case "value" -> {
                    if (p.currentToken() == JsonToken.VALUE_NUMBER_FLOAT || p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                        floatVal = p.getDoubleValue();
                    }
                    stringVal = p.getText();
                }
                case "base64" -> base64Val = p.getText();
                default -> p.skipChildren();
            }
        }

        if (type == null) throw new IOException("Value missing type field");
        return switch (type) {
            case "null" -> Value.ofNull();
            case "integer" -> new Value.Integer(Long.parseLong(stringVal));
            case "float" -> new Value.Float(floatVal);
            case "text" -> new Value.Text(stringVal != null ? stringVal : "");
            case "blob" -> new Value.Blob(base64Val != null ? B64_DECODER.decode(base64Val) : new byte[0]);
            default -> throw new IOException("Unknown Value type: " + type);
        };
    }

    private BatchResult readBatchResult(JsonParser p) throws IOException {
        Map<Integer, StmtResult> stepResults = new HashMap<>();
        Map<Integer, HranaError> stepErrors = new HashMap<>();

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "step_results" -> {
                    if (p.currentToken() == JsonToken.START_ARRAY) {
                        int idx = 0;
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            if (p.currentToken() != JsonToken.VALUE_NULL) {
                                stepResults.put(idx, readStmtResult(p));
                            }
                            idx++;
                        }
                    } else {
                        p.skipChildren();
                    }
                }
                case "step_errors" -> {
                    if (p.currentToken() == JsonToken.START_ARRAY) {
                        int idx = 0;
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            if (p.currentToken() != JsonToken.VALUE_NULL) {
                                stepErrors.put(idx, readError(p));
                            }
                            idx++;
                        }
                    } else {
                        p.skipChildren();
                    }
                }
                default -> p.skipChildren();
            }
        }
        return new BatchResult(stepResults, stepErrors);
    }

    private DescribeResult readDescribeResult(JsonParser p) throws IOException {
        List<DescribeParam> params = new ArrayList<>();
        List<DescribeCol> cols = new ArrayList<>();
        boolean isExplain = false;
        boolean isReadonly = false;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "params" -> {
                    if (p.currentToken() == JsonToken.START_ARRAY) {
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            params.add(readDescribeParam(p));
                        }
                    } else p.skipChildren();
                }
                case "cols" -> {
                    if (p.currentToken() == JsonToken.START_ARRAY) {
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            cols.add(readDescribeCol(p));
                        }
                    } else p.skipChildren();
                }
                case "is_explain" -> isExplain = p.getBooleanValue();
                case "is_readonly" -> isReadonly = p.getBooleanValue();
                default -> p.skipChildren();
            }
        }
        return new DescribeResult(params, cols, isExplain, isReadonly);
    }

    private DescribeParam readDescribeParam(JsonParser p) throws IOException {
        String name = null;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            if ("name".equals(field)) name = p.getText();
            else p.skipChildren();
        }
        return new DescribeParam(name);
    }

    private DescribeCol readDescribeCol(JsonParser p) throws IOException {
        String name = null;
        String decltype = null;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "name" -> name = p.getText();
                case "decltype" -> decltype = p.getText();
                default -> p.skipChildren();
            }
        }
        return new DescribeCol(name, decltype);
    }

    @Override
    public HranaError deserializeError(InputStream in) throws IOException {
        try (JsonParser p = JSON_FACTORY.createParser(in)) {
            p.nextToken();
            return readError(p);
        }
    }

    private HranaError readError(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw new IOException("Expected START_OBJECT for Error");
        }
        String message = "";
        String code = null;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "message" -> message = p.getText();
                case "code" -> code = p.getText();
                default -> p.skipChildren();
            }
        }
        return new HranaError(message, code);
    }
}
