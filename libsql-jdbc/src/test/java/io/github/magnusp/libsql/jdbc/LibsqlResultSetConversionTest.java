package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.Row;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlResultSetConversionTest {

    @Test
    void declaredNumericTextRejectsANonNumberWithTheXerialMessage() throws SQLException {
        LibsqlResultSet rs = resultSet("NUMERIC", new Value.Text("abc"));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getInt(1))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type numeric: abc");
    }

    @Test
    void declaredNumericBlobRejectsANonNumberWithTheXerialMessage() throws SQLException {
        LibsqlResultSet rs =
                resultSet("NUMERIC", new Value.Blob("abc".getBytes(StandardCharsets.UTF_8)));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getDouble(1))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type numeric: abc");
    }

    @Test
    void typedIntegerTargetOnTextStorageUsesTheTargetTypeMessage() throws SQLException {
        LibsqlResultSet rs = resultSet("NUMERIC", new Value.Text("abc"));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getObject(1, Integer.class))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type Integer");
    }

    @Test
    void declaredTinyIntRejectsAValueOutsideTheIntRange() throws SQLException {
        LibsqlResultSet rs = resultSet("TINYINT", Value.of(4_000_000_000L));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getObject(1))
                .isInstanceOf(SQLException.class)
                .hasMessage("Value 4000000000 does not fit the declared TINYINT type");
    }

    @Test
    void bigDecimalRejectsNonNumericText() throws SQLException {
        LibsqlResultSet rs = resultSet("DECIMAL", new Value.Text("abc"));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getBigDecimal(1))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type BigDecimal: abc");
    }

    @Test
    void urlRejectsMalformedText() throws SQLException {
        LibsqlResultSet rs = resultSet("DATALINK", new Value.Text("not a url"));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getURL(1))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type URL: not a url");
    }

    @Test
    void offsetTimeRejectsMalformedText() throws SQLException {
        LibsqlResultSet rs = resultSet("TIME_WITH_TIMEZONE", new Value.Text("abc"));
        assertThat(rs.next()).isTrue();

        assertThatThrownBy(() -> rs.getObject(1, OffsetTime.class))
                .isInstanceOf(SQLException.class)
                .hasMessage("Bad value for type OffsetTime: abc");
    }

    @Test
    void offsetDateTimeAcceptsTheSqliteSpaceSeparator() throws SQLException {
        LibsqlResultSet rs =
                resultSet("TIMESTAMP_WITH_TIMEZONE", new Value.Text("2024-01-02 03:04:05+02:00"));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getObject(1, OffsetDateTime.class))
                .isEqualTo(OffsetDateTime.parse("2024-01-02T03:04:05+02:00"));
    }

    private static LibsqlResultSet resultSet(String declaredType, Value value) {
        List<Col> cols = List.of(new Col("c1", declaredType));
        StmtResult result = new StmtResult(cols, List.of(new Row(List.of(value))), 1, null);
        return new LibsqlResultSet(null, result);
    }
}
