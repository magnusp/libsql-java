package tech.libsql.hrana.codec;

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

/**
 * High-performance, zero-dependency streaming JSON codec purpose-built for Hrana v3 protocol payloads.
 * Allocates no Jackson objects, no intermediate tree nodes, and operates directly with low-overhead scanning.
 */
public class DirectHranaJsonCodec implements HranaJsonCodec {

    private static final Base64.Encoder B64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    @Override
    public byte[] serializePipelineRequest(PipelineReqBody req) throws IOException {
        FastJsonWriter writer = new FastJsonWriter(512);
        writePipelineRequest(writer, req);
        return writer.toByteArray();
    }

    @Override
    public void serializePipelineRequest(PipelineReqBody req, OutputStream out) throws IOException {
        byte[] bytes = serializePipelineRequest(req);
        out.write(bytes);
    }

    private void writePipelineRequest(FastJsonWriter w, PipelineReqBody req) {
        w.writeStartObject();
        if (req.baton() != null) {
            w.writeStringField("baton", req.baton());
        } else {
            w.writeNullField("baton");
        }

        w.writeComma();
        w.writeFieldName("requests");
        w.writeStartArray();
        List<StreamRequest> requests = req.requests();
        for (int i = 0; i < requests.size(); i++) {
            if (i > 0) w.writeComma();
            writeStreamRequest(w, requests.get(i));
        }
        w.writeEndArray();
        w.writeEndObject();
    }

    private void writeStreamRequest(FastJsonWriter w, StreamRequest req) {
        w.writeStartObject();
        switch (req) {
            case StreamRequest.Close c -> w.writeStringField("type", "close");
            case StreamRequest.Execute exec -> {
                w.writeStringField("type", "execute");
                w.writeComma();
                w.writeFieldName("stmt");
                writeStmt(w, exec.stmt());
            }
            case StreamRequest.BatchReq b -> {
                w.writeStringField("type", "batch");
                w.writeComma();
                w.writeFieldName("batch");
                writeBatch(w, b.batch());
            }
            case StreamRequest.Sequence seq -> {
                w.writeStringField("type", "sequence");
                if (seq.sql() != null) {
                    w.writeComma();
                    w.writeStringField("sql", seq.sql());
                }
                if (seq.sqlId() != null) {
                    w.writeComma();
                    w.writeNumberField("sql_id", seq.sqlId());
                }
            }
            case StreamRequest.Describe d -> {
                w.writeStringField("type", "describe");
                if (d.sql() != null) {
                    w.writeComma();
                    w.writeStringField("sql", d.sql());
                }
                if (d.sqlId() != null) {
                    w.writeComma();
                    w.writeNumberField("sql_id", d.sqlId());
                }
            }
            case StreamRequest.StoreSql s -> {
                w.writeStringField("type", "store_sql");
                w.writeComma();
                w.writeNumberField("sql_id", s.sqlId());
                w.writeComma();
                w.writeStringField("sql", s.sql());
            }
            case StreamRequest.CloseSql c -> {
                w.writeStringField("type", "close_sql");
                w.writeComma();
                w.writeNumberField("sql_id", c.sqlId());
            }
            case StreamRequest.GetAutocommit a -> w.writeStringField("type", "get_autocommit");
        }
        w.writeEndObject();
    }

    private void writeStmt(FastJsonWriter w, Stmt stmt) {
        w.writeStartObject();
        boolean hasPrev = false;
        if (stmt.sql() != null) {
            w.writeStringField("sql", stmt.sql());
            hasPrev = true;
        }
        if (stmt.sqlId() != null) {
            if (hasPrev) w.writeComma();
            w.writeNumberField("sql_id", stmt.sqlId());
            hasPrev = true;
        }

        if (stmt.args() != null && !stmt.args().isEmpty()) {
            if (hasPrev) w.writeComma();
            w.writeFieldName("args");
            w.writeStartArray();
            List<Value> args = stmt.args();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) w.writeComma();
                writeValue(w, args.get(i));
            }
            w.writeEndArray();
            hasPrev = true;
        }

        if (stmt.namedArgs() != null && !stmt.namedArgs().isEmpty()) {
            if (hasPrev) w.writeComma();
            w.writeFieldName("named_args");
            w.writeStartArray();
            List<NamedArg> namedArgs = stmt.namedArgs();
            for (int i = 0; i < namedArgs.size(); i++) {
                if (i > 0) w.writeComma();
                NamedArg na = namedArgs.get(i);
                w.writeStartObject();
                w.writeStringField("name", na.name());
                w.writeComma();
                w.writeFieldName("value");
                writeValue(w, na.value());
                w.writeEndObject();
            }
            w.writeEndArray();
            hasPrev = true;
        }

        if (stmt.wantRows() != null) {
            if (hasPrev) w.writeComma();
            w.writeBooleanField("want_rows", stmt.wantRows());
        }
        w.writeEndObject();
    }

    private void writeValue(FastJsonWriter w, Value value) {
        w.writeStartObject();
        switch (value) {
            case Value.Null n -> w.writeStringField("type", "null");
            case Value.Integer i -> {
                w.writeStringField("type", "integer");
                w.writeComma();
                w.writeFieldName("value");
                w.writeString(Long.toString(i.value()));
            }
            case Value.Float f -> {
                w.writeStringField("type", "float");
                w.writeComma();
                w.writeFieldName("value");
                w.writeRaw(Double.toString(f.value()));
            }
            case Value.Text t -> {
                w.writeStringField("type", "text");
                w.writeComma();
                w.writeStringField("value", t.value());
            }
            case Value.Blob b -> {
                w.writeStringField("type", "blob");
                w.writeComma();
                w.writeStringField("base64", B64_ENCODER.encodeToString(b.bytes()));
            }
        }
        w.writeEndObject();
    }

    private void writeBatch(FastJsonWriter w, Batch batch) {
        w.writeStartObject();
        w.writeFieldName("steps");
        w.writeStartArray();
        List<BatchStep> steps = batch.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) w.writeComma();
            BatchStep step = steps.get(i);
            w.writeStartObject();
            if (step.condition() != null) {
                w.writeFieldName("condition");
                writeBatchCond(w, step.condition());
                w.writeComma();
            }
            w.writeFieldName("stmt");
            writeStmt(w, step.stmt());
            w.writeEndObject();
        }
        w.writeEndArray();
        w.writeEndObject();
    }

    private void writeBatchCond(FastJsonWriter w, BatchCond cond) {
        w.writeStartObject();
        switch (cond) {
            case BatchCond.StepOk ok -> {
                w.writeStringField("type", "ok");
                w.writeComma();
                w.writeNumberField("step", ok.step());
            }
            case BatchCond.StepError err -> {
                w.writeStringField("type", "error");
                w.writeComma();
                w.writeNumberField("step", err.step());
            }
            case BatchCond.Not not -> {
                w.writeStringField("type", "not");
                w.writeComma();
                w.writeFieldName("cond");
                writeBatchCond(w, not.cond());
            }
            case BatchCond.And and -> {
                w.writeStringField("type", "and");
                w.writeComma();
                w.writeFieldName("conds");
                w.writeStartArray();
                List<BatchCond> conds = and.conds();
                for (int i = 0; i < conds.size(); i++) {
                    if (i > 0) w.writeComma();
                    writeBatchCond(w, conds.get(i));
                }
                w.writeEndArray();
            }
            case BatchCond.Or or -> {
                w.writeStringField("type", "or");
                w.writeComma();
                w.writeFieldName("conds");
                w.writeStartArray();
                List<BatchCond> conds = or.conds();
                for (int i = 0; i < conds.size(); i++) {
                    if (i > 0) w.writeComma();
                    writeBatchCond(w, conds.get(i));
                }
                w.writeEndArray();
            }
            case BatchCond.IsAutocommit a -> w.writeStringField("type", "is_autocommit");
        }
        w.writeEndObject();
    }

    @Override
    public PipelineRespBody deserializePipelineResponse(byte[] bytes) throws IOException {
        FastJsonParser p = new FastJsonParser(bytes);
        return parsePipelineRespBody(p);
    }

    @Override
    public PipelineRespBody deserializePipelineResponse(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        return deserializePipelineResponse(bytes);
    }

    @Override
    public HranaError deserializeError(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        FastJsonParser p = new FastJsonParser(bytes);
        return parseError(p);
    }

    private PipelineRespBody parsePipelineRespBody(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        String baton = null;
        String baseUrl = null;
        List<StreamResult> results = new ArrayList<>();

        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            switch (field) {
                case "baton" -> baton = p.nextStringOrNull();
                case "base_url" -> baseUrl = p.nextStringOrNull();
                case "results" -> {
                    if (p.peekNull()) {
                        p.nextNull();
                    } else {
                        p.expectArrayStart();
                        while (!p.checkArrayEnd()) {
                            results.add(parseStreamResult(p));
                        }
                    }
                }
                default -> p.skipValue();
            }
        }
        return new PipelineRespBody(baton, baseUrl, results);
    }

    private StreamResult parseStreamResult(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        String type = null;
        StreamResponse response = null;
        HranaError error = null;

        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            switch (field) {
                case "type" -> type = p.nextString();
                case "response" -> response = parseStreamResponse(p);
                case "error" -> error = parseError(p);
                default -> p.skipValue();
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

    private StreamResponse parseStreamResponse(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        String type = null;
        StmtResult stmtResult = null;
        BatchResult batchResult = null;
        DescribeResult describeResult = null;
        boolean isAutocommit = false;

        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            switch (field) {
                case "type" -> type = p.nextString();
                case "result" -> {
                    if ("execute".equals(type)) {
                        stmtResult = parseStmtResult(p);
                    } else if ("batch".equals(type)) {
                        batchResult = parseBatchResult(p);
                    } else if ("describe".equals(type)) {
                        describeResult = parseDescribeResult(p);
                    } else {
                        p.skipValue();
                    }
                }
                case "is_autocommit" -> isAutocommit = p.nextBoolean();
                default -> p.skipValue();
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

    private StmtResult parseStmtResult(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        List<Col> cols = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        long affectedRowCount = 0;
        Long lastInsertRowid = null;

        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            switch (field) {
                case "cols" -> {
                    p.expectArrayStart();
                    while (!p.checkArrayEnd()) {
                        cols.add(parseCol(p));
                    }
                }
                case "rows" -> {
                    p.expectArrayStart();
                    while (!p.checkArrayEnd()) {
                        rows.add(parseRow(p));
                    }
                }
                case "affected_row_count" -> affectedRowCount = p.nextLong();
                case "last_insert_rowid" -> {
                    String s = p.nextStringOrNull();
                    if (s != null) {
                        lastInsertRowid = Long.parseLong(s);
                    }
                }
                default -> p.skipValue();
            }
        }
        return new StmtResult(cols, rows, affectedRowCount, lastInsertRowid);
    }

    private Col parseCol(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        String name = null;
        String decltype = null;
        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            switch (field) {
                case "name" -> name = p.nextStringOrNull();
                case "decltype" -> decltype = p.nextStringOrNull();
                default -> p.skipValue();
            }
        }
        return new Col(name, decltype);
    }

    private Row parseRow(FastJsonParser p) throws IOException {
        p.expectArrayStart();
        List<Value> values = new ArrayList<>();
        while (!p.checkArrayEnd()) {
            values.add(parseValue(p));
        }
        return new Row(values);
    }

    private Value parseValue(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        String type = null;
        String stringVal = null;
        double floatVal = 0.0;
        String base64Val = null;

        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            switch (field) {
                case "type" -> type = p.nextString();
                case "value" -> {
                    if (p.peekString()) {
                        stringVal = p.nextString();
                    } else if (p.peekNumber()) {
                        floatVal = p.nextDouble();
                        stringVal = Double.toString(floatVal);
                    } else if (p.peekNull()) {
                        p.nextNull();
                    } else {
                        p.skipValue();
                    }
                }
                case "base64" -> base64Val = p.nextStringOrNull();
                default -> p.skipValue();
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

    private BatchResult parseBatchResult(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        Map<Integer, StmtResult> stepResults = new HashMap<>();
        Map<Integer, HranaError> stepErrors = new HashMap<>();

        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            switch (field) {
                case "step_results" -> {
                    p.expectArrayStart();
                    int idx = 0;
                    while (!p.checkArrayEnd()) {
                        if (p.peekNull()) {
                            p.nextNull();
                        } else {
                            stepResults.put(idx, parseStmtResult(p));
                        }
                        idx++;
                    }
                }
                case "step_errors" -> {
                    p.expectArrayStart();
                    int idx = 0;
                    while (!p.checkArrayEnd()) {
                        if (p.peekNull()) {
                            p.nextNull();
                        } else {
                            stepErrors.put(idx, parseError(p));
                        }
                        idx++;
                    }
                }
                default -> p.skipValue();
            }
        }
        return new BatchResult(stepResults, stepErrors);
    }

    private DescribeResult parseDescribeResult(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        List<DescribeParam> params = new ArrayList<>();
        List<DescribeCol> cols = new ArrayList<>();
        boolean isExplain = false;
        boolean isReadonly = false;

        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            switch (field) {
                case "params" -> {
                    p.expectArrayStart();
                    while (!p.checkArrayEnd()) {
                        params.add(parseDescribeParam(p));
                    }
                }
                case "cols" -> {
                    p.expectArrayStart();
                    while (!p.checkArrayEnd()) {
                        cols.add(parseDescribeCol(p));
                    }
                }
                case "is_explain" -> isExplain = p.nextBoolean();
                case "is_readonly" -> isReadonly = p.nextBoolean();
                default -> p.skipValue();
            }
        }
        return new DescribeResult(params, cols, isExplain, isReadonly);
    }

    private DescribeParam parseDescribeParam(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        String name = null;
        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            if ("name".equals(field)) {
                name = p.nextStringOrNull();
            } else {
                p.skipValue();
            }
        }
        return new DescribeParam(name);
    }

    private DescribeCol parseDescribeCol(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        String name = null;
        String decltype = null;
        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            switch (field) {
                case "name" -> name = p.nextStringOrNull();
                case "decltype" -> decltype = p.nextStringOrNull();
                default -> p.skipValue();
            }
        }
        return new DescribeCol(name, decltype);
    }

    private HranaError parseError(FastJsonParser p) throws IOException {
        p.expectObjectStart();
        String message = "";
        String code = null;
        while (!p.checkObjectEnd()) {
            String field = p.nextFieldName();
            switch (field) {
                case "message" -> message = p.nextString();
                case "code" -> code = p.nextStringOrNull();
                default -> p.skipValue();
            }
        }
        return new HranaError(message, code);
    }

    // -------------------------------------------------------------
    // Direct Fast JSON Writer
    // -------------------------------------------------------------
    static final class FastJsonWriter {
        private byte[] buf;
        private int count;

        public FastJsonWriter(int initialCapacity) {
            this.buf = new byte[initialCapacity];
            this.count = 0;
        }

        public byte[] toByteArray() {
            byte[] copy = new byte[count];
            System.arraycopy(buf, 0, copy, 0, count);
            return copy;
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity > buf.length) {
                int newCap = Math.max(buf.length << 1, minCapacity);
                byte[] newBuf = new byte[newCap];
                System.arraycopy(buf, 0, newBuf, 0, count);
                buf = newBuf;
            }
        }

        public void writeByte(byte b) {
            ensureCapacity(count + 1);
            buf[count++] = b;
        }

        public void writeRaw(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            ensureCapacity(count + bytes.length);
            System.arraycopy(bytes, 0, buf, count, bytes.length);
            count += bytes.length;
        }

        public void writeStartObject() {
            writeByte((byte) '{');
        }

        public void writeEndObject() {
            writeByte((byte) '}');
        }

        public void writeStartArray() {
            writeByte((byte) '[');
        }

        public void writeEndArray() {
            writeByte((byte) ']');
        }

        public void writeComma() {
            writeByte((byte) ',');
        }

        public void writeColon() {
            writeByte((byte) ':');
        }

        public void writeFieldName(String name) {
            writeString(name);
            writeByte((byte) ':');
        }

        public void writeStringField(String name, String value) {
            writeFieldName(name);
            writeString(value);
        }

        public void writeNumberField(String name, long value) {
            writeFieldName(name);
            writeRaw(Long.toString(value));
        }

        public void writeBooleanField(String name, boolean value) {
            writeFieldName(name);
            writeRaw(value ? "true" : "false");
        }

        public void writeNullField(String name) {
            writeFieldName(name);
            writeRaw("null");
        }

        public void writeString(String s) {
            if (s == null) {
                writeRaw("null");
                return;
            }
            writeByte((byte) '"');
            int len = s.length();
            for (int i = 0; i < len; i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> { writeByte((byte) '\\'); writeByte((byte) '"'); }
                    case '\\' -> { writeByte((byte) '\\'); writeByte((byte) '\\'); }
                    case '\b' -> { writeByte((byte) '\\'); writeByte((byte) 'b'); }
                    case '\f' -> { writeByte((byte) '\\'); writeByte((byte) 'f'); }
                    case '\n' -> { writeByte((byte) '\\'); writeByte((byte) 'n'); }
                    case '\r' -> { writeByte((byte) '\\'); writeByte((byte) 'r'); }
                    case '\t' -> { writeByte((byte) '\\'); writeByte((byte) 't'); }
                    default -> {
                        if (c < 0x20) {
                            writeRaw(String.format("\\u%04x", (int) c));
                        } else if (c < 0x80) {
                            writeByte((byte) c);
                        } else {
                            byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                            ensureCapacity(count + utf8.length);
                            System.arraycopy(utf8, 0, buf, count, utf8.length);
                            count += utf8.length;
                        }
                    }
                }
            }
            writeByte((byte) '"');
        }
    }

    // -------------------------------------------------------------
    // Direct Fast JSON Parser (Zero allocation scanning)
    // -------------------------------------------------------------
    static final class FastJsonParser {
        private final byte[] src;
        private int pos;
        private final int limit;

        public FastJsonParser(byte[] src) {
            this.src = src;
            this.pos = 0;
            this.limit = src.length;
        }

        private void skipWhitespace() {
            while (pos < limit) {
                byte b = src[pos];
                if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        public void expectObjectStart() throws IOException {
            skipWhitespace();
            if (pos >= limit || src[pos] != '{') {
                throw new IOException("Expected '{' at pos " + pos);
            }
            pos++;
        }

        public boolean checkObjectEnd() throws IOException {
            skipWhitespace();
            if (pos >= limit) throw new IOException("Unexpected EOF waiting for '}'");
            if (src[pos] == '}') {
                pos++;
                return true;
            }
            if (src[pos] == ',') {
                pos++;
                skipWhitespace();
            }
            return false;
        }

        public void expectArrayStart() throws IOException {
            skipWhitespace();
            if (pos >= limit || src[pos] != '[') {
                throw new IOException("Expected '[' at pos " + pos);
            }
            pos++;
        }

        public boolean checkArrayEnd() throws IOException {
            skipWhitespace();
            if (pos >= limit) throw new IOException("Unexpected EOF waiting for ']'");
            if (src[pos] == ']') {
                pos++;
                return true;
            }
            if (src[pos] == ',') {
                pos++;
                skipWhitespace();
            }
            return false;
        }

        public String nextFieldName() throws IOException {
            skipWhitespace();
            String name = nextString();
            skipWhitespace();
            if (pos >= limit || src[pos] != ':') {
                throw new IOException("Expected ':' after field name at pos " + pos);
            }
            pos++;
            return name;
        }

        public boolean peekNull() {
            skipWhitespace();
            return pos + 4 <= limit &&
                   src[pos] == 'n' && src[pos + 1] == 'u' && src[pos + 2] == 'l' && src[pos + 3] == 'l';
        }

        public boolean peekString() {
            skipWhitespace();
            return pos < limit && src[pos] == '"';
        }

        public boolean peekNumber() {
            skipWhitespace();
            if (pos >= limit) return false;
            byte b = src[pos];
            return (b >= '0' && b <= '9') || b == '-';
        }

        public void nextNull() throws IOException {
            skipWhitespace();
            if (peekNull()) {
                pos += 4;
            } else {
                throw new IOException("Expected 'null' at pos " + pos);
            }
        }

        public String nextStringOrNull() throws IOException {
            skipWhitespace();
            if (peekNull()) {
                nextNull();
                return null;
            }
            return nextString();
        }

        public String nextString() throws IOException {
            skipWhitespace();
            if (pos >= limit || src[pos] != '"') {
                throw new IOException("Expected '\"' at pos " + pos);
            }
            pos++;
            int start = pos;
            boolean hasEscape = false;
            while (pos < limit && src[pos] != '"') {
                if (src[pos] == '\\') {
                    hasEscape = true;
                    pos += 2; // skip escape
                } else {
                    pos++;
                }
            }
            if (pos >= limit) throw new IOException("Unterminated string starting at " + start);
            int end = pos;
            pos++; // skip closing '"'

            if (!hasEscape) {
                return new String(src, start, end - start, StandardCharsets.UTF_8);
            }

            // Handle escapes
            StringBuilder sb = new StringBuilder(end - start);
            int i = start;
            while (i < end) {
                byte b = src[i];
                if (b == '\\') {
                    i++;
                    byte esc = src[i++];
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            String hex = new String(src, i, 4, StandardCharsets.US_ASCII);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                        default -> sb.append((char) esc);
                    }
                } else {
                    int charLen = utf8ByteLength(b);
                    sb.append(new String(src, i, charLen, StandardCharsets.UTF_8));
                    i += charLen;
                }
            }
            return sb.toString();
        }

        private static int utf8ByteLength(byte b) {
            if ((b & 0x80) == 0) return 1;
            if ((b & 0xE0) == 0xC0) return 2;
            if ((b & 0xF0) == 0xE0) return 3;
            if ((b & 0xF8) == 0xF0) return 4;
            return 1;
        }

        public long nextLong() throws IOException {
            skipWhitespace();
            int start = pos;
            if (pos < limit && (src[pos] == '-' || src[pos] == '+')) {
                pos++;
            }
            while (pos < limit && src[pos] >= '0' && src[pos] <= '9') {
                pos++;
            }
            if (start == pos) throw new IOException("Expected number at pos " + pos);
            String num = new String(src, start, pos - start, StandardCharsets.US_ASCII);
            return Long.parseLong(num);
        }

        public double nextDouble() throws IOException {
            skipWhitespace();
            int start = pos;
            if (pos < limit && (src[pos] == '-' || src[pos] == '+')) {
                pos++;
            }
            while (pos < limit && ((src[pos] >= '0' && src[pos] <= '9') || src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E' || src[pos] == '-' || src[pos] == '+')) {
                pos++;
            }
            if (start == pos) throw new IOException("Expected double at pos " + pos);
            String num = new String(src, start, pos - start, StandardCharsets.US_ASCII);
            return Double.parseDouble(num);
        }

        public boolean nextBoolean() throws IOException {
            skipWhitespace();
            if (pos + 4 <= limit && src[pos] == 't' && src[pos + 1] == 'r' && src[pos + 2] == 'u' && src[pos + 3] == 'e') {
                pos += 4;
                return true;
            }
            if (pos + 5 <= limit && src[pos] == 'f' && src[pos + 1] == 'a' && src[pos + 2] == 'l' && src[pos + 3] == 's' && src[pos + 4] == 'e') {
                pos += 5;
                return false;
            }
            throw new IOException("Expected boolean at pos " + pos);
        }

        public void skipValue() throws IOException {
            skipWhitespace();
            if (pos >= limit) return;
            byte b = src[pos];
            if (b == '{') {
                int depth = 1;
                pos++;
                while (pos < limit && depth > 0) {
                    if (src[pos] == '"') {
                        skipString();
                    } else {
                        if (src[pos] == '{') depth++;
                        else if (src[pos] == '}') depth--;
                        pos++;
                    }
                }
            } else if (b == '[') {
                int depth = 1;
                pos++;
                while (pos < limit && depth > 0) {
                    if (src[pos] == '"') {
                        skipString();
                    } else {
                        if (src[pos] == '[') depth++;
                        else if (src[pos] == ']') depth--;
                        pos++;
                    }
                }
            } else if (b == '"') {
                skipString();
            } else {
                while (pos < limit) {
                    byte c = src[pos];
                    if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        break;
                    }
                    pos++;
                }
            }
        }

        private void skipString() throws IOException {
            pos++;
            while (pos < limit && src[pos] != '"') {
                if (src[pos] == '\\') {
                    pos += 2;
                } else {
                    pos++;
                }
            }
            if (pos < limit) pos++;
        }
    }
}
