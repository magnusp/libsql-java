package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.Row;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlResultSetNavigationTest {

    private static final StmtResult DUPLICATE_LABELS = new StmtResult(
            List.of(new Col("a", null), new Col("a", null), new Col("n", null)),
            List.of(new Row(List.of(Value.of(1L), Value.of(2L), Value.ofNull()))),
            1,
            null);

    private static final StmtResult TWO_ROWS = new StmtResult(
            List.of(new Col("value", null)),
            List.of(new Row(List.of(Value.of(1L))), new Row(List.of(Value.of(2L)))),
            2,
            null);

    private static final StmtResult NO_ROWS = new StmtResult(
            List.of(new Col("value", null)),
            List.of(),
            0,
            null);

    @Test
    void findColumnReturnsTheFirstMatchingDuplicateLabel() throws SQLException {
        LibsqlResultSet resultSet = resultSet(DUPLICATE_LABELS);

        assertThat(resultSet.findColumn("a")).isEqualTo(1);
        assertThat(resultSet.findColumn("A")).isEqualTo(1);
    }

    @Test
    void findColumnRejectsUnknownLabelsWithXerialMessage() throws SQLException {
        LibsqlResultSet resultSet = resultSet(DUPLICATE_LABELS);

        assertThatThrownBy(() -> resultSet.findColumn("missing"))
                .isInstanceOf(SQLException.class)
                .hasMessage("no such column: 'missing'");
    }

    @Test
    void gettersReadTheFirstRowBeforeNext() throws SQLException {
        LibsqlResultSet resultSet = resultSet(DUPLICATE_LABELS);

        assertThat(resultSet.getInt(1)).isEqualTo(1);
        assertThat(resultSet.getString(1)).isEqualTo("1");
        assertThat(resultSet.getObject(2)).isEqualTo(2);
    }

    @Test
    void gettersReturnNullAndZeroAfterTheLastRow() throws SQLException {
        LibsqlResultSet resultSet = resultSet(DUPLICATE_LABELS);
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.next()).isFalse();

        assertThat(resultSet.getInt(1)).isZero();
        assertThat(resultSet.getString(1)).isNull();
        assertThat(resultSet.getObject(1)).isNull();
        assertThat(resultSet.getBytes(1)).isNull();
        assertThat(resultSet.getBoolean(1)).isFalse();
    }

    @Test
    void gettersReturnNullAndZeroWhenTheResultSetHasNoRows() throws SQLException {
        LibsqlResultSet resultSet = resultSet(NO_ROWS);

        assertThat(resultSet.getInt(1)).isZero();
        assertThat(resultSet.getString(1)).isNull();
        assertThat(resultSet.getObject(1)).isNull();
    }

    @Test
    void getRowIsZeroBeforeTheFirstRowAndStaysOnTheLastRowAfterTheEnd() throws SQLException {
        LibsqlResultSet resultSet = resultSet(TWO_ROWS);

        assertThat(resultSet.getRow()).isZero();
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getRow()).isEqualTo(1);
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getRow()).isEqualTo(2);
        assertThat(resultSet.next()).isFalse();
        assertThat(resultSet.getRow()).isEqualTo(2);
        assertThat(resultSet.next()).isFalse();
        assertThat(resultSet.getRow()).isEqualTo(2);
    }

    @Test
    void getRowIsZeroForAnEmptyResultSet() throws SQLException {
        LibsqlResultSet resultSet = resultSet(NO_ROWS);

        assertThat(resultSet.getRow()).isZero();
        assertThat(resultSet.next()).isFalse();
        assertThat(resultSet.getRow()).isZero();
    }

    @Test
    void isLastAlwaysThrowsLikeXerial() throws SQLException {
        LibsqlResultSet resultSet = resultSet(DUPLICATE_LABELS);

        assertThatThrownBy(resultSet::isLast)
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("not supported by sqlite");
        assertThat(resultSet.next()).isTrue();
        assertThatThrownBy(resultSet::isLast)
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("not supported by sqlite");
    }

    @Test
    void isFirstIsTrueOnlyOnTheFirstRow() throws SQLException {
        LibsqlResultSet resultSet = resultSet(TWO_ROWS);

        assertThat(resultSet.isFirst()).isFalse();
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.isFirst()).isTrue();
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.isFirst()).isFalse();
        assertThat(resultSet.next()).isFalse();
        assertThat(resultSet.isFirst()).isFalse();
        assertThat(resultSet.next()).isFalse();
        assertThat(resultSet.isFirst()).isFalse();
    }

    @Test
    void isFirstIsTrueOnTheSingleRowAndFalseAfterTheEnd() throws SQLException {
        LibsqlResultSet resultSet = resultSet(DUPLICATE_LABELS);

        assertThat(resultSet.isFirst()).isFalse();
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.isFirst()).isTrue();
        assertThat(resultSet.next()).isFalse();
        assertThat(resultSet.isFirst()).isFalse();
    }

    @Test
    void isFirstIsFalseForAnEmptyResultSet() throws SQLException {
        LibsqlResultSet resultSet = resultSet(NO_ROWS);

        assertThat(resultSet.isFirst()).isFalse();
        assertThat(resultSet.next()).isFalse();
        assertThat(resultSet.isFirst()).isFalse();
    }

    @Test
    void wasNullThrowsBeforeAnyGetterAndAfterTheEndWithoutAGetter() throws SQLException {
        LibsqlResultSet beforeFirst = resultSet(DUPLICATE_LABELS);
        assertThatThrownBy(beforeFirst::wasNull)
                .isInstanceOf(SQLException.class)
                .hasMessage("column 0 out of bounds [1,3]");

        LibsqlResultSet afterLast = resultSet(DUPLICATE_LABELS);
        assertThat(afterLast.next()).isTrue();
        assertThat(afterLast.next()).isFalse();
        assertThatThrownBy(afterLast::wasNull)
                .isInstanceOf(SQLException.class)
                .hasMessage("column -1 out of bounds [1,3]");
    }

    @Test
    void wasNullTracksTheLastGetterRead() throws SQLException {
        LibsqlResultSet resultSet = resultSet(DUPLICATE_LABELS);

        assertThat(resultSet.getInt(1)).isEqualTo(1);
        assertThat(resultSet.wasNull()).isFalse();

        assertThat(resultSet.getObject(3)).isNull();
        assertThat(resultSet.wasNull()).isTrue();

        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.next()).isFalse();
        assertThat(resultSet.getObject(1)).isNull();
        assertThat(resultSet.wasNull()).isTrue();
    }

    private static LibsqlResultSet resultSet(StmtResult result) {
        return new LibsqlResultSet(null, result);
    }
}
