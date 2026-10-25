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
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlResultSetGetObjectTest {

    static Stream<Arguments> numericTargets() {
        return Stream.of(
                Arguments.of(Integer.class, "Integer", 7, 7),
                Arguments.of(Long.class, "Long", 7L, 7L),
                Arguments.of(Float.class, "Float", 7.0F, 7.5F),
                Arguments.of(Double.class, "Double", 7.0, 7.5));
    }

    static Stream<Arguments> nonIntegerOrFloatStorage() {
        return Stream.of(
                Arguments.of("numeric text", new Value.Text("7")),
                Arguments.of("non-numeric text", new Value.Text("abc")),
                Arguments.of("numeric blob", new Value.Blob("123".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("non-numeric blob", new Value.Blob("abc".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("null", new Value.Null()));
    }

    @ParameterizedTest(name = "{1} from INTEGER storage")
    @MethodSource("numericTargets")
    void numericTargetsReadIntegerStorage(
            Class<?> target, String typeName, Object fromInteger, Object fromFloat) throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Integer(7));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getObject(1, target)).isEqualTo(fromInteger);
    }

    @ParameterizedTest(name = "{1} from FLOAT storage")
    @MethodSource("numericTargets")
    void numericTargetsReadFloatStorage(
            Class<?> target, String typeName, Object fromInteger, Object fromFloat) throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Float(7.5));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getObject(1, target)).isEqualTo(fromFloat);
    }

    @ParameterizedTest(name = "{1} rejects {0}")
    @MethodSource("numericTargets")
    void numericTargetsRejectTextBlobAndNullStorage(Class<?> target, String typeName)
            throws SQLException {
        for (Arguments arguments : nonIntegerOrFloatStorage().toList()) {
            Object[] parts = arguments.get();
            Value value = (Value) parts[1];

            LibsqlResultSet rs = resultSet(value);
            assertThat(rs.next()).isTrue();

            assertThatThrownBy(() -> rs.getObject(1, target))
                    .as("%s from %s storage", typeName, parts[0])
                    .isExactlyInstanceOf(SQLException.class)
                    .hasMessage("Bad value for type " + typeName);
        }
    }

    static Stream<Arguments> shortAndByteTargets() {
        return Stream.of(Arguments.of(Short.class, "Short"), Arguments.of(Byte.class, "Byte"));
    }

    @ParameterizedTest(name = "{1} is unsupported")
    @MethodSource("shortAndByteTargets")
    void shortAndByteAreUnsupportedForEveryStorageClass(Class<?> target, String typeName)
            throws SQLException {
        for (Arguments arguments : nonIntegerOrFloatStorage().toList()) {
            Object[] parts = arguments.get();
            Value value = (Value) parts[1];

            LibsqlResultSet rs = resultSet(value);
            assertThat(rs.next()).isTrue();

            assertThatThrownBy(() -> rs.getObject(1, target))
                    .as("%s from %s storage", typeName, parts[0])
                    .isExactlyInstanceOf(SQLFeatureNotSupportedException.class)
                    .hasMessage("not implemented by SQLite JDBC driver");
        }

        for (Value value : List.of(new Value.Integer(7), new Value.Float(7.5))) {
            LibsqlResultSet rs = resultSet(value);
            assertThat(rs.next()).isTrue();

            assertThatThrownBy(() -> rs.getObject(1, target))
                    .as("%s from %s storage", typeName, value.getClass().getSimpleName())
                    .isExactlyInstanceOf(SQLFeatureNotSupportedException.class)
                    .hasMessage("not implemented by SQLite JDBC driver");
        }
    }

    static Stream<Arguments> unsupportedTargets() {
        return Stream.of(
                Arguments.of(Object.class),
                Arguments.of(Number.class),
                Arguments.of(UUID.class));
    }

    @ParameterizedTest(name = "{0} is unsupported")
    @MethodSource("unsupportedTargets")
    void unsupportedTargetTypeUsesXerialMessage(Class<?> target) throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Integer(7));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getObject(1, target))
                .isExactlyInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("not implemented by SQLite JDBC driver");
    }

    @Test
    void nullTargetTypeUsesXerialMessage() throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Integer(7));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getObject(1, (Class<Object>) null))
                .isExactlyInstanceOf(SQLException.class)
                .hasMessage("requested type cannot be null");
    }

    @Test
    void nonNumericTargetsKeepTheirUnrestrictedConversions() throws SQLException {
        LibsqlResultSet integers = resultSet(new Value.Integer(7));
        assertThat(integers.next()).isTrue();
        assertThat(integers.getObject(1, String.class)).isEqualTo("7");
        assertThat(integers.getObject(1, byte[].class))
                .isEqualTo("7".getBytes(StandardCharsets.UTF_8));

        LibsqlResultSet text = resultSet(new Value.Text("abc"));
        assertThat(text.next()).isTrue();
        assertThat(text.getObject(1, String.class)).isEqualTo("abc");
        assertThat(text.getObject(1, Boolean.class)).isFalse();

        LibsqlResultSet nullValue = resultSet(new Value.Null());
        assertThat(nullValue.next()).isTrue();
        assertThat(nullValue.getObject(1, String.class)).isNull();
        assertThat(nullValue.getObject(1, Boolean.class)).isFalse();
        assertThat(nullValue.getObject(1, byte[].class)).isNull();
    }

    private static LibsqlResultSet resultSet(Value value) {
        List<Col> cols = List.of(new Col("c", null));
        StmtResult result = new StmtResult(cols, List.of(new Row(List.of(value))), 1, null);
        return new LibsqlResultSet(null, result);
    }
}
