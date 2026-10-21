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

    private static LibsqlConnection connectionReturning(StmtResult result) {
        return new LibsqlConnection(null, null) {
            @Override
            public StmtResult executeStatement(Stmt stmt) throws SQLException {
                return result;
            }
        };
    }
}
