package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.Blob;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void resolvesAttachedTableMetadataThroughPragmaDatabaseList() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("id", "INTEGER"), new Col("name", "TEXT")),
                List.of(),
                "SELECT id, name FROM aux.People",
                connectionForAttachedSchema("main", "temp", "Aux"));

        assertThat(metadata.getTableName(1)).isEqualTo("People");
        assertThat(metadata.getTableName(2)).isEqualTo("People");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNoNulls);
        assertThat(metadata.isNullable(2)).isEqualTo(ResultSetMetaData.columnNoNulls);
        assertThat(metadata.isAutoIncrement(1)).isTrue();
        assertThat(metadata.isAutoIncrement(2)).isFalse();
    }

    @Test
    void reportsNoTableMetadataForASchemaMissingFromPragmaDatabaseList() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("id", "INTEGER")),
                List.of(),
                "SELECT id FROM ghost.People",
                connectionForAttachedSchema("main", "temp", "Aux"));

        assertThat(metadata.getTableName(1)).isEmpty();
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullableUnknown);
    }

    @Test
    void resolvesNonAsciiTableName() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("namn_ö", "TEXT")),
                List.of(),
                "SELECT namn_ö FROM användare",
                connectionForSwedishTables());

        assertThat(metadata.getTableName(1)).isEqualTo("användare");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullable);
    }

    @Test
    void prefersNonAsciiTableOverItsAsciiPrefix() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("namn_ö", "TEXT")),
                List.of(),
                "SELECT namn_ö FROM användare",
                connectionForSwedishTables());

        assertThat(metadata.getTableName(1)).isEqualTo("användare");
        assertThat(metadata.getColumnName(1)).isEqualTo("namn_ö");
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
    void usesDeclaredIntegerTypeForEveryValueMagnitude() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("wide", "INTEGER"), new Col("narrow", "INTEGER")),
                List.of(
                        new Row(List.of(Value.ofNull(), Value.ofNull())),
                        new Row(List.of(Value.of(5_000_000_000L), Value.of(42L)))),
                "SELECT 1",
                null);

        assertThat(metadata.getColumnClassName(1)).isEqualTo(Integer.class.getName());
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.INTEGER);
        assertThat(metadata.getColumnClassName(2)).isEqualTo(Integer.class.getName());
        assertThat(metadata.getColumnType(2)).isEqualTo(Types.INTEGER);
    }

    @Test
    void usesDeclaredTypeForNonNumericValuesInIntegerColumn() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("value", "INTEGER")),
                List.of(
                        new Row(List.of(Value.ofNull())),
                        new Row(List.of(Value.of("not a number")))),
                "SELECT 1",
                null);

        assertThat(metadata.getColumnClassName(1)).isEqualTo(Integer.class.getName());
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.INTEGER);
    }

    @Test
    void usesDeclaredTypesInsteadOfRuntimeStorageClass() throws SQLException {
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

        assertDeclaredTypes(metadata);

        assertThat(resultSet.next()).isTrue();
        assertDeclaredTypes(metadata);
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
    void isSignedMatchesXerialNarrowTypeNameSet() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(
                        new Col("bi", "BIGINT"),
                        new Col("sm", "SMALLINT"),
                        new Col("dec", "DECIMAL(10,5)"),
                        new Col("f", "FLOAT"),
                        new Col("d", "DOUBLE"),
                        new Col("num", "NUMERIC"),
                        new Col("i", "INTEGER"),
                        new Col("r", "REAL")),
                List.of(),
                "SELECT 1",
                null);

        assertThat(metadata.isSigned(1)).as("BIGINT").isFalse();
        assertThat(metadata.isSigned(2)).as("SMALLINT").isFalse();
        assertThat(metadata.isSigned(3)).as("DECIMAL(10,5)").isFalse();
        assertThat(metadata.isSigned(4)).as("FLOAT").isFalse();
        assertThat(metadata.isSigned(5)).as("DOUBLE").isFalse();
        assertThat(metadata.isSigned(6)).as("NUMERIC").isTrue();
        assertThat(metadata.isSigned(7)).as("INTEGER").isTrue();
        assertThat(metadata.isSigned(8)).as("REAL").isTrue();
    }

    @Test
    void matchesCastOnlyInLowerCase() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("CAST(1 AS BOOLEAN)", null), new Col("cast(1 as BOOLEAN)", null)),
                List.of(new Row(List.of(Value.of(1L), Value.of(1L)))),
                "SELECT CAST(1 AS BOOLEAN), cast(1 as BOOLEAN)",
                null);

        assertThat(metadata.getColumnTypeName(1)).as("uppercase CAST").isEqualTo("NUMERIC");
        assertThat(metadata.getColumnType(1)).as("uppercase CAST").isEqualTo(Types.INTEGER);
        assertThat(metadata.isSigned(1)).as("uppercase CAST").isTrue();

        assertThat(metadata.getColumnTypeName(2)).as("lowercase cast").isEqualTo("BOOLEAN");
        assertThat(metadata.getColumnType(2)).as("lowercase cast").isEqualTo(Types.BOOLEAN);
        assertThat(metadata.isSigned(2)).as("lowercase cast").isFalse();
    }

    @Test
    void preservesWhitespaceInDeclaredTypeNames() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("c", "BIG INT"), new Col("g", "  NUMERIC  ")),
                List.of(new Row(List.of(Value.of(1L), Value.of(1L)))),
                "SELECT c, g FROM q",
                null);

        assertThat(metadata.getColumnTypeName(1)).isEqualTo("BIG INT");
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.INTEGER);
        assertThat(metadata.isSigned(1)).isFalse();

        assertThat(metadata.getColumnTypeName(2)).isEqualTo("  NUMERIC  ");
        assertThat(metadata.getColumnType(2)).isEqualTo(Types.INTEGER);
        assertThat(metadata.isSigned(2)).isFalse();
    }

    @Test
    void throwsNumberFormatExceptionForMalformedPrecision() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("e", "DECIMAL(abc)")), List.of(), "SELECT e FROM q", null);

        assertThat(metadata.getColumnTypeName(1)).isEqualTo("DECIMAL");
        assertThatThrownBy(() -> metadata.getPrecision(1))
                .isInstanceOf(NumberFormatException.class)
                .hasMessageContaining("abc");
        assertThat(metadata.getScale(1)).isZero();
    }

    @Test
    void reportsPrecisionAndScaleFromParenthesizedType() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("d", "DECIMAL(10,5)")), List.of(), "SELECT d FROM m", null);

        assertThat(metadata.getPrecision(1)).isEqualTo(10);
        assertThat(metadata.getScale(1)).isEqualTo(5);
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
        List<String> expected = List.of(
                Integer.class.getName(),
                Long.class.getName(),
                Object.class.getName(),
                Blob.class.getName(),
                Boolean.class.getName(),
                Float.class.getName(),
                Object.class.getName());

        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            assertThat(metadata.getColumnClassName(column))
                    .as("column %d before the first row", column)
                    .isEqualTo(expected.get(column - 1));
        }

        assertThat(resultSet.next()).isFalse();

        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            assertThat(metadata.getColumnClassName(column))
                    .as("column %d after the last row", column)
                    .isEqualTo(expected.get(column - 1));
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
    void reportsUnknownTypeNameForExpressionColumnAtEveryCursorPosition() throws SQLException {
        StmtResult result = new StmtResult(
                List.of(new Col("x", null)), List.of(new Row(List.of(Value.of(1L)))), 0, null);
        LibsqlResultSet resultSet = new LibsqlResultSet(null, result);
        ResultSetMetaData metadata = resultSet.getMetaData();

        assertThat(metadata.getColumnTypeName(1))
                .as("before the first row")
                .isEqualTo("NUMERIC");

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnTypeName(1)).as("on a row").isEqualTo("NUMERIC");

        assertThat(resultSet.next()).isFalse();
        assertThat(metadata.getColumnTypeName(1))
                .as("after the last row")
                .isEqualTo("NUMERIC");
    }

    @Test
    void reportsUnknownTypeNameForMixedTypeColumn() throws SQLException {
        StmtResult result = new StmtResult(
                List.of(new Col("x", null)),
                List.of(new Row(List.of(Value.of(1L))), new Row(List.of(Value.of("a")))),
                0,
                null);
        LibsqlResultSet resultSet = new LibsqlResultSet(null, result);
        ResultSetMetaData metadata = resultSet.getMetaData();

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnTypeName(1))
                .as("on the integer row")
                .isEqualTo("NUMERIC");
        assertThat(metadata.isSigned(1)).as("on the integer row").isTrue();

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnTypeName(1))
                .as("on the text row")
                .isEqualTo("NUMERIC");
        assertThat(metadata.isSigned(1)).as("on the text row").isFalse();

        assertThat(resultSet.next()).isFalse();
        assertThat(metadata.getColumnTypeName(1))
                .as("after the last row")
                .isEqualTo("NUMERIC");
        assertThat(metadata.isSigned(1)).as("after the last row").isTrue();
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
    void keepsDeclaredBigintTypeAcrossStorageClassChanges() throws SQLException {
        StmtResult result = new StmtResult(
                List.of(new Col("bi", "BIGINT")),
                List.of(new Row(List.of(Value.of(1.5))), new Row(List.of(Value.of(2L)))),
                0,
                null);
        LibsqlResultSet resultSet = new LibsqlResultSet(null, result);
        ResultSetMetaData metadata = resultSet.getMetaData();

        assertThat(metadata.getColumnType(1)).isEqualTo(Types.BIGINT);

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.BIGINT);

        assertThat(resultSet.next()).isTrue();
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.BIGINT);

        assertThat(resultSet.next()).isFalse();
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.BIGINT);
    }

    private static void assertDeclaredTypes(ResultSetMetaData metadata) throws SQLException {
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.BIGINT);
        assertThat(metadata.getColumnType(2)).isEqualTo(Types.BOOLEAN);
        assertThat(metadata.getColumnType(3)).isEqualTo(Types.NUMERIC);
        assertThat(metadata.getColumnType(4)).isEqualTo(Types.DECIMAL);
    }

    @Test
    void resolvesAliasTableNameAndNullability() throws SQLException {
        LibsqlResultSetMetaData metadata = metadataForSql(
                "SELECT a AS z FROM w", schemaConnection(), List.of(new Col("z", "INTEGER")));

        assertThat(metadata.getTableName(1)).isEqualTo("w");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullable);
    }

    @Test
    void resolvesJoinTableNamesAndNullability() throws SQLException {
        LibsqlResultSetMetaData metadata = metadataForSql(
                "SELECT w.a, z.a FROM w JOIN z ON w.a = z.a",
                schemaConnection(),
                List.of(new Col("a", "INTEGER"), new Col("a", "INTEGER")));

        assertThat(metadata.getTableName(1)).isEqualTo("w");
        assertThat(metadata.getTableName(2)).isEqualTo("z");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNoNulls);
        assertThat(metadata.isNullable(2)).isEqualTo(ResultSetMetaData.columnNullable);
    }

    @Test
    void resolvesSubqueryTableNameAndNullability() throws SQLException {
        LibsqlResultSetMetaData metadata = metadataForSql(
                "SELECT q.a FROM (SELECT a FROM w) q",
                schemaConnection(),
                List.of(new Col("a", "INTEGER")));

        assertThat(metadata.getTableName(1)).isEqualTo("w");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNoNulls);
    }

    @Test
    void resolvesReturningTableNameAndNullability() throws SQLException {
        LibsqlResultSetMetaData metadata = metadataForSql(
                "INSERT INTO w(a, b) VALUES (1, 'x') RETURNING a, b",
                schemaConnection(),
                List.of(new Col("a", "INTEGER"), new Col("b", "TEXT")));

        assertThat(metadata.getTableName(1)).isEqualTo("w");
        assertThat(metadata.getTableName(2)).isEqualTo("w");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNoNulls);
        assertThat(metadata.isNullable(2)).isEqualTo(ResultSetMetaData.columnNullable);
    }

    @Test
    void resolvesAliasedReturningTableNameAndNullability() throws SQLException {
        LibsqlResultSetMetaData metadata = metadataForSql(
                "INSERT INTO w(a, b) VALUES (1, 'x') RETURNING a AS aa",
                schemaConnection(),
                List.of(new Col("aa", "INTEGER")));

        assertThat(metadata.getTableName(1)).isEqualTo("w");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullable);
    }

    @Test
    void resolvesViewTableNameAndNullability() throws SQLException {
        LibsqlResultSetMetaData metadata = metadataForSql(
                "SELECT a, b FROM v",
                schemaConnection(),
                List.of(new Col("a", "INTEGER"), new Col("b", "TEXT")));

        assertThat(metadata.getTableName(1)).isEqualTo("w");
        assertThat(metadata.getTableName(2)).isEqualTo("w");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNoNulls);
        assertThat(metadata.isNullable(2)).isEqualTo(ResultSetMetaData.columnNullable);
    }

    @Test
    void resolvesUsingJoinStarColumnsToTheLeftTable() throws SQLException {
        LibsqlResultSetMetaData metadata = metadataForSql(
                "SELECT * FROM a JOIN b USING(id) JOIN c",
                schemaConnection(),
                List.of(new Col("id", "INTEGER"), new Col("x", "TEXT"), new Col("y", "TEXT"), new Col("z", "TEXT")));

        assertThat(metadata.getTableName(1)).isEqualTo("a");
        assertThat(metadata.getTableName(2)).isEqualTo("a");
        assertThat(metadata.getTableName(3)).isEqualTo("b");
        assertThat(metadata.getTableName(4)).isEqualTo("c");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNoNulls);
    }

    @Test
    void resolvesGeneratedColumnStarColumnsThroughTableXinfo() throws SQLException {
        LibsqlResultSetMetaData metadata = metadataForSql(
                "SELECT * FROM g JOIN c ON 1",
                schemaConnection(),
                List.of(
                        new Col("p", "INTEGER"),
                        new Col("q", "INTEGER"),
                        new Col("r", "TEXT"),
                        new Col("z", "TEXT")));

        assertThat(metadata.getTableName(1)).isEqualTo("g");
        assertThat(metadata.getTableName(2)).isEqualTo("g");
        assertThat(metadata.getTableName(3)).isEqualTo("g");
        assertThat(metadata.getTableName(4)).isEqualTo("c");
        assertThat(metadata.isNullable(3)).isEqualTo(ResultSetMetaData.columnNoNulls);
    }

    @Test
    void excludesHiddenColumnsFromMainSchemaStarExpansion() throws SQLException {
        LibsqlResultSetMetaData metadata = metadataForSql(
                "SELECT * FROM main.docs JOIN main.c ON 1",
                connectionForHiddenColumns(),
                List.of(new Col("title", "TEXT"), new Col("body", "TEXT"), new Col("z", "TEXT")));

        // Star width comes from the xinfo catalogue. A hidden column would widen it past the three
        // protocol columns, and the resolver blanks every table name on that mismatch.
        assertThat(metadata.getTableName(1)).isEqualTo("docs");
        assertThat(metadata.getTableName(2)).isEqualTo("docs");
        assertThat(metadata.getTableName(3)).isEqualTo("c");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNoNulls);
        assertThat(metadata.isNullable(2)).isEqualTo(ResultSetMetaData.columnNullable);
    }

    @Test
    void excludesHiddenColumnsFromAttachedSchemaStarExpansion() throws SQLException {
        LibsqlResultSetMetaData metadata = new LibsqlResultSetMetaData(
                List.of(new Col("title", "TEXT"), new Col("body", "TEXT")),
                List.of(),
                "SELECT * FROM aux.docs",
                connectionForHiddenColumns());

        // Same width mismatch: the attached query must filter the hidden column too.
        assertThat(metadata.getTableName(1)).isEqualTo("docs");
        assertThat(metadata.getTableName(2)).isEqualTo("docs");
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullable);
        assertThat(metadata.isAutoIncrement(1)).isFalse();
    }

    @Test
    void leavesEveryColumnUnknownWhenTheStarWidthDiffers() throws SQLException {
        LibsqlResultSetMetaData metadata = metadataForSql(
                "SELECT * FROM (VALUES(1)) v, c",
                schemaConnection(),
                List.of(new Col("column1", null), new Col("z", "TEXT")));

        assertThat(metadata.getTableName(1)).isEmpty();
        assertThat(metadata.getTableName(2)).isEmpty();
        assertThat(metadata.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullableUnknown);
        assertThat(metadata.isNullable(2)).isEqualTo(ResultSetMetaData.columnNullableUnknown);
    }

    @Test
    void leavesUnsupportedShapesUnknown() throws SQLException {
        LibsqlResultSetMetaData expression = metadataForSql(
                "SELECT a + 1 FROM w", schemaConnection(), List.of(new Col("a + 1", "INTEGER")));
        LibsqlResultSetMetaData literal =
                metadataForSql("SELECT 1 AS one", schemaConnection(), List.of(new Col("one", "INTEGER")));

        assertThat(expression.getTableName(1)).isEmpty();
        assertThat(expression.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullableUnknown);
        assertThat(expression.isAutoIncrement(1)).isFalse();
        assertThat(literal.getTableName(1)).isEmpty();
        assertThat(literal.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullableUnknown);
    }

    private static LibsqlResultSetMetaData metadataForSql(String sql, LibsqlConnection connection, List<Col> cols)
            throws SQLException {
        return new LibsqlResultSetMetaData(cols, List.of(), sql, connection);
    }

    private static LibsqlConnection schemaConnection() {
        Map<String, FakeTable> tables = new LinkedHashMap<>();
        tables.put(
                "w",
                new FakeTable(
                        "w",
                        "CREATE TABLE w (a INTEGER NOT NULL, b TEXT)",
                        List.of(new FakeColumn("a", true, false), new FakeColumn("b", false, false))));
        tables.put(
                "z",
                new FakeTable(
                        "z",
                        "CREATE TABLE z (a INTEGER)",
                        List.of(new FakeColumn("a", false, false))));
        tables.put(
                "v",
                new FakeTable(
                        "v",
                        "CREATE VIEW v AS SELECT a, b FROM w",
                        List.of(new FakeColumn("a", false, false), new FakeColumn("b", false, false))));
        tables.put(
                "a",
                new FakeTable(
                        "a",
                        "CREATE TABLE a (id INTEGER NOT NULL, x TEXT)",
                        List.of(new FakeColumn("id", true, false), new FakeColumn("x", false, false))));
        tables.put(
                "b",
                new FakeTable(
                        "b",
                        "CREATE TABLE b (id INTEGER, y TEXT)",
                        List.of(new FakeColumn("id", false, false), new FakeColumn("y", false, false))));
        tables.put(
                "c",
                new FakeTable(
                        "c", "CREATE TABLE c (z TEXT)", List.of(new FakeColumn("z", false, false))));
        tables.put(
                "g",
                new FakeTable(
                        "g",
                        "CREATE TABLE g (p INTEGER, q AS (p+1), r TEXT NOT NULL)",
                        List.of(
                                new FakeColumn("p", false, false),
                                new FakeColumn("q", false, false, true),
                                new FakeColumn("r", true, false))));
        return new LibsqlConnection(null, null) {
            @Override
            public StmtResult executeStatement(Stmt stmt) throws SQLException {
                boolean xinfo = stmt.sql().contains("pragma_table_xinfo");
                if (!xinfo && !stmt.sql().contains("pragma_table_info")) {
                    return new StmtResult(List.of(), List.of(), 0, null);
                }
                String table = argument(stmt, 0);
                String schema = argument(stmt, 1);
                FakeTable fake = tables.get(table != null ? table.toLowerCase(Locale.ROOT) : "");
                if (fake == null || !"main".equalsIgnoreCase(schema)) {
                    return new StmtResult(List.of(), List.of(), 0, null);
                }
                List<Row> rows = new ArrayList<>();
                for (FakeColumn column : fake.columns()) {
                    if (column.generated() && !xinfo) {
                        continue;
                    }
                    rows.add(new Row(List.of(
                            Value.of(fake.name()),
                            Value.of(fake.sql()),
                            Value.of(column.name()),
                            Value.of(column.notNull() ? 1L : 0L),
                            Value.of(column.primaryKey() ? 1L : 0L))));
                }
                return new StmtResult(List.of(), rows, 0, null);
            }
        };
    }

    private static String argument(Stmt stmt, int index) {
        if (stmt.args() == null || stmt.args().size() <= index) {
            return null;
        }
        return stmt.args().get(index) instanceof Value.Text text ? text.value() : null;
    }

    private record FakeTable(String name, String sql, List<FakeColumn> columns) {}

    private record FakeColumn(String name, boolean notNull, boolean primaryKey, boolean generated, boolean hidden) {
        FakeColumn(String name, boolean notNull, boolean primaryKey) {
            this(name, notNull, primaryKey, false, false);
        }

        FakeColumn(String name, boolean notNull, boolean primaryKey, boolean generated) {
            this(name, notNull, primaryKey, generated, false);
        }
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

    private static LibsqlConnection connectionForSwedishTables() {
        Map<String, String> tables = Map.of(
                "anv", "CREATE TABLE anv (id INTEGER)",
                "användare", "CREATE TABLE användare (id INTEGER, namn_ö TEXT)");
        Map<String, List<String>> columns = Map.of("anv", List.of("id"), "användare", List.of("id", "namn_ö"));
        return new LibsqlConnection(null, null) {
            @Override
            public StmtResult executeStatement(Stmt stmt) throws SQLException {
                String table = argument(stmt, 0);
                String createSql = tables.get(table);
                if (createSql == null) {
                    return new StmtResult(List.of(), List.of(), 0, null);
                }
                List<Row> rows = new ArrayList<>();
                for (String column : columns.get(table)) {
                    rows.add(new Row(List.of(
                            Value.of(table),
                            Value.of(createSql),
                            Value.of(column),
                            Value.of(0L),
                            Value.of(0L))));
                }
                return new StmtResult(List.of(), rows, 0, null);
            }
        };
    }

    private static LibsqlConnection connectionReturning(StmtResult result) {
        return new LibsqlConnection(null, null) {
            @Override
            public StmtResult executeStatement(Stmt stmt) throws SQLException {
                return result;
            }
        };
    }

    /**
     * Answers {@code pragma_database_list} with the given schema names and every per-schema table
     * lookup from a fixed catalogue, so the caller sees metadata only for schemas that the database
     * list actually reports.
     */
    private static LibsqlConnection connectionForAttachedSchema(String... databaseNames) {
        Map<String, FakeTable> tables = new LinkedHashMap<>();
        tables.put(
                "Aux",
                new FakeTable(
                        "People",
                        "CREATE TABLE People (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)",
                        List.of(new FakeColumn("id", true, true), new FakeColumn("name", true, false))));
        tables.put(
                "main",
                new FakeTable(
                        "People",
                        "CREATE TABLE People (id INTEGER, name TEXT)",
                        List.of(new FakeColumn("id", false, false), new FakeColumn("name", false, false))));
        tables.put(
                "ghost",
                new FakeTable(
                        "People",
                        "CREATE TABLE People (id INTEGER)",
                        List.of(new FakeColumn("id", false, false))));
        List<Row> databases = new ArrayList<>();
        for (String name : databaseNames) {
            databases.add(new Row(List.of(Value.of(name))));
        }
        StmtResult databaseList = new StmtResult(List.of(), databases, 0, null);

        return new LibsqlConnection(null, null) {
            @Override
            public StmtResult executeStatement(Stmt stmt) throws SQLException {
                if (stmt.sql().contains("pragma_database_list")) {
                    return databaseList;
                }
                FakeTable table = tables.get(argument(stmt, 1));
                if (table == null) {
                    return new StmtResult(List.of(), List.of(), 0, null);
                }
                List<Row> rows = new ArrayList<>();
                for (FakeColumn column : table.columns()) {
                    rows.add(new Row(List.of(
                            Value.of(table.name()),
                            Value.of(table.sql()),
                            Value.of(column.name()),
                            Value.of(column.notNull() ? 1L : 0L),
                            Value.of(column.primaryKey() ? 1L : 0L))));
                }
                return new StmtResult(List.of(), rows, 0, null);
            }
        };
    }

    /**
     * Answers both {@code pragma_table_xinfo} forms from one catalogue and drops a column exactly
     * when the query filters {@code hidden = 1}, as SQLite does. Dispatch on the query text keeps a
     * filter-free query seeing the hidden column, which widens star expansion. The attached schema
     * reports a nullable {@code title} so its rows are distinguishable from the main schema's.
     */
    private static LibsqlConnection connectionForHiddenColumns() {
        Map<String, FakeTable> tables = new LinkedHashMap<>();
        tables.put(
                "main.docs",
                new FakeTable(
                        "docs",
                        "CREATE VIRTUAL TABLE docs USING fts5(title, body)",
                        List.of(
                                new FakeColumn("title", true, false),
                                new FakeColumn("body", false, false),
                                new FakeColumn("rank", false, false, false, true))));
        tables.put(
                "main.c",
                new FakeTable("c", "CREATE TABLE c (z TEXT)", List.of(new FakeColumn("z", false, false))));
        tables.put(
                "aux.docs",
                new FakeTable(
                        "docs",
                        "CREATE VIRTUAL TABLE docs USING fts5(title, body)",
                        List.of(
                                new FakeColumn("title", false, false),
                                new FakeColumn("body", false, false),
                                new FakeColumn("rank", false, false, false, true))));
        List<Row> databases = List.of(
                new Row(List.of(Value.of("main"))),
                new Row(List.of(Value.of("temp"))),
                new Row(List.of(Value.of("Aux"))));
        StmtResult databaseList = new StmtResult(List.of(), databases, 0, null);

        return new LibsqlConnection(null, null) {
            @Override
            public StmtResult executeStatement(Stmt stmt) throws SQLException {
                if (stmt.sql().contains("pragma_database_list")) {
                    return databaseList;
                }
                if (!stmt.sql().contains("pragma_table_xinfo")) {
                    return new StmtResult(List.of(), List.of(), 0, null);
                }
                String table = argument(stmt, 0);
                String schema = argument(stmt, 1);
                FakeTable fake = tables.get((schema + "." + table).toLowerCase(Locale.ROOT));
                if (fake == null) {
                    return new StmtResult(List.of(), List.of(), 0, null);
                }
                boolean filtersHidden = stmt.sql().contains("p.hidden != 1");
                List<Row> rows = new ArrayList<>();
                for (FakeColumn column : fake.columns()) {
                    if (column.hidden() && filtersHidden) {
                        continue;
                    }
                    rows.add(new Row(List.of(
                            Value.of(fake.name()),
                            Value.of(fake.sql()),
                            Value.of(column.name()),
                            Value.of(column.notNull() ? 1L : 0L),
                            Value.of(column.primaryKey() ? 1L : 0L))));
                }
                return new StmtResult(List.of(), rows, 0, null);
            }
        };
    }
}
