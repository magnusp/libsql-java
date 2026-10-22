package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class LibsqlDatabaseMetaDataTest {

    @Test
    void getTablesClosesResultSetWhenStatementCloses() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new RecordingConnection(emptyResult()));

        ResultSet resultSet = metadata.getTables(null, null, null, null);
        LibsqlStatement statement = (LibsqlStatement) resultSet.getStatement();
        statement.close();

        assertThat(resultSet.isClosed()).isTrue();
    }

    @Test
    void escapesSingleQuoteInTableNamePattern() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(connection);

        ResultSet resultSet = metadata.getTables(null, null, "O'Brien", null);
        resultSet.close();

        assertThat(connection.lastSql).contains("TABLE_NAME LIKE 'O''Brien'");
        assertThat(connection.lastSql).doesNotContain("TABLE_NAME LIKE 'O'Brien'");
    }

    @Test
    void getIndexInfoRoundTripsQuotedTableName() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new IndexInfoConnection());

        try (ResultSet resultSet = metadata.getIndexInfo(null, null, "t'x", false, false)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("TABLE_NAME")).isEqualTo("t'x");
            assertThat(resultSet.getString("INDEX_NAME")).isEqualTo("idx_t");
            assertThat(resultSet.getString("COLUMN_NAME")).isEqualTo("col");
        }
    }

    @Test
    void getTablesWithNullTypesReturnsOnlyTablesViewsAndSystemAndTemporaryRows() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, null, null).close();

        assertThat(connection.lastSql)
                .contains("'sqlite_schema' AS NAME")
                .contains("'SYSTEM TABLE' AS TYPE")
                .contains("'GLOBAL TEMPORARY' AS TYPE")
                .contains("FROM sqlite_temp_master")
                .contains("UPPER(TYPE) IN ('TABLE', 'VIEW')")
                .contains("TABLE_NAME LIKE '%'")
                .doesNotContain("TABLE_TYPE IN")
                .doesNotContain("INDEX")
                .doesNotContain("TRIGGER");
    }

    @Test
    void getTablesWithEmptyTypesBehavesLikeNullTypes() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, null, new String[0]).close();

        assertThat(connection.lastSql)
                .contains("'SYSTEM TABLE' AS TYPE")
                .contains("'GLOBAL TEMPORARY' AS TYPE")
                .contains("UPPER(TYPE) IN ('TABLE', 'VIEW')")
                .doesNotContain("TABLE_TYPE IN");
    }

    @Test
    void getTablesWithTableTypeFiltersToTableRows() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, null, new String[] {"TABLE"}).close();

        assertThat(connection.lastSql)
                .contains("UPPER(TYPE) IN ('TABLE', 'VIEW')")
                .contains("AND TABLE_TYPE IN ('TABLE')");
    }

    @Test
    void getTablesWithViewTypeFiltersToViewRows() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, null, new String[] {"VIEW"}).close();

        assertThat(connection.lastSql).contains("AND TABLE_TYPE IN ('VIEW')");
    }

    @Test
    void getTablesWithMultipleTypesUppercasesThem() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, null, new String[] {"table", "View"}).close();

        assertThat(connection.lastSql).contains("AND TABLE_TYPE IN ('TABLE','VIEW')");
    }

    @Test
    void getTablesWithEmptyTableNamePatternMatchesEverything() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, "", null).close();

        assertThat(connection.lastSql).contains("TABLE_NAME LIKE '%'");
    }

    @Test
    void getTableTypesReturnsEveryXerialTableType() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new TableTypesConnection());

        List<String> tableTypes = new ArrayList<>();
        try (ResultSet resultSet = metadata.getTableTypes()) {
            while (resultSet.next()) {
                tableTypes.add(resultSet.getString("TABLE_TYPE"));
            }
        }

        assertThat(tableTypes)
                .containsExactlyInAnyOrder("TABLE", "VIEW", "SYSTEM TABLE", "GLOBAL TEMPORARY");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataQueries")
    void closesStatementWithoutCloseOnCompletion(String method, MetadataCall call) throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new RecordingConnection(emptyResult()));

        ResultSet resultSet = call.execute(metadata);
        assertThat(resultSet.getStatement())
                .as("%s must expose the statement that produced it", method)
                .isInstanceOf(LibsqlStatement.class);
        LibsqlStatement statement = (LibsqlStatement) resultSet.getStatement();
        assertThat(statement.isCloseOnCompletion())
                .as("%s must not rely on closeOnCompletion to close its statement", method)
                .isFalse();

        resultSet.close();

        assertThat(statement.isClosed())
                .as("%s must close its statement when the result set closes", method)
                .isTrue();
    }

    static Stream<Arguments> metadataQueries() {
        return Stream.of(
                arguments("getTables", (MetadataCall) m -> m.getTables(null, null, null, null)),
                arguments("getColumns", (MetadataCall) m -> m.getColumns(null, null, "t", null)),
                arguments("getPrimaryKeys", (MetadataCall) m -> m.getPrimaryKeys(null, null, "t")),
                arguments("getTableTypes", (MetadataCall) LibsqlDatabaseMetaData::getTableTypes),
                arguments("getSchemas", (MetadataCall) LibsqlDatabaseMetaData::getSchemas),
                arguments("getSchemas(catalog, schemaPattern)", (MetadataCall) m -> m.getSchemas(null, null)),
                arguments("getCatalogs", (MetadataCall) LibsqlDatabaseMetaData::getCatalogs),
                arguments("getTypeInfo", (MetadataCall) LibsqlDatabaseMetaData::getTypeInfo),
                arguments("getImportedKeys", (MetadataCall) m -> m.getImportedKeys(null, null, "t")),
                arguments("getExportedKeys", (MetadataCall) m -> m.getExportedKeys(null, null, "t")),
                arguments(
                        "getCrossReference",
                        (MetadataCall)
                                m -> m.getCrossReference(null, null, "parent", null, null, "child")),
                arguments(
                        "getIndexInfo",
                        (MetadataCall) m -> m.getIndexInfo(null, null, "t", false, false)));
    }

    @FunctionalInterface
    private interface MetadataCall {
        ResultSet execute(LibsqlDatabaseMetaData metadata) throws SQLException;
    }

    @Test
    void getColumnsReportsDeclaredTypesSizesNullabilityAndAutoIncrement() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new StmtResultConnection(columnMetadataRows()));

        try (ResultSet rs = metadata.getColumns(null, null, "c", null)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("TABLE_NAME")).isEqualTo("c");
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("i");
            assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.INTEGER);
            assertThat(rs.getString("TYPE_NAME")).isEqualTo("INTEGER");
            assertThat(rs.getInt("COLUMN_SIZE")).isEqualTo(2_000_000_000);
            assertThat(rs.getInt("DECIMAL_DIGITS")).isZero();
            assertThat(rs.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNullable);
            assertThat(rs.getString("IS_NULLABLE")).isEqualTo("YES");
            assertThat(rs.getString("IS_AUTOINCREMENT")).isEqualTo("NO");
            assertThat(rs.getInt("ORDINAL_POSITION")).isEqualTo(1);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("d");
            assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.FLOAT);
            assertThat(rs.getString("TYPE_NAME")).isEqualTo("DECIMAL");
            assertThat(rs.getInt("COLUMN_SIZE")).isEqualTo(15);
            assertThat(rs.getInt("DECIMAL_DIGITS")).isEqualTo(5);
            assertThat(rs.getInt("ORDINAL_POSITION")).isEqualTo(2);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("k");
            assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.INTEGER);
            assertThat(rs.getString("IS_AUTOINCREMENT")).isEqualTo("YES");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("n");
            assertThat(rs.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNoNulls);
            assertThat(rs.getString("IS_NULLABLE")).isEqualTo("NO");
            assertThat(rs.getString("IS_AUTOINCREMENT")).isEqualTo("NO");

            assertThat(rs.next()).isFalse();
        }
    }

    private static StmtResult columnMetadataRows() {
        String createSql =
                "CREATE TABLE c (i INTEGER, d DECIMAL(10,5), k INTEGER PRIMARY KEY AUTOINCREMENT, n TEXT NOT NULL)";
        return new StmtResult(
                List.of(
                        new Col("TABLE_NAME", "TEXT"),
                        new Col("CREATE_SQL", "TEXT"),
                        new Col("CID", "INTEGER"),
                        new Col("COLUMN_NAME", "TEXT"),
                        new Col("DECLARED_TYPE", "TEXT"),
                        new Col("NOT_NULL", "INTEGER"),
                        new Col("COLUMN_DEF", "TEXT"),
                        new Col("PK", "INTEGER"),
                        new Col("HIDDEN", "INTEGER")),
                List.of(
                        columnRow("c", createSql, 0, "i", "INTEGER", 0, null, 0, 0),
                        columnRow("c", createSql, 1, "d", "DECIMAL(10,5)", 0, null, 0, 0),
                        columnRow("c", createSql, 2, "k", "INTEGER", 0, null, 1, 0),
                        columnRow("c", createSql, 3, "n", "TEXT", 1, null, 0, 0)),
                0,
                null);
    }

    private static Row columnRow(
            String table,
            String createSql,
            int cid,
            String column,
            String declaredType,
            int notNull,
            String columnDefault,
            int primaryKey,
            int hidden) {
        return new Row(List.of(
                Value.of(table),
                Value.of(createSql),
                Value.of(cid),
                Value.of(column),
                Value.of(declaredType),
                Value.of(notNull),
                Value.of(columnDefault),
                Value.of(primaryKey),
                Value.of(hidden)));
    }

    private static StmtResult emptyResult() {
        return new StmtResult(List.of(new Col("TABLE_NAME", "TEXT")), List.of(), 0, null);
    }

    private static final class StmtResultConnection extends LibsqlConnection {
        private final StmtResult result;

        private StmtResultConnection(StmtResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            return result;
        }
    }

    private static final class TableTypesConnection extends LibsqlConnection {
        private static final Pattern TYPE_ROW = Pattern.compile("SELECT '([^']*)' AS TABLE_TYPE");

        private TableTypesConnection() {
            super(null, null);
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            List<Row> rows = new ArrayList<>();
            Matcher matcher = TYPE_ROW.matcher(stmt.sql());
            while (matcher.find()) {
                rows.add(new Row(List.of(Value.of(matcher.group(1)))));
            }
            return new StmtResult(List.of(new Col("TABLE_TYPE", "TEXT")), rows, 0, null);
        }
    }

    private static final class IndexInfoConnection extends LibsqlConnection {
        private IndexInfoConnection() {
            super(null, null);
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) throws SQLException {
            String sql = stmt.sql();
            String selectedTable = literalAfter(sql, "NULL AS TABLE_SCHEM, ", " AS TABLE_NAME");
            String pragmaTable = literalAfter(sql, "pragma_index_list(", ") il");
            if (!selectedTable.equals(pragmaTable)) {
                throw new SQLException("TABLE_NAME literal does not match the pragma table name");
            }
            return new StmtResult(
                    List.of(
                            new Col("TABLE_CAT", "TEXT"),
                            new Col("TABLE_SCHEM", "TEXT"),
                            new Col("TABLE_NAME", "TEXT"),
                            new Col("NON_UNIQUE", "INTEGER"),
                            new Col("INDEX_QUALIFIER", "TEXT"),
                            new Col("INDEX_NAME", "TEXT"),
                            new Col("TYPE", "INTEGER"),
                            new Col("ORDINAL_POSITION", "INTEGER"),
                            new Col("COLUMN_NAME", "TEXT"),
                            new Col("ASC_OR_DESC", "TEXT"),
                            new Col("CARDINALITY", "INTEGER"),
                            new Col("PAGES", "INTEGER"),
                            new Col("FILTER_CONDITION", "TEXT")),
                    List.of(new Row(List.of(
                            Value.ofNull(),
                            Value.ofNull(),
                            Value.of(selectedTable),
                            Value.of(1),
                            Value.ofNull(),
                            Value.of("idx_t"),
                            Value.of(3),
                            Value.of(1),
                            Value.of("col"),
                            Value.of("A"),
                            Value.of(0),
                            Value.of(0),
                            Value.ofNull()))),
                    0,
                    null);
        }

        private static String literalAfter(String sql, String prefix, String suffix) throws SQLException {
            int prefixIndex = sql.indexOf(prefix);
            if (prefixIndex < 0) {
                throw new SQLException("missing " + prefix);
            }
            int quote = prefixIndex + prefix.length();
            if (quote >= sql.length() || sql.charAt(quote) != '\'') {
                throw new SQLException("expected a quoted literal after " + prefix);
            }
            StringBuilder literal = new StringBuilder();
            int i = quote + 1;
            while (i < sql.length()) {
                char c = sql.charAt(i);
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        literal.append('\'');
                        i += 2;
                        continue;
                    }
                    if (!sql.startsWith(suffix, i + 1)) {
                        throw new SQLException("syntax error after table-name literal");
                    }
                    return literal.toString();
                }
                literal.append(c);
                i++;
            }
            throw new SQLException("unterminated table-name literal");
        }
    }

    private static final class RecordingConnection extends LibsqlConnection {
        private final StmtResult result;
        private String lastSql;

        private RecordingConnection(StmtResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) throws SQLException {
            lastSql = stmt.sql();
            return result;
        }
    }
}
