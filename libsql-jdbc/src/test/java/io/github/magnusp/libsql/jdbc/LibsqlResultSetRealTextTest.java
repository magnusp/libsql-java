package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.Row;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LibsqlResultSetRealTextTest {

    @Test
    void realTextMatchesSqliteCanonicalForm() throws SQLException {
        LibsqlResultSet rs = resultSet(
                new Value.Float(0.0001),
                new Value.Float(10_000_000.0),
                new Value.Float(1e20));

        assertThat(rs.next()).isTrue();

        assertThat(rs.getString(1)).isEqualTo("0.0001");
        assertThat(rs.getString(2)).isEqualTo("10000000.0");
        assertThat(rs.getString(3)).isEqualTo("1.0e+20");
    }

    @Test
    void realBigDecimalScaleMatchesSqliteCanonicalForm() throws SQLException {
        LibsqlResultSet rs = resultSet(
                new Value.Float(0.0001),
                new Value.Float(10_000_000.0),
                new Value.Float(1e20));

        assertThat(rs.next()).isTrue();

        assertThat(rs.getBigDecimal(1)).isEqualTo(new BigDecimal("0.0001"));
        assertThat(rs.getBigDecimal(1).scale()).isEqualTo(4);
        assertThat(rs.getBigDecimal(2)).isEqualTo(new BigDecimal("10000000.0"));
        assertThat(rs.getBigDecimal(2).scale()).isEqualTo(1);
        assertThat(rs.getBigDecimal(3)).isEqualTo(new BigDecimal("1.0e+20"));
        assertThat(rs.getBigDecimal(3).scale()).isEqualTo(-19);
    }

    @Test
    void realBigDecimalEqualsIsStableAcrossRepeatedAndRowReads() throws SQLException {
        Value value = new Value.Float(1e20);
        StmtResult result = new StmtResult(
                List.of(new Col("c", null)),
                List.of(new Row(List.of(value)), new Row(List.of(value))),
                1,
                null);
        LibsqlResultSet rs = new LibsqlResultSet(null, result);

        assertThat(rs.next()).isTrue();
        BigDecimal first = rs.getBigDecimal(1);
        BigDecimal repeated = rs.getBigDecimal(1);
        assertThat(rs.next()).isTrue();
        BigDecimal secondRow = rs.getBigDecimal(1);

        assertThat(repeated).isEqualTo(first);
        assertThat(secondRow).isEqualTo(first);
        assertThat(first.toString()).isEqualTo("1.0E+20");
    }

    static Stream<Arguments> canonicalText() {
        return Stream.of(
                Arguments.of("0.0001", "0.0001"),
                Arguments.of("10000000.0", "10000000.0"),
                Arguments.of("1e20", "1.0e+20"),
                Arguments.of("-0.0001", "-0.0001"),
                Arguments.of("-10000000.0", "-10000000.0"),
                Arguments.of("-1e20", "-1.0e+20"),
                Arguments.of("0.0", "0.0"),
                Arguments.of("1e16", "10000000000000000.0"),
                Arguments.of("1e17", "1.0e+17"),
                Arguments.of("1e-4", "0.0001"),
                Arguments.of("1e-5", "1.0e-05"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("canonicalText")
    void realTextUsesFixedNotationBetweenNegativeFourAndSixteen(String sqliteValue, String expected)
            throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Float(Double.parseDouble(sqliteValue)));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getString(1)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("canonicalText")
    void realBytesMatchCanonicalText(String sqliteValue, String expected) throws SQLException {
        LibsqlResultSet rs = resultSet(new Value.Float(Double.parseDouble(sqliteValue)));
        assertThat(rs.next()).isTrue();

        assertThat(rs.getBytes(1)).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(rs.getBytes(1))
                .isEqualTo(rs.getString(1).getBytes(StandardCharsets.UTF_8));
    }

    private static LibsqlResultSet resultSet(Value... values) {
        List<Col> cols = Stream.generate(() -> new Col("c", null)).limit(values.length).toList();
        StmtResult result = new StmtResult(cols, List.of(new Row(List.of(values))), 1, null);
        return new LibsqlResultSet(null, result);
    }
}
