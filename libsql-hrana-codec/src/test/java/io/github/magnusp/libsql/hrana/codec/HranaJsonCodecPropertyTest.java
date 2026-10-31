package io.github.magnusp.libsql.hrana.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential properties for the two Hrana JSON implementations.
 *
 * <p>Both codecs speak the same protocol. The property is that a generated value produces the same
 * JSON when either codec encodes it, and that both codecs read that JSON back to the same value.
 * The generated domain includes the two precision guarantees from the module guide: every 64-bit
 * integer is sent as a decimal string, and every blob is sent as base64.
 */
class HranaJsonCodecPropertyTest {

    private static final int ITERATIONS = 1_000;

    private final DirectHranaJsonCodec direct = new DirectHranaJsonCodec();
    private final JacksonHranaJsonCodec jackson = new JacksonHranaJsonCodec();

    @Test
    void bothCodecsEncodeTheSameValue() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(describedValues(), described -> {
                    String directJson = canonicalUnchecked(encodeUnchecked(direct, described.value()));
                    String jacksonJson = canonicalUnchecked(encodeUnchecked(jackson, described.value()));
                    if (!directJson.equals(jacksonJson)) {
                        throw new AssertionError(
                                "direct=" + escape(directJson) + " jackson=" + escape(jacksonJson));
                    }
                    return true;
                });
    }

    @Test
    void bothCodecsDecodeTheSameResponse() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(describedValues(), described -> {
                    byte[] json = responseUnchecked(described.value());
                    PipelineRespBody directResponse = decodeUnchecked(direct, json);
                    PipelineRespBody jacksonResponse = decodeUnchecked(jackson, json);

                    assertThat(jacksonResponse).isEqualTo(directResponse);
                    assertThat(firstValue(directResponse)).isEqualTo(described.value());
                    return true;
                });
    }

    @Test
    void integersKeepAllSixtyFourBitsInText() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(longs(), value -> {
                    String expected = "\"type\":\"integer\",\"value\":\"" + value + "\"";
                    assertThat(text(encodeUnchecked(direct, Value.of(value)))).contains(expected);
                    assertThat(text(encodeUnchecked(jackson, Value.of(value)))).contains(expected);
                    return true;
                });
    }

    @Test
    void blobsKeepTheirBytesInBase64() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(describedBlobs(), blob -> {
                    String expected = "\"type\":\"blob\",\"base64\":\""
                            + Base64.getEncoder().encodeToString(blob.bytes()) + "\"";
                    assertThat(text(encodeUnchecked(direct, Value.of(blob.bytes())))).contains(expected);
                    assertThat(text(encodeUnchecked(jackson, Value.of(blob.bytes())))).contains(expected);
                    return true;
                });
    }

    private record DescribedValue(String description, Value value) {
        @Override
        public String toString() {
            return description;
        }
    }

    private record DescribedBlob(byte[] bytes) {
        @Override
        public String toString() {
            return "blob " + HexFormat.of().formatHex(bytes);
        }
    }

    private static Generator<DescribedValue> describedValues() {
        return values().map(value -> new DescribedValue(describe(value), value));
    }

    private static Generator<Value> values() {
        return Generator.anyOf(
                Generator.constant(Value.ofNull()),
                longs().map(value -> Value.of(value.longValue())),
                finiteDoubles().map(value -> Value.of(value.doubleValue())),
                texts().map(Value::of),
                describedBlobs().map(blob -> Value.of(blob.bytes())));
    }

    /** jetCheck has no built-in 64-bit generator, so compose one from two 32-bit draws. */
    private static Generator<Long> longs() {
        return Generator.zipWith(
                Generator.integers(),
                Generator.integers(),
                (high, low) -> ((long) high << 32) | (low & 0xffffffffL));
    }

    private static Generator<Double> finiteDoubles() {
        return Generator.doubles().suchThat(Double::isFinite);
    }

    private static Generator<String> texts() {
        return Generator.anyOf(
                Generator.stringsOf(
                        IntDistribution.uniform(0, 40), Generator.charsInRange((char) 0, (char) 127)),
                // Exclude the surrogate range: jetCheck shrinks strings at char boundaries, which
                // can split a supplementary pair into a lone surrogate that is not well-formed
                // Unicode. Supplementary text is covered by the constants below.
                Generator.stringsOf(
                        IntDistribution.uniform(0, 20), Generator.charsInRange((char) 0x20, (char) 0xD7FF)),
                Generator.stringsOf(
                        IntDistribution.uniform(0, 20), Generator.charsInRange((char) 0xE000, (char) 0xFFFF)),
                Generator.constant("\u0000quoted \" text\n\t"),
                Generator.constant("\uD840\uDC40 supplementary"),
                Generator.constant("emoji \uD83D\uDE00"));
    }

    private static Generator<DescribedBlob> describedBlobs() {
        return Generator.listsOf(IntDistribution.uniform(0, 32), Generator.integers(0, 255))
                .map(HranaJsonCodecPropertyTest::toBytes)
                .map(DescribedBlob::new);
    }

    private static byte[] toBytes(List<Integer> values) {
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) {
            bytes[i] = (byte) (int) values.get(i);
        }
        return bytes;
    }

    private static String describe(Value value) {
        return switch (value) {
            case Value.Null n -> "null";
            case Value.Integer i -> "integer " + i.value();
            case Value.Float f -> "float " + f.value();
            case Value.Text t -> "text \"" + escape(t.value()) + "\"";
            case Value.Blob b -> "blob " + HexFormat.of().formatHex(b.bytes());
        };
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c < 0x7f && c != '\\') {
                escaped.append(c);
            } else {
                escaped.append(String.format("\\u%04x", (int) c));
            }
        }
        return escaped.toString();
    }

    private static PipelineRespBody decodeUnchecked(HranaJsonCodec codec, byte[] json) {
        try {
            return codec.deserializePipelineResponse(json);
        } catch (IOException e) {
            throw new AssertionError("could not decode " + text(json), e);
        }
    }

    private static byte[] encodeUnchecked(HranaJsonCodec codec, Value value) {
        try {
            return encode(codec, value);
        } catch (IOException e) {
            throw new AssertionError("could not encode " + value, e);
        }
    }

    private static String canonicalUnchecked(byte[] json) {
        try {
            return canonical(json);
        } catch (IOException e) {
            throw new AssertionError("could not read back " + text(json), e);
        }
    }

    private static byte[] responseUnchecked(Value value) {
        try {
            return response(value);
        } catch (IOException e) {
            throw new AssertionError("could not write a response for " + value, e);
        }
    }

    private static byte[] encode(HranaJsonCodec codec, Value value) throws IOException {
        PipelineReqBody request = new PipelineReqBody(
                null, List.of(new StreamRequest.Execute(new Stmt("select ?", List.of(value)))));
        return codec.serializePipelineRequest(request);
    }

    private static String text(byte[] json) {
        return new String(json, StandardCharsets.UTF_8);
    }

    /**
     * Reduces JSON to a token stream, so formatting and string-escaping differences between the
     * two writers do not appear as protocol differences. Field order still matters.
     */
    private static String canonical(byte[] json) throws IOException {
        StringBuilder canonical = new StringBuilder();
        try (JsonParser parser = new JsonFactory().createParser(json)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                canonical.append(token.name()).append('\u0000');
                if (token == JsonToken.FIELD_NAME
                        || token == JsonToken.VALUE_STRING
                        || token == JsonToken.VALUE_NUMBER_INT
                        || token == JsonToken.VALUE_NUMBER_FLOAT) {
                    canonical.append(parser.getText());
                }
                canonical.append('\n');
            }
        }
        return canonical.toString();
    }

    private static byte[] response(Value value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator json = new JsonFactory().createGenerator(out)) {
            json.writeStartObject();
            json.writeArrayFieldStart("results");
            json.writeStartObject();
            json.writeStringField("type", "ok");
            json.writeObjectFieldStart("response");
            json.writeStringField("type", "execute");
            json.writeObjectFieldStart("result");
            json.writeArrayFieldStart("cols");
            json.writeStartObject();
            json.writeStringField("name", "v");
            json.writeStringField("decltype", "TEXT");
            json.writeEndObject();
            json.writeEndArray();
            json.writeArrayFieldStart("rows");
            json.writeStartArray();
            writeValue(json, value);
            json.writeEndArray();
            json.writeEndArray();
            json.writeNumberField("affected_row_count", 0);
            json.writeStringField("last_insert_rowid", "0");
            json.writeEndObject();
            json.writeEndObject();
            json.writeEndObject();
            json.writeEndArray();
            json.writeEndObject();
        }
        return out.toByteArray();
    }

    private static void writeValue(JsonGenerator json, Value value) throws IOException {
        json.writeStartObject();
        switch (value) {
            case Value.Null n -> json.writeStringField("type", "null");
            case Value.Integer i -> {
                json.writeStringField("type", "integer");
                json.writeStringField("value", Long.toString(i.value()));
            }
            case Value.Float f -> {
                json.writeStringField("type", "float");
                json.writeNumberField("value", f.value());
            }
            case Value.Text t -> {
                json.writeStringField("type", "text");
                json.writeStringField("value", t.value());
            }
            case Value.Blob b -> {
                json.writeStringField("type", "blob");
                json.writeStringField("base64", Base64.getEncoder().encodeToString(b.bytes()));
            }
        }
        json.writeEndObject();
    }

    private static Value firstValue(PipelineRespBody response) {
        StreamResult.Ok ok = (StreamResult.Ok) response.results().get(0);
        StreamResponse.Execute execute = (StreamResponse.Execute) ok.response();
        return execute.result().rows().get(0).values().get(0);
    }
}
