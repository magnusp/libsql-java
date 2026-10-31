package io.github.magnusp.libsql.perf;

import io.github.magnusp.libsql.hrana.codec.PipelineReqBody;
import io.github.magnusp.libsql.hrana.codec.Stmt;
import io.github.magnusp.libsql.hrana.codec.StreamRequest;
import io.github.magnusp.libsql.hrana.codec.Value;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Builds the fixed payloads the benchmark measures.
 *
 * <p>The tracked payload is deliberately plain ASCII without JSON escapes so that changes in the
 * numbers come from the codec, not from a shifting test document. Codec correctness for escapes and
 * non-ASCII text is covered by the unit and property tests.
 */
final class Payloads {

    private static final String NOTE =
            "lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor";
    private static final String BLOB = Base64.getEncoder().encodeToString(new byte[16]);
    private static final String[] NAMES = {"id", "name", "score", "payload", "note", "flag"};
    private static final String[] DECL_TYPES = {"INTEGER", "TEXT", "REAL", "BLOB", "TEXT", "INTEGER"};

    /** A Hrana {@code execute} response with six columns and the requested number of rows. */
    static byte[] scan(int rows) {
        StringBuilder sb = new StringBuilder(rows * 300 + 512);
        sb.append("{\"baton\":\"baton-token\",\"base_url\":null,\"results\":[{\"type\":\"ok\",")
                .append("\"response\":{\"type\":\"execute\",\"result\":{\"cols\":[");
        for (int c = 0; c < NAMES.length; c++) {
            if (c > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(NAMES[c]).append("\",\"decltype\":\"").append(DECL_TYPES[c]).append("\"}");
        }
        sb.append("],\"rows\":[");
        for (int r = 0; r < rows; r++) {
            if (r > 0) {
                sb.append(',');
            }
            sb.append("[{\"type\":\"integer\",\"value\":\"").append(r).append("\"},")
                    .append("{\"type\":\"text\",\"value\":\"user-").append(r).append("-name\"},")
                    .append("{\"type\":\"float\",\"value\":").append(r).append(".25},")
                    .append("{\"type\":\"blob\",\"base64\":\"").append(BLOB).append("\"},")
                    .append("{\"type\":\"text\",\"value\":\"").append(NOTE).append("\"},")
                    .append("{\"type\":\"").append(r % 2 == 0 ? "integer\",\"value\":\"1" : "null").append("\"}]");
        }
        sb.append("],\"affected_row_count\":0,\"last_insert_rowid\":null}}}]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A pipeline with one parameterized insert, the shape every prepared execute sends. */
    static PipelineReqBody insertRequest() {
        List<Value> args = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            args.add(switch (i % 3) {
                case 0 -> Value.of(123456789L);
                case 1 -> Value.of("some text value, with a comma and a \"quote\"");
                default -> Value.of(12.5);
            });
        }
        Stmt stmt = new Stmt(
                "INSERT INTO users (a, b, c, d, e, f) VALUES (?, ?, ?, ?, ?, ?)", null, args, List.of(), false);
        return new PipelineReqBody("baton-token", List.of(new StreamRequest.Execute(stmt)));
    }

    private Payloads() {}
}
