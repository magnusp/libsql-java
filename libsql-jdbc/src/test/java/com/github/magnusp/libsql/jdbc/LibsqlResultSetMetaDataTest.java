package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LibsqlResultSetMetaDataTest {

    @Test
    void resolvesTableNameNullabilityAndAutoIncrement() throws SQLException {
        String createSql = "CREATE TABLE People (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)";
        StmtResult tableRows = new StmtResult(
                List.of(),
                List.of(
                        new Row(List.of(
                                Value.of("People"),
                                Value.of(createSql),
                                Value.of("id"),
                                Value.of(0L),
                                Value.of(1L))),
                        new Row(List.of(
                                Value.of("People"),
                                Value.of(createSql),
                                Value.of("name"),
                                Value.of(1L),
                                Value.of(0L)))),
                0,
                null);
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("id", "INTEGER"), new Col("name", "TEXT")),
                List.of(),
                "SELECT id, name FROM main.People",
                connectionReturning(tableRows));

        assertThat(metadata.getTableName(1)).isEqualTo("People");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullable);
        assertThat(metadata.isNullable(2)).isEqualTo(ResultSetMetaData.columnNoNulls);
        assertThat(metadata.isAutoIncrement(1)).isTrue();
        assertThat(metadata.isAutoIncrement(2)).isFalse();
    }

    @Test
    void ignoresAutoIncrementInsideSqlComments() throws SQLException {
        String createSql = "CREATE TABLE People (id INTEGER PRIMARY KEY /* AUTOINCREMENT */, name TEXT)";
        StmtResult tableRows = new StmtResult(
                List.of(),
                List.of(new Row(List.of(
                        Value.of("People"),
                        Value.of(createSql),
                        Value.of("id"),
                        Value.of(0L),
                        Value.of(1L)))),
                0,
                null);
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("id", "INTEGER")),
                List.of(),
                "SELECT id FROM main.People",
                connectionReturning(tableRows));

        assertThat(metadata.isAutoIncrement(1)).isFalse();
    }

    @Test
    void ignoresAutoIncrementInsideStringLiterals() throws SQLException {
        String createSql = "CREATE TABLE People (id INTEGER PRIMARY KEY, note TEXT DEFAULT 'AUTOINCREMENT')";
        StmtResult tableRows = new StmtResult(
                List.of(),
                List.of(new Row(List.of(
                        Value.of("People"),
                        Value.of(createSql),
                        Value.of("id"),
                        Value.of(0L),
                        Value.of(1L)))),
                0,
                null);
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("id", "INTEGER")),
                List.of(),
                "SELECT id FROM main.People",
                connectionReturning(tableRows));

        assertThat(metadata.isAutoIncrement(1)).isFalse();
    }

    @Test
    void doesNotReportAutoIncrementForColumnNamedAutoincrement() throws SQLException {
        String createSql = "CREATE TABLE T (myautoincrement INTEGER PRIMARY KEY)";
        LibsqlResultSetMetaData metadata = metadataForTable(createSql, List.of(new Col("myautoincrement", "INTEGER")));

        assertThat(metadata.isAutoIncrement(1)).isFalse();
    }

    @Test
    void reportsAutoIncrementForTableLevelAutoincrementPrimaryKey() throws SQLException {
        String createSql = "CREATE TABLE T (id INTEGER, PRIMARY KEY (id AUTOINCREMENT))";
        LibsqlResultSetMetaData metadata = metadataForTable(createSql, List.of(new Col("id", "INTEGER")));

        assertThat(metadata.isAutoIncrement(1)).isTrue();
    }

    @Test
    void ignoresQuotedIdentifierNamedAutoincrement() throws SQLException {
        String createSql = "CREATE TABLE T (\"AUTOINCREMENT\" INTEGER PRIMARY KEY)";
        LibsqlResultSetMetaData metadata = metadataForTable(createSql, List.of(new Col("AUTOINCREMENT", "INTEGER")));

        assertThat(metadata.isAutoIncrement(1)).isFalse();
    }

    @Test
    void usesFirstNonNullValueForIntegerTypeAndClass() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("wide", "INTEGER"), new Col("narrow", "INTEGER")),
                List.of(
                        new Row(List.of(Value.ofNull(), Value.ofNull())),
                        new Row(List.of(Value.of(5_000_000_000L), Value.of(42L)))),
                "SELECT 1",
                null);

        assertThat(metadata.getColumnClassName(1)).isEqualTo(Long.class.getName());
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.BIGINT);
        assertThat(metadata.getColumnClassName(2)).isEqualTo(Integer.class.getName());
        assertThat(metadata.getColumnType(2)).isEqualTo(Types.INTEGER);
    }

    @Test
    void usesRuntimeValueMappingForNonIntegerValuesInIntegerColumn() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("value", "INTEGER")),
                List.of(
                        new Row(List.of(Value.ofNull())),
                        new Row(List.of(Value.of("not a number")))),
                "SELECT 1",
                null);

        assertThat(metadata.getColumnClassName(1)).isEqualTo(String.class.getName());
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.VARCHAR);
    }

    @Test
    void usesRuntimeStorageClassForDeclaredTypes() throws SQLException {
        StmtResult result = new StmtResult(
                List.of(
                        new Col("bi", "BIGINT"),
                        new Col("bo", "BOOLEAN"),
                        new Col("num", "NUMERIC"),
                        new Col("dec", "DECIMAL")),
                List.of(new Row(List.of(Value.of(1.5), Value.of("true"), Value.of(7L), Value.of(8L)))),
                0,
                null);
        LibsqlResultSet resultSet = new LibsqlResultSet(null, result);
        ResultSetMetaData metadata = resultSet.getMetaData();

        assertRuntimeTypes(metadata);

        assertThat(resultSet.next()).isTrue();
        assertRuntimeTypes(metadata);
    }

    @Test
    void usesDeclaredTypesWhenRuntimeValueIsNull() throws SQLException {
        StmtResult result = new StmtResult(
                List.of(
                        new Col("bi", "BIGINT"),
                        new Col("bo", "BOOLEAN"),
                        new Col("num", "NUMERIC"),
                        new Col("dec", "DECIMAL")),
                List.of(new Row(List.of(Value.ofNull(), Value.ofNull(), Value.ofNull(), Value.ofNull()))),
                0,
                null);
        LibsqlResultSet resultSet = new LibsqlResultSet(null, result);
        ResultSetMetaData metadata = resultSet.getMetaData();

        assertDeclaredTypes(metadata);

        assertThat(resultSet.next()).isTrue();
        assertDeclaredTypes(metadata);
    }

    @Test
    void usesDeclaredTypesForEmptyResultSet() throws SQLException {
        StmtResult result = new StmtResult(
                List.of(
                        new Col("bi", "BIGINT"),
                        new Col("bo", "BOOLEAN"),
                        new Col("num", "NUMERIC"),
                        new Col("dec", "DECIMAL")),
                List.of(),
                0,
                null);
        LibsqlResultSet resultSet = new LibsqlResultSet(null, result);
        ResultSetMetaData metadata = resultSet.getMetaData();

        assertDeclaredTypes(metadata);
        assertThat(resultSet.next()).isFalse();
        assertDeclaredTypes(metadata);
    }

    @Test
    void reportsObjectClassNameForEmptyResultSet() throws SQLException {
        StmtResult result = new StmtResult(
                List.of(
                        new Col("i", "INTEGER"),
                        new Col("big", "BIGINT"),
                        new Col("txt", "TEXT"),
                        new Col("b", "BLOB"),
                        new Col("flag", "BOOLEAN"),
                        new Col("real", "REAL"),
                        new Col("untyped", null)),
                List.of(),
                0,
                null);
        LibsqlResultSet resultSet = new LibsqlResultSet(null, result);
        ResultSetMetaData metadata = resultSet.getMetaData();

        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            assertThat(metadata.getColumnClassName(column))
                    .as("column %d before the first row", column)
                    .isEqualTo(Object.class.getName());
        }

        assertThat(resultSet.next()).isFalse();

        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            assertThat(metadata.getColumnClassName(column))
                    .as("column %d after the last row", column)
                    .isEqualTo(Object.class.getName());
        }
    }

    @Test
    void reportsCurrentRowClassNameForMixedTypeColumn() throws SQLException {
        StmtResult result = new StmtResult(
                List.of(new Col("x", null)),
                List.of(new Row(List.of(Value.of(1L))), new Row(List.of(Value.of("a")))),
                0,
                null);
        LibsqlResultSet resultSet = new LibsqlResultSet(null, result);
        ResultSetMetaData metadata = resultSet.getMetaData();

        assertThat(metadata.getColumnClassName(1))
                .as("before the first row")
                .isEqualTo(Integer.class.getName());

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnClassName(1))
                .as("on the integer row")
                .isEqualTo(Integer.class.getName());

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnClassName(1))
                .as("on the text row")
                .isEqualTo(String.class.getName());

        assertThat(resultSet.next()).isFalse();
        assertThat(metadata.getColumnClassName(1))
                .as("after the last row")
                .isEqualTo(Object.class.getName());
    }

    @Test
    void reportsClassNameForCurrentRowOutsideIntRange() throws SQLException {
        StmtResult result = new StmtResult(
                List.of(new Col("x", null)),
                List.of(new Row(List.of(Value.of(1L))), new Row(List.of(Value.of(5_000_000_000L)))),
                0,
                null);
        LibsqlResultSet resultSet = new LibsqlResultSet(null, result);
        ResultSetMetaData metadata = resultSet.getMetaData();

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnClassName(1)).isEqualTo(Integer.class.getName());

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnClassName(1)).isEqualTo(Long.class.getName());
    }

    @Test
    void reflectsStorageClassChangesBetweenRows() throws SQLException {
        StmtResult result = new StmtResult(
                List.of(new Col("bi", "BIGINT")),
                List.of(new Row(List.of(Value.of(1.5))), new Row(List.of(Value.of(2L)))),
                0,
                null);
        LibsqlResultSet resultSet = new LibsqlResultSet(null, result);
        ResultSetMetaData metadata = resultSet.getMetaData();

        assertThat(metadata.getColumnType(1)).isEqualTo(Types.FLOAT);

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.FLOAT);

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.BIGINT);

        assertThat(resultSet.next()).isFalse();
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.BIGINT);
    }

    private static void assertRuntimeTypes(ResultSetMetaData metadata) throws SQLException {
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.FLOAT);
        assertThat(metadata.getColumnType(2)).isEqualTo(Types.VARCHAR);
        assertThat(metadata.getColumnType(3)).isEqualTo(Types.INTEGER);
        assertThat(metadata.getColumnType(4)).isEqualTo(Types.INTEGER);
    }

    private static void assertDeclaredTypes(ResultSetMetaData metadata) throws SQLException {
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.BIGINT);
        assertThat(metadata.getColumnType(2)).isEqualTo(Types.BOOLEAN);
        assertThat(metadata.getColumnType(3)).isEqualTo(Types.NUMERIC);
        assertThat(metadata.getColumnType(4)).isEqualTo(Types.DECIMAL);
    }

    @Test
    void leavesAmbiguousTableContextUnknown() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("id", "INTEGER")),
                List.of(),
                "SELECT * FROM a JOIN b ON a.id = b.id",
                connectionReturning(new StmtResult(List.of(), List.of(), 0, null)));

        assertThat(metadata.getTableName(1)).isEmpty();
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullableUnknown);
        assertThat(metadata.isAutoIncrement(1)).isFalse();
    }

    private static LibsqlResultSetMetaData metadataForTable(String createSql, List<Col> cols) throws SQLException {
        List<Row> tableRows = cols.stream()
                .map(col -> new Row(List.of(
                        Value.of("T"),
                        Value.of(createSql),
                        Value.of(col.name()),
                        Value.of(0L),
                        Value.of(1L))))
                .toList();
        StmtResult result = new StmtResult(List.of(), tableRows, 0, null);
        return new LibsqlResultSetMetaData(cols, List.of(), "SELECT * FROM T", connectionReturning(result));
    }

    private static LibsqlConnection connectionReturning(StmtResult result) {
        return new LibsqlConnection(null, null) {
            @Override
            public StmtResult executeStatement(Stmt stmt) throws SQLException {
                return result;
            }
        };
    }
}
