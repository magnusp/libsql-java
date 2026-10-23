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
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void getDatabaseProductVersionClosesItsStatement() throws SQLException {
        RecordingConnection connection = new RecordingConnection(versionResult("3.45.1"));

        new LibsqlDatabaseMetaData(connection).getDatabaseProductVersion();

        assertThat(connection.statements)
                .as("the product-version lookup must open exactly one statement")
                .hasSize(1);
        assertThat(connection.statements.get(0).isClosed())
                .as("the product-version lookup must close its statement")
                .isTrue();
    }

    @Test
    void getDatabaseProductVersionOpensOneStatementAcrossTwoCalls() throws SQLException {
        RecordingConnection connection = new RecordingConnection(versionResult("3.45.1"));
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(connection);

        assertThat(metadata.getDatabaseProductVersion()).isEqualTo("3.45.1");
        assertThat(metadata.getDatabaseProductVersion()).isEqualTo("3.45.1");

        assertThat(connection.statements)
                .as("the second product-version lookup must hit the cache and open nothing")
                .hasSize(1);
    }

    @Test
    void getDatabaseProductVersionRetriesAfterAFailedLookup() throws SQLException {
        RecordingConnection connection = new RecordingConnection(versionResult("3.45.1"));
        connection.failuresRemaining = 1;
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(connection);

        assertThatThrownBy(metadata::getDatabaseProductVersion)
                .as("the first lookup must surface the failure instead of caching a fallback")
                .isInstanceOf(SQLException.class);
        assertThat(metadata.getDatabaseProductVersion())
                .as("the second lookup must retry and return the server version")
                .isEqualTo("3.45.1");
        assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(3);
        assertThat(metadata.getDatabaseMinorVersion()).isEqualTo(45);
    }

    @Test
    void getDatabaseProductVersionThrowsOnClosedConnection() throws SQLException {
        RecordingConnection connection = new RecordingConnection(versionResult("3.45.1"));
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(connection);
        assertThat(metadata.getDatabaseProductVersion()).isEqualTo("3.45.1");
        connection.close();

        assertThatThrownBy(metadata::getDatabaseProductVersion)
                .as("an already cached version must not outlive the connection")
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> new LibsqlDatabaseMetaData(connection).getDatabaseProductVersion())
                .as("an uncached lookup on a closed connection must not return a fallback")
                .isInstanceOf(SQLException.class);
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
    void getColumnsIncludesSqliteSchemaBeforeUserTables() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getColumns(null, null, "%", "%").close();

        assertThat(connection.lastSql)
                .as("getColumns must synthesize the sqlite_schema system table")
                .contains("'sqlite_schema' AS TABLE_NAME")
                .as("getColumns must order system tables before user tables, like getTables")
                .contains("ORDER BY t.TABLE_RANK, t.TABLE_NAME, p.cid");
    }

    @Test
    void getCrossReferenceReturnsNoRowForAnotherCatalogOrSchema() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new ForeignKeyConnection(false));

        try (ResultSet rs =
                metadata.getCrossReference("ca'talog", "sch'ema", "parent", null, null, "child1")) {
            assertThat(columnNames(rs)).hasSize(14);
            assertThat(rs.next())
                    .as("the database has no catalog or schema, so a specific request matches nothing")
                    .isFalse();
        }
    }

    @Test
    void getPrimaryKeysOrdersColumnsByNameAndKeepsTheDeclaredKeySequence() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new CompositePrimaryKeysConnection());

        try (ResultSet rs = metadata.getPrimaryKeys(null, null, "pk")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("col2");
            assertThat(rs.getInt("KEY_SEQ")).isEqualTo(2);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("col3");
            assertThat(rs.getInt("KEY_SEQ")).isEqualTo(1);

            assertThat(rs.next()).isFalse();
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
                        (MetadataCall) m -> m.getIndexInfo(null, null, "t", false, false)),
                arguments("getProcedures", (MetadataCall) m -> m.getProcedures(null, null, null)),
                arguments(
                        "getProcedureColumns",
                        (MetadataCall) m -> m.getProcedureColumns(null, null, null, null)),
                arguments(
                        "getColumnPrivileges",
                        (MetadataCall) m -> m.getColumnPrivileges(null, null, null, null)),
                arguments(
                        "getTablePrivileges",
                        (MetadataCall) m -> m.getTablePrivileges(null, null, null)),
                arguments(
                        "getBestRowIdentifier",
                        (MetadataCall)
                                m -> m.getBestRowIdentifier(
                                        null, null, null, DatabaseMetaData.bestRowSession, false)),
                arguments(
                        "getVersionColumns",
                        (MetadataCall) m -> m.getVersionColumns(null, null, null)),
                arguments("getUDTs", (MetadataCall) m -> m.getUDTs(null, null, null, null)));
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

    private static final String PARENT_CREATE_SQL =
            "CREATE TABLE parent (id INTEGER, name TEXT, CONSTRAINT pk_parent PRIMARY KEY (id))";
    private static final String CHILD_CREATE_SQL =
            "CREATE TABLE child (id INTEGER, parent_id INTEGER, "
                    + "CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES parent (id) "
                    + "ON UPDATE CASCADE ON DELETE SET NULL)";

    @Test
    void getImportedKeysMapsForeignKeyRowsAndNames() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new ForeignKeyConnection(false));

        try (ResultSet rs = metadata.getImportedKeys(null, null, "child")) {
            assertThat(columnNames(rs))
                    .containsExactly(
                            "PKTABLE_CAT",
                            "PKTABLE_SCHEM",
                            "PKTABLE_NAME",
                            "PKCOLUMN_NAME",
                            "FKTABLE_CAT",
                            "FKTABLE_SCHEM",
                            "FKTABLE_NAME",
                            "FKCOLUMN_NAME",
                            "KEY_SEQ",
                            "UPDATE_RULE",
                            "DELETE_RULE",
                            "FK_NAME",
                            "PK_NAME",
                            "DEFERRABILITY");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("PKTABLE_NAME")).isEqualTo("parent");
            assertThat(rs.getString("PKCOLUMN_NAME")).isEqualTo("id");
            assertThat(rs.getString("FKTABLE_NAME")).isEqualTo("child");
            assertThat(rs.getString("FKCOLUMN_NAME")).isEqualTo("parent_id");
            assertThat(rs.getInt("KEY_SEQ")).isEqualTo(1);
            assertThat(rs.getInt("UPDATE_RULE")).isEqualTo(DatabaseMetaData.importedKeyCascade);
            assertThat(rs.getInt("DELETE_RULE")).isEqualTo(DatabaseMetaData.importedKeySetNull);
            assertThat(rs.getString("FK_NAME")).isEqualTo("fk_child_parent");
            assertThat(rs.getString("PK_NAME")).isEqualTo("pk_parent");
            assertThat(rs.getInt("DEFERRABILITY"))
                    .isEqualTo(DatabaseMetaData.importedKeyInitiallyDeferred);
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void getExportedKeysMapsForeignKeyRowsAndNames() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new ForeignKeyConnection(false));

        try (ResultSet rs = metadata.getExportedKeys(null, null, "parent")) {
            assertThat(columnNames(rs)).hasSize(14);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("PKTABLE_NAME")).isEqualTo("parent");
            assertThat(rs.getString("PKCOLUMN_NAME")).isEqualTo("id");
            assertThat(rs.getString("FKTABLE_NAME")).isEqualTo("child");
            assertThat(rs.getString("FKCOLUMN_NAME")).isEqualTo("parent_id");
            assertThat(rs.getInt("KEY_SEQ")).isEqualTo(1);
            assertThat(rs.getInt("UPDATE_RULE")).isEqualTo(DatabaseMetaData.importedKeyNoAction);
            assertThat(rs.getInt("DELETE_RULE")).isEqualTo(DatabaseMetaData.importedKeyCascade);
            assertThat(rs.getString("FK_NAME")).isEqualTo("fk_child_parent");
            assertThat(rs.getString("PK_NAME")).isEqualTo("pk_parent");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void getCrossReferenceMapsBothTables() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new ForeignKeyConnection(false));

        try (ResultSet rs = metadata.getCrossReference(null, null, "parent", null, null, "child")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("PKTABLE_NAME")).isEqualTo("parent");
            assertThat(rs.getString("FKTABLE_NAME")).isEqualTo("child");
            assertThat(rs.getString("FK_NAME")).isEqualTo("fk_child_parent");
            assertThat(rs.getString("PK_NAME")).isEqualTo("pk_parent");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void getCrossReferenceWithoutAChildTableDelegatesToImportedKeys() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new ForeignKeyConnection(false));

        try (ResultSet rs = metadata.getCrossReference(null, null, "child", null, null, null)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("FKTABLE_NAME")).isEqualTo("child");
            assertThat(rs.getString("FKCOLUMN_NAME")).isEqualTo("parent_id");
        }
    }

    @Test
    void emptyDatabaseReturnsWellFormedEmptyKeyMetadata() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new ForeignKeyConnection(true));

        try (ResultSet rs = metadata.getImportedKeys(null, null, "child")) {
            assertThat(columnNames(rs)).hasSize(14);
            assertThat(rs.next()).isFalse();
        }
        try (ResultSet rs = metadata.getExportedKeys(null, null, "parent")) {
            assertThat(columnNames(rs)).hasSize(14);
            assertThat(rs.next()).isFalse();
        }
        try (ResultSet rs = metadata.getCrossReference(null, null, "parent", null, null, "child")) {
            assertThat(columnNames(rs)).hasSize(14);
            assertThat(rs.next()).isFalse();
        }
        try (ResultSet rs = metadata.getPrimaryKeys(null, null, "parent")) {
            assertThat(columnNames(rs))
                    .containsExactly(
                            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void getPrimaryKeysReturnsTheParsedConstraintName() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new PrimaryKeysConnection(PARENT_CREATE_SQL));

        try (ResultSet rs = metadata.getPrimaryKeys(null, null, "parent")) {
            assertThat(columnNames(rs))
                    .containsExactly(
                            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("TABLE_NAME")).isEqualTo("parent");
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("id");
            assertThat(rs.getInt("KEY_SEQ")).isEqualTo(1);
            assertThat(rs.getString("PK_NAME")).isEqualTo("pk_parent");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void getPrimaryKeysLeavesPKNameNullForAnInlinePrimaryKey() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(
                new PrimaryKeysConnection("CREATE TABLE tag (id INTEGER PRIMARY KEY, label TEXT)"));

        try (ResultSet rs = metadata.getPrimaryKeys(null, null, "tag")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("id");
            assertThat(rs.getString("PK_NAME")).isNull();
        }
    }

    @Test
    void getPrimaryKeysReadsAConstraintNameGluedToTheKeywords() throws SQLException {
        for (String createSql : List.of(
                "CREATE TABLE `Pk10` (`Col1`, `Col2`, `Col3`, CONSTRAINT `NamedPk`PRIMARY KEY (`Col3`, `Col2`))",
                "CREATE TABLE `Pk12` (`Col1`, `Col2`, `Col3`, CONSTRAINT`NamedPk`PRIMARY KEY(`Col3`,`Col2`))")) {
            LibsqlDatabaseMetaData metadata =
                    new LibsqlDatabaseMetaData(new PrimaryKeysConnection(createSql));

            try (ResultSet rs = metadata.getPrimaryKeys(null, null, "pk")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("PK_NAME"))
                        .as(createSql)
                        .isEqualTo("NamedPk");
            }
        }
    }

    @Test
    void getTypeInfoReturnsTheXerialTypeTable() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new RecordingConnection(emptyResult()));

        try (ResultSet rs = metadata.getTypeInfo()) {
            assertThat(columnNames(rs))
                    .containsExactly(
                            "TYPE_NAME",
                            "DATA_TYPE",
                            "PRECISION",
                            "LITERAL_PREFIX",
                            "LITERAL_SUFFIX",
                            "CREATE_PARAMS",
                            "NULLABLE",
                            "CASE_SENSITIVE",
                            "SEARCHABLE",
                            "UNSIGNED_ATTRIBUTE",
                            "FIXED_PREC_SCALE",
                            "AUTO_INCREMENT",
                            "LOCAL_TYPE_NAME",
                            "MINIMUM_SCALE",
                            "MAXIMUM_SCALE",
                            "SQL_DATA_TYPE",
                            "SQL_DATETIME_SUB",
                            "NUM_PREC_RADIX");

            List<String> names = new ArrayList<>();
            List<Integer> types = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString("TYPE_NAME"));
                types.add(rs.getInt("DATA_TYPE"));
                assertThat(rs.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.typeNullable);
                assertThat(rs.getInt("SEARCHABLE")).isEqualTo(DatabaseMetaData.typeSearchable);
                assertThat(rs.getInt("NUM_PREC_RADIX")).isEqualTo(10);
            }

            assertThat(names).containsExactly("NULL", "INTEGER", "REAL", "TEXT", "BLOB");
            assertThat(types).containsExactly(Types.NULL, Types.INTEGER, Types.REAL, Types.VARCHAR, Types.BLOB);
        }
    }

    @Test
    void everyUnsupportedMethodThrowsSqlFeatureNotSupportedException() {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new RecordingConnection(emptyResult()));

        List<UnsupportedCall> calls = List.of(
                new UnsupportedCall("getSuperTypes", () -> metadata.getSuperTypes(null, null, null)),
                new UnsupportedCall("getSuperTables", () -> metadata.getSuperTables(null, null, null)),
                new UnsupportedCall("getAttributes", () -> metadata.getAttributes(null, null, null, null)),
                new UnsupportedCall("getClientInfoProperties", metadata::getClientInfoProperties),
                new UnsupportedCall("getFunctions", () -> metadata.getFunctions(null, null, null)),
                new UnsupportedCall("getFunctionColumns", () -> metadata.getFunctionColumns(null, null, null, null)),
                new UnsupportedCall("getPseudoColumns", () -> metadata.getPseudoColumns(null, null, null, null)));

        for (UnsupportedCall call : calls) {
            assertThatThrownBy(call.call())
                    .as(call.name())
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyMetadataShapes")
    void metadataMethodsWithoutSqliteSupportReturnJavaSqlShapedEmptyResultSets(
            String method, List<String> columns, MetadataCall call) throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new RecordingConnection(emptyResult()));

        try (ResultSet resultSet = call.execute(metadata)) {
            assertThat(resultSet.next())
                    .as("%s must return an empty result set", method)
                    .isFalse();
            assertThat(columnNames(resultSet))
                    .as("%s must return the java.sql column list", method)
                    .containsExactlyElementsOf(columns);
        }
    }

    static Stream<Arguments> emptyMetadataShapes() {
        return Stream.of(
                arguments(
                        "getProcedures",
                        List.of(
                                "PROCEDURE_CAT",
                                "PROCEDURE_SCHEM",
                                "PROCEDURE_NAME",
                                "UNDEF1",
                                "UNDEF2",
                                "UNDEF3",
                                "REMARKS",
                                "PROCEDURE_TYPE"),
                        (MetadataCall) m -> m.getProcedures(null, null, null)),
                arguments(
                        "getProcedureColumns",
                        List.of(
                                "PROCEDURE_CAT",
                                "PROCEDURE_SCHEM",
                                "PROCEDURE_NAME",
                                "COLUMN_NAME",
                                "COLUMN_TYPE",
                                "DATA_TYPE",
                                "TYPE_NAME",
                                "PRECISION",
                                "LENGTH",
                                "SCALE",
                                "RADIX",
                                "NULLABLE",
                                "REMARKS"),
                        (MetadataCall) m -> m.getProcedureColumns(null, null, null, null)),
                arguments(
                        "getColumnPrivileges",
                        List.of(
                                "TABLE_CAT",
                                "TABLE_SCHEM",
                                "TABLE_NAME",
                                "COLUMN_NAME",
                                "GRANTOR",
                                "GRANTEE",
                                "PRIVILEGE",
                                "IS_GRANTABLE"),
                        (MetadataCall) m -> m.getColumnPrivileges(null, null, null, null)),
                arguments(
                        "getTablePrivileges",
                        List.of(
                                "TABLE_CAT",
                                "TABLE_SCHEM",
                                "TABLE_NAME",
                                "GRANTOR",
                                "GRANTEE",
                                "PRIVILEGE",
                                "IS_GRANTABLE"),
                        (MetadataCall) m -> m.getTablePrivileges(null, null, null)),
                arguments(
                        "getBestRowIdentifier",
                        List.of(
                                "SCOPE",
                                "COLUMN_NAME",
                                "DATA_TYPE",
                                "TYPE_NAME",
                                "COLUMN_SIZE",
                                "BUFFER_LENGTH",
                                "DECIMAL_DIGITS",
                                "PSEUDO_COLUMN"),
                        (MetadataCall)
                                m -> m.getBestRowIdentifier(
                                        null, null, null, DatabaseMetaData.bestRowSession, false)),
                arguments(
                        "getVersionColumns",
                        List.of(
                                "SCOPE",
                                "COLUMN_NAME",
                                "DATA_TYPE",
                                "TYPE_NAME",
                                "COLUMN_SIZE",
                                "BUFFER_LENGTH",
                                "DECIMAL_DIGITS",
                                "PSEUDO_COLUMN"),
                        (MetadataCall) m -> m.getVersionColumns(null, null, null)),
                arguments(
                        "getUDTs",
                        List.of(
                                "TYPE_CAT",
                                "TYPE_SCHEM",
                                "TYPE_NAME",
                                "CLASS_NAME",
                                "DATA_TYPE",
                                "REMARKS",
                                "BASE_TYPE"),
                        (MetadataCall) m -> m.getUDTs(null, null, null, null)));
    }

    private record UnsupportedCall(String name, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {}

    private static List<String> columnNames(ResultSet rs) throws SQLException {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            names.add(rs.getMetaData().getColumnName(i));
        }
        return names;
    }

    private static StmtResult emptyResult() {
        return new StmtResult(List.of(new Col("TABLE_NAME", "TEXT")), List.of(), 0, null);
    }

    private static final class ForeignKeyConnection extends LibsqlConnection {
        private final boolean empty;

        private ForeignKeyConnection(boolean empty) {
            super(null, null);
            this.empty = empty;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            String sql = stmt.sql();
            if (sql.contains("JOIN pragma_foreign_key_list")) {
                return empty ? emptyForeignKeyResult() : exportedForeignKeyResult();
            }
            if (sql.contains("FROM pragma_foreign_key_list")) {
                return empty ? emptyForeignKeyResult() : importedForeignKeyResult();
            }
            if (sql.contains("FROM pragma_table_info")) {
                return new StmtResult(
                        List.of(new Col("COLUMN_NAME", "TEXT"), new Col("KEY_SEQ", "INTEGER"), new Col("CREATE_SQL", "TEXT")),
                        List.of(),
                        0,
                        null);
            }
            throw new AssertionError("unexpected metadata query: " + sql);
        }

        private static StmtResult importedForeignKeyResult() {
            return new StmtResult(
                    List.of(
                            new Col("FK_ID", "INTEGER"),
                            new Col("KEY_SEQ", "INTEGER"),
                            new Col("PKTABLE_NAME", "TEXT"),
                            new Col("FKCOLUMN_NAME", "TEXT"),
                            new Col("PKCOLUMN_NAME", "TEXT"),
                            new Col("UPDATE_RULE", "TEXT"),
                            new Col("DELETE_RULE", "TEXT"),
                            new Col("FK_CREATE_SQL", "TEXT"),
                            new Col("PK_CREATE_SQL", "TEXT"),
                            new Col("PK_FIRST_COLUMN", "TEXT")),
                    List.of(new Row(List.of(
                            Value.of(0),
                            Value.of(0),
                            Value.of("parent"),
                            Value.of("parent_id"),
                            Value.of("id"),
                            Value.of("CASCADE"),
                            Value.of("SET NULL"),
                            Value.of(CHILD_CREATE_SQL),
                            Value.of(PARENT_CREATE_SQL),
                            Value.of("id")))),
                    0,
                    null);
        }

        private static StmtResult exportedForeignKeyResult() {
            return new StmtResult(
                    List.of(
                            new Col("FK_ID", "INTEGER"),
                            new Col("KEY_SEQ", "INTEGER"),
                            new Col("PKTABLE_NAME", "TEXT"),
                            new Col("FKCOLUMN_NAME", "TEXT"),
                            new Col("PKCOLUMN_NAME", "TEXT"),
                            new Col("UPDATE_RULE", "TEXT"),
                            new Col("DELETE_RULE", "TEXT"),
                            new Col("FKTABLE_NAME", "TEXT"),
                            new Col("FK_CREATE_SQL", "TEXT"),
                            new Col("PK_CREATE_SQL", "TEXT"),
                            new Col("PK_COLUMNS", "TEXT")),
                    List.of(new Row(List.of(
                            Value.of(0),
                            Value.of(0),
                            Value.of("parent"),
                            Value.of("parent_id"),
                            Value.of("id"),
                            Value.of("NO ACTION"),
                            Value.of("CASCADE"),
                            Value.of("child"),
                            Value.of(CHILD_CREATE_SQL),
                            Value.of(PARENT_CREATE_SQL),
                            Value.of("id")))),
                    0,
                    null);
        }

        private static StmtResult emptyForeignKeyResult() {
            return new StmtResult(
                    List.of(new Col("FK_ID", "INTEGER"), new Col("KEY_SEQ", "INTEGER")), List.of(), 0, null);
        }
    }

    private static final class PrimaryKeysConnection extends LibsqlConnection {
        private final String createSql;

        private PrimaryKeysConnection(String createSql) {
            super(null, null);
            this.createSql = createSql;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            return new StmtResult(
                    List.of(
                            new Col("COLUMN_NAME", "TEXT"),
                            new Col("KEY_SEQ", "INTEGER"),
                            new Col("CREATE_SQL", "TEXT")),
                    List.of(new Row(List.of(Value.of("id"), Value.of(1), Value.of(createSql)))),
                    0,
                    null);
        }
    }

    private static final class CompositePrimaryKeysConnection extends LibsqlConnection {
        private CompositePrimaryKeysConnection() {
            super(null, null);
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            return new StmtResult(
                    List.of(
                            new Col("COLUMN_NAME", "TEXT"),
                            new Col("KEY_SEQ", "INTEGER"),
                            new Col("CREATE_SQL", "TEXT")),
                    List.of(
                            new Row(List.of(
                                    Value.of("col3"),
                                    Value.of(1),
                                    Value.of("CREATE TABLE pk (col1, col2, col3, col4, PRIMARY KEY (col3, col2))"))),
                            new Row(List.of(
                                    Value.of("col2"),
                                    Value.of(2),
                                    Value.of("CREATE TABLE pk (col1, col2, col3, col4, PRIMARY KEY (col3, col2))")))),
                    0,
                    null);
        }
    }

    private static StmtResult versionResult(String version) {
        return new StmtResult(
                List.of(new Col("sqlite_version()", "TEXT")),
                List.of(new Row(List.of(Value.of(version)))),
                0,
                null);
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
        private final List<LibsqlStatement> statements = new ArrayList<>();
        private String lastSql;
        private int failuresRemaining;

        private RecordingConnection(StmtResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public Statement createStatement() throws SQLException {
            LibsqlStatement statement = (LibsqlStatement) super.createStatement();
            statements.add(statement);
            return statement;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) throws SQLException {
            lastSql = stmt.sql();
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new SQLException("simulated product-version lookup failure");
            }
            return result;
        }
    }
}
