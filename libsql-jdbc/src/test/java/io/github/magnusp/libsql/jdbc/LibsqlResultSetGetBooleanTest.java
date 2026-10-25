package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.Row;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LibsqlResultSetGetBooleanTest {

    static Stream<Arguments> storageClasses() {
        return Stream.of(
                Arguments.of("text 'true'", new Value.Text("true"), true),
                Arguments.of("text 'false'", new Value.Text("false"), false),
                Arguments.of("text '0'", new Value.Text("0"), false),
                Arguments.of("text '2'", new Value.Text("2"), true),
                Arguments.of("real 0.5", new Value.Float(0.5), false),
                Arguments.of("real 2.0", new Value.Float(2.0), true),
                Arguments.of(
                        "blob x'313233'",
                        new Value.Blob("123".getBytes(StandardCharsets.UTF_8)),
                        true),
                Arguments.of("null", new Value.Null(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storageClasses")
    void getBooleanConvertsStorageClasses(String name, Value value, boolean expected)
            throws SQLException {
        LibsqlResultSet rs = resultSet(value);
        assertThat(rs.next()).isTrue();

        assertThat(rs.getBoolean(1)).isEqualTo(expected);
    }

    @Test
    void getBooleanParsesTextTrueAndFalse() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Text("true"), new Value.Text("false"));

        assertThat(rs.next()).isTrue();

        assertThat(rs.getBoolean(1)).isTrue();
        assertThat(rs.getBoolean(2)).isFalse();
    }

    @Test
    void textTrueIsTrueAndNumericTextKeepsIntegerCoercion() throws SQLException {
        LibsqlResultSet rs = resultSet(
                new Value.Text("true"),
                new Value.Text("2"),
                new Value.Float(0.5));

        assertThat(rs.next()).isTrue();

        assertThat(rs.getBoolean(1)).isTrue();
        assertThat(rs.getBoolean(2)).isTrue();
        assertThat(rs.getBoolean(3)).isFalse();
    }

    private static LibsqlResultSet resultSet(Value... values) {
        List<Col> cols = Stream.generate(() -> new Col("c", null)).limit(values.length).toList();
        StmtResult result = new StmtResult(cols, List.of(new Row(List.of(values))), 1, null);
        return new LibsqlResultSet(null, result);
    }
}
