package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LibsqlDatabaseMetaData implements DatabaseMetaData {

    private static final Pattern INTEGER_TYPE = Pattern.compile(".*(INT|BOOL).*");
    private static final Pattern VARCHAR_TYPE = Pattern.compile(".*(CHAR|CLOB|TEXT|BLOB).*");
    private static final Pattern FLOAT_TYPE = Pattern.compile(".*(REAL|FLOA|DOUB|DEC|NUM).*");
    private static final Pattern NAMED_PRIMARY_KEY = Pattern.compile(
            "\\bCONSTRAINT\\s*(\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|'[^']+'|[^\\s]+)\\s*PRIMARY\\s+KEY\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NAMED_FOREIGN_KEY = Pattern.compile(
            "\\bCONSTRAINT\\s+(\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|'[^']+'|[^\\s]+)\\s+FOREIGN\\s+KEY\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int UNKNOWN_COLUMN_SIZE = 2_000_000_000;
    private static final int DEFAULT_DECIMAL_DIGITS = 10;

    private static final List<Col> FOREIGN_KEY_COLUMNS = List.of(
            new Col("PKTABLE_CAT", "TEXT"),
            new Col("PKTABLE_SCHEM", "TEXT"),
            new Col("PKTABLE_NAME", "TEXT"),
            new Col("PKCOLUMN_NAME", "TEXT"),
            new Col("FKTABLE_CAT", "TEXT"),
            new Col("FKTABLE_SCHEM", "TEXT"),
            new Col("FKTABLE_NAME", "TEXT"),
            new Col("FKCOLUMN_NAME", "TEXT"),
            new Col("KEY_SEQ", "INTEGER"),
            new Col("UPDATE_RULE", "INTEGER"),
            new Col("DELETE_RULE", "INTEGER"),
            new Col("FK_NAME", "TEXT"),
            new Col("PK_NAME", "TEXT"),
            new Col("DEFERRABILITY", "INTEGER"));

    private static final List<Col> PRIMARY_KEY_COLUMNS = List.of(
            new Col("TABLE_CAT", "TEXT"),
            new Col("TABLE_SCHEM", "TEXT"),
            new Col("TABLE_NAME", "TEXT"),
            new Col("COLUMN_NAME", "TEXT"),
            new Col("KEY_SEQ", "INTEGER"),
            new Col("PK_NAME", "TEXT"));

    private static final List<Col> TYPE_INFO_COLUMNS = List.of(
            new Col("TYPE_NAME", "TEXT"),
            new Col("DATA_TYPE", "INTEGER"),
            new Col("PRECISION", "INTEGER"),
            new Col("LITERAL_PREFIX", "TEXT"),
            new Col("LITERAL_SUFFIX", "TEXT"),
            new Col("CREATE_PARAMS", "TEXT"),
            new Col("NULLABLE", "INTEGER"),
            new Col("CASE_SENSITIVE", "INTEGER"),
            new Col("SEARCHABLE", "INTEGER"),
            new Col("UNSIGNED_ATTRIBUTE", "INTEGER"),
            new Col("FIXED_PREC_SCALE", "INTEGER"),
            new Col("AUTO_INCREMENT", "INTEGER"),
            new Col("LOCAL_TYPE_NAME", "TEXT"),
            new Col("MINIMUM_SCALE", "INTEGER"),
            new Col("MAXIMUM_SCALE", "INTEGER"),
            new Col("SQL_DATA_TYPE", "INTEGER"),
            new Col("SQL_DATETIME_SUB", "INTEGER"),
            new Col("NUM_PREC_RADIX", "INTEGER"));

    private static final StmtResult TYPE_INFO_RESULT = new StmtResult(
            TYPE_INFO_COLUMNS,
            List.of(
                    typeInfoRow("NULL", Types.NULL, 0, 1, 0),
                    typeInfoRow("INTEGER", Types.INTEGER, 0, 0, 1),
                    typeInfoRow("REAL", Types.REAL, 0, 0, 0),
                    typeInfoRow("TEXT", Types.VARCHAR, 1, 1, 0),
                    typeInfoRow("BLOB", Types.BLOB, 0, 1, 0)),
            0,
            null);

    private static final StmtResult PROCEDURES_RESULT = emptyMetadataResult(List.of(
            new Col("PROCEDURE_CAT", "TEXT"),
            new Col("PROCEDURE_SCHEM", "TEXT"),
            new Col("PROCEDURE_NAME", "TEXT"),
            new Col("UNDEF1", "TEXT"),
            new Col("UNDEF2", "TEXT"),
            new Col("UNDEF3", "TEXT"),
            new Col("REMARKS", "TEXT"),
            new Col("PROCEDURE_TYPE", "INTEGER")));

    private static final StmtResult PROCEDURE_COLUMNS_RESULT = emptyMetadataResult(List.of(
            new Col("PROCEDURE_CAT", "TEXT"),
            new Col("PROCEDURE_SCHEM", "TEXT"),
            new Col("PROCEDURE_NAME", "TEXT"),
            new Col("COLUMN_NAME", "TEXT"),
            new Col("COLUMN_TYPE", "INTEGER"),
            new Col("DATA_TYPE", "INTEGER"),
            new Col("TYPE_NAME", "TEXT"),
            new Col("PRECISION", "INTEGER"),
            new Col("LENGTH", "INTEGER"),
            new Col("SCALE", "INTEGER"),
            new Col("RADIX", "INTEGER"),
            new Col("NULLABLE", "INTEGER"),
            new Col("REMARKS", "TEXT")));

    private static final StmtResult COLUMN_PRIVILEGES_RESULT = emptyMetadataResult(List.of(
            new Col("TABLE_CAT", "TEXT"),
            new Col("TABLE_SCHEM", "TEXT"),
            new Col("TABLE_NAME", "TEXT"),
            new Col("COLUMN_NAME", "TEXT"),
            new Col("GRANTOR", "TEXT"),
            new Col("GRANTEE", "TEXT"),
            new Col("PRIVILEGE", "TEXT"),
            new Col("IS_GRANTABLE", "TEXT")));

    private static final StmtResult TABLE_PRIVILEGES_RESULT = emptyMetadataResult(List.of(
            new Col("TABLE_CAT", "TEXT"),
            new Col("TABLE_SCHEM", "TEXT"),
            new Col("TABLE_NAME", "TEXT"),
            new Col("GRANTOR", "TEXT"),
            new Col("GRANTEE", "TEXT"),
            new Col("PRIVILEGE", "TEXT"),
            new Col("IS_GRANTABLE", "TEXT")));

    private static final StmtResult BEST_ROW_IDENTIFIER_RESULT = emptyMetadataResult(List.of(
            new Col("SCOPE", "INTEGER"),
            new Col("COLUMN_NAME", "TEXT"),
            new Col("DATA_TYPE", "INTEGER"),
            new Col("TYPE_NAME", "TEXT"),
            new Col("COLUMN_SIZE", "INTEGER"),
            new Col("BUFFER_LENGTH", "INTEGER"),
            new Col("DECIMAL_DIGITS", "INTEGER"),
            new Col("PSEUDO_COLUMN", "INTEGER")));

    private static final StmtResult VERSION_COLUMNS_RESULT = emptyMetadataResult(List.of(
            new Col("SCOPE", "INTEGER"),
            new Col("COLUMN_NAME", "TEXT"),
            new Col("DATA_TYPE", "INTEGER"),
            new Col("TYPE_NAME", "TEXT"),
            new Col("COLUMN_SIZE", "INTEGER"),
            new Col("BUFFER_LENGTH", "INTEGER"),
            new Col("DECIMAL_DIGITS", "INTEGER"),
            new Col("PSEUDO_COLUMN", "INTEGER")));

    private static final StmtResult UDTS_RESULT = emptyMetadataResult(List.of(
            new Col("TYPE_CAT", "TEXT"),
            new Col("TYPE_SCHEM", "TEXT"),
            new Col("TYPE_NAME", "TEXT"),
            new Col("CLASS_NAME", "TEXT"),
            new Col("DATA_TYPE", "INTEGER"),
            new Col("REMARKS", "TEXT"),
            new Col("BASE_TYPE", "INTEGER")));

    private static StmtResult emptyMetadataResult(List<Col> columns) {
        return new StmtResult(columns, List.of(), 0, null);
    }

    private final LibsqlConnection connection;
    private String cachedDatabaseProductVersion;

    public LibsqlDatabaseMetaData(LibsqlConnection connection) {
        this.connection = connection;
    }

    private String resolveDatabaseProductVersion() throws SQLException {
        connection.checkClosed();
        if (cachedDatabaseProductVersion == null) {
            try (var statement = connection.createStatement();
                    var rs = statement.executeQuery("SELECT sqlite_version()")) {
                if (!rs.next()) {
                    throw new SQLException("SELECT sqlite_version() returned no row");
                }
                cachedDatabaseProductVersion = rs.getString(1);
            }
        }
        return cachedDatabaseProductVersion;
    }

    @Override
    public boolean allProceduresAreCallable() throws SQLException { return false; }
    @Override
    public boolean allTablesAreSelectable() throws SQLException { return true; }
    @Override
    public String getURL() throws SQLException {
        return connection.getConfig() != null ? connection.getConfig().url() : null;
    }
    @Override
    public String getUserName() throws SQLException { return null; }
    @Override
    public boolean isReadOnly() throws SQLException { return connection.isReadOnly(); }
    @Override
    public boolean nullsAreSortedHigh() throws SQLException { return true; }
    @Override
    public boolean nullsAreSortedLow() throws SQLException { return false; }
    @Override
    public boolean nullsAreSortedAtStart() throws SQLException { return false; }
    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException { return false; }
    @Override
    public String getDatabaseProductName() throws SQLException { return "SQLite (libSQL)"; }
    @Override
    public String getDatabaseProductVersion() throws SQLException { return resolveDatabaseProductVersion(); }
    @Override
    public String getDriverName() throws SQLException { return "libSQL JDBC Driver"; }
    @Override
    public String getDriverVersion() throws SQLException { return "0.1.0"; }
    @Override
    public int getDriverMajorVersion() { return 0; }
    @Override
    public int getDriverMinorVersion() { return 1; }
    @Override
    public boolean usesLocalFiles() throws SQLException { return false; }
    @Override
    public boolean usesLocalFilePerTable() throws SQLException { return false; }
    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException { return false; }
    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException { return false; }
    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException { return false; }
    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException { return true; }
    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException { return false; }
    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException { return false; }
    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException { return false; }
    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException { return true; }
    @Override
    public String getIdentifierQuoteString() throws SQLException { return "\""; }
    @Override
    public String getSQLKeywords() throws SQLException { return ""; }
    @Override
    public String getNumericFunctions() throws SQLException { return ""; }
    @Override
    public String getStringFunctions() throws SQLException { return ""; }
    @Override
    public String getSystemFunctions() throws SQLException { return ""; }
    @Override
    public String getTimeDateFunctions() throws SQLException { return ""; }
    @Override
    public String getSearchStringEscape() throws SQLException { return "\\"; }
    @Override
    public String getExtraNameCharacters() throws SQLException { return ""; }
    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException { return true; }
    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException { return true; }
    @Override
    public boolean supportsColumnAliasing() throws SQLException { return true; }
    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException { return true; }
    @Override
    public boolean supportsConvert() throws SQLException { return false; }
    @Override
    public boolean supportsConvert(int fromType, int toType) throws SQLException { return false; }
    @Override
    public boolean supportsTableCorrelationNames() throws SQLException { return true; }
    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException { return false; }
    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException { return true; }
    @Override
    public boolean supportsOrderByUnrelated() throws SQLException { return true; }
    @Override
    public boolean supportsGroupBy() throws SQLException { return true; }
    @Override
    public boolean supportsGroupByUnrelated() throws SQLException { return true; }
    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException { return true; }
    @Override
    public boolean supportsLikeEscapeClause() throws SQLException { return true; }
    @Override
    public boolean supportsMultipleResultSets() throws SQLException { return false; }
    @Override
    public boolean supportsMultipleTransactions() throws SQLException { return true; }
    @Override
    public boolean supportsNonNullableColumns() throws SQLException { return true; }
    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException { return true; }
    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException { return true; }
    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException { return false; }
    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException { return true; }
    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException { return false; }
    @Override
    public boolean supportsANSI92FullSQL() throws SQLException { return false; }
    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException { return false; }
    @Override
    public boolean supportsOuterJoins() throws SQLException { return true; }
    @Override
    public boolean supportsFullOuterJoins() throws SQLException { return false; }
    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException { return true; }
    @Override
    public String getSchemaTerm() throws SQLException { return "schema"; }
    @Override
    public String getProcedureTerm() throws SQLException { return "procedure"; }
    @Override
    public String getCatalogTerm() throws SQLException { return "catalog"; }
    @Override
    public boolean isCatalogAtStart() throws SQLException { return true; }
    @Override
    public String getCatalogSeparator() throws SQLException { return "."; }
    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException { return false; }
    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException { return false; }
    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException { return false; }
    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException { return false; }
    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException { return false; }
    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException { return false; }
    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException { return false; }
    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException { return false; }
    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException { return false; }
    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException { return false; }
    @Override
    public boolean supportsPositionedDelete() throws SQLException { return false; }
    @Override
    public boolean supportsPositionedUpdate() throws SQLException { return false; }
    @Override
    public boolean supportsSelectForUpdate() throws SQLException { return false; }
    @Override
    public boolean supportsStoredProcedures() throws SQLException { return false; }
    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException { return true; }
    @Override
    public boolean supportsSubqueriesInExists() throws SQLException { return true; }
    @Override
    public boolean supportsSubqueriesInIns() throws SQLException { return true; }
    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException { return false; }
    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException { return true; }
    @Override
    public boolean supportsUnion() throws SQLException { return true; }
    @Override
    public boolean supportsUnionAll() throws SQLException { return true; }
    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException { return false; }
    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException { return false; }
    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException { return true; }
    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException { return true; }
    @Override
    public int getMaxBinaryLiteralLength() throws SQLException { return 0; }
    @Override
    public int getMaxCharLiteralLength() throws SQLException { return 0; }
    @Override
    public int getMaxColumnNameLength() throws SQLException { return 0; }
    @Override
    public int getMaxColumnsInGroupBy() throws SQLException { return 0; }
    @Override
    public int getMaxColumnsInIndex() throws SQLException { return 0; }
    @Override
    public int getMaxColumnsInOrderBy() throws SQLException { return 0; }
    @Override
    public int getMaxColumnsInSelect() throws SQLException { return 0; }
    @Override
    public int getMaxColumnsInTable() throws SQLException { return 0; }
    @Override
    public int getMaxConnections() throws SQLException { return 0; }
    @Override
    public int getMaxCursorNameLength() throws SQLException { return 0; }
    @Override
    public int getMaxIndexLength() throws SQLException { return 0; }
    @Override
    public int getMaxSchemaNameLength() throws SQLException { return 0; }
    @Override
    public int getMaxProcedureNameLength() throws SQLException { return 0; }
    @Override
    public int getMaxCatalogNameLength() throws SQLException { return 0; }
    @Override
    public int getMaxRowSize() throws SQLException { return 0; }
    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException { return false; }
    @Override
    public int getMaxStatementLength() throws SQLException { return 0; }
    @Override
    public int getMaxStatements() throws SQLException { return 0; }
    @Override
    public int getMaxTableNameLength() throws SQLException { return 0; }
    @Override
    public int getMaxTablesInSelect() throws SQLException { return 0; }
    @Override
    public int getMaxUserNameLength() throws SQLException { return 0; }
    @Override
    public int getDefaultTransactionIsolation() throws SQLException { return Connection.TRANSACTION_SERIALIZABLE; }
    @Override
    public boolean supportsTransactions() throws SQLException { return true; }
    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        return level == Connection.TRANSACTION_SERIALIZABLE || level == Connection.TRANSACTION_READ_COMMITTED;
    }
    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException { return true; }
    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException { return false; }
    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException { return false; }
    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException { return false; }

    private ResultSet executeMetadataQuery(String sql) throws SQLException {
        return executeMetadataQuery(sql, sql, result -> result);
    }

    private ResultSet executeMetadataQuery(
            String sql, String originSql, UnaryOperator<StmtResult> transform) throws SQLException {
        LibsqlStatement statement = connection.createStatementInternal();
        try {
            StmtResult result = transform.apply(connection.executeStatement(new Stmt(sql)));
            LibsqlResultSet resultSet = new LibsqlResultSet(statement, result, originSql, true);
            statement.assignResultSet(resultSet, true);
            return resultSet;
        } catch (SQLException e) {
            statement.close();
            throw e;
        }
    }

    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        String pattern = tableNamePattern == null || tableNamePattern.isEmpty()
                ? "%"
                : escapeLiteral(tableNamePattern);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, NAME AS TABLE_NAME, TYPE AS TABLE_TYPE, ")
                .append("NULL AS REMARKS, NULL AS TYPE_CAT, NULL AS TYPE_SCHEM, NULL AS TYPE_NAME, ")
                .append("NULL AS SELF_REFERENCING_COL_NAME, NULL AS REF_GENERATION FROM (")
                .append("SELECT 'sqlite_schema' AS NAME, 'SYSTEM TABLE' AS TYPE")
                .append(" UNION ALL SELECT NAME, UPPER(TYPE) AS TYPE FROM sqlite_schema")
                .append(" WHERE NAME NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND UPPER(TYPE) IN ('TABLE', 'VIEW')")
                .append(" UNION ALL SELECT NAME, 'GLOBAL TEMPORARY' AS TYPE FROM sqlite_temp_master")
                .append(" UNION ALL SELECT NAME, 'SYSTEM TABLE' AS TYPE FROM sqlite_schema")
                .append(" WHERE NAME LIKE 'sqlite\\_%' ESCAPE '\\'")
                .append(") WHERE TABLE_NAME LIKE '").append(pattern).append("' ESCAPE '\\'");
        if (types != null && types.length != 0) {
            sql.append(" AND TABLE_TYPE IN (");
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    sql.append(",");
                }
                sql.append("'").append(escapeLiteral(types[i].toUpperCase())).append("'");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY TABLE_TYPE, TABLE_NAME");
        return executeMetadataQuery(sql.toString());
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        String tablePattern = tableNamePattern == null || tableNamePattern.isEmpty() ? "%" : tableNamePattern;
        String columnPattern = columnNamePattern == null || columnNamePattern.isEmpty() ? "%" : columnNamePattern;
        String sql = "WITH t(TABLE_NAME, CREATE_SQL, TABLE_RANK) AS ("
                + " SELECT 'sqlite_schema' AS TABLE_NAME, NULL AS CREATE_SQL, 1 AS TABLE_RANK"
                + " UNION ALL SELECT NAME, SQL, 1 FROM sqlite_schema"
                + " WHERE NAME LIKE 'sqlite\\_%' ESCAPE '\\'"
                + " UNION ALL SELECT NAME, SQL, 2 FROM sqlite_schema"
                + " WHERE NAME NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND UPPER(TYPE) = 'TABLE'"
                + " UNION ALL SELECT NAME, SQL, 3 FROM sqlite_schema"
                + " WHERE NAME NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND UPPER(TYPE) = 'VIEW'"
                + " UNION ALL SELECT NAME, NULL, 0 FROM sqlite_temp_master"
                + ") SELECT t.TABLE_NAME AS TABLE_NAME, t.CREATE_SQL AS CREATE_SQL, p.cid AS CID, "
                + "p.name AS COLUMN_NAME, p.type AS DECLARED_TYPE, p.\"notnull\" AS NOT_NULL, "
                + "p.dflt_value AS COLUMN_DEF, p.pk AS PK, p.hidden AS HIDDEN "
                + "FROM t JOIN pragma_table_xinfo(t.TABLE_NAME) p "
                + "WHERE t.TABLE_NAME LIKE '" + escapeLiteral(tablePattern) + "' ESCAPE '\\'"
                + " AND p.name LIKE '" + escapeLiteral(columnPattern) + "' ESCAPE '\\'"
                + " ORDER BY t.TABLE_RANK, t.TABLE_NAME, p.cid";
        // getColumns synthesizes its column labels, so the raw SQL must not drive column-origin
        // resolution for the result set.
        return executeMetadataQuery(sql, null, LibsqlDatabaseMetaData::columnsResult);
    }

    private static StmtResult columnsResult(StmtResult raw) {
        List<Col> columns = List.of(
                new Col("TABLE_CAT", "TEXT"),
                new Col("TABLE_SCHEM", "TEXT"),
                new Col("TABLE_NAME", "TEXT"),
                new Col("COLUMN_NAME", "TEXT"),
                new Col("DATA_TYPE", "INTEGER"),
                new Col("TYPE_NAME", "TEXT"),
                new Col("COLUMN_SIZE", "INTEGER"),
                new Col("BUFFER_LENGTH", "INTEGER"),
                new Col("DECIMAL_DIGITS", "INTEGER"),
                new Col("NUM_PREC_RADIX", "INTEGER"),
                new Col("NULLABLE", "INTEGER"),
                new Col("REMARKS", "TEXT"),
                new Col("COLUMN_DEF", "TEXT"),
                new Col("SQL_DATA_TYPE", "INTEGER"),
                new Col("SQL_DATETIME_SUB", "INTEGER"),
                new Col("CHAR_OCTET_LENGTH", "INTEGER"),
                new Col("ORDINAL_POSITION", "INTEGER"),
                new Col("IS_NULLABLE", "TEXT"),
                new Col("SCOPE_CATALOG", "TEXT"),
                new Col("SCOPE_SCHEMA", "TEXT"),
                new Col("SCOPE_TABLE", "TEXT"),
                new Col("SOURCE_DATA_TYPE", "INTEGER"),
                new Col("IS_AUTOINCREMENT", "TEXT"),
                new Col("IS_GENERATEDCOLUMN", "TEXT"));
        List<Row> rows = new ArrayList<>();
        if (raw != null && raw.rows() != null) {
            for (Row row : raw.rows()) {
                List<Value> values = row.values();
                String tableName = text(values.get(0));
                String createSql = text(values.get(1));
                long cid = integer(values.get(2));
                String columnName = text(values.get(3));
                String declaredType = text(values.get(4));
                boolean notNull = integer(values.get(5)) != 0;
                String columnDefault = text(values.get(6));
                boolean primaryKey = integer(values.get(7)) != 0;
                long hidden = integer(values.get(8));

                ColumnShape shape = columnShape(declaredType);
                boolean autoIncrement = primaryKey
                        && LibsqlResultSetMetaData.isAutoIncrementColumn(createSql, columnName);
                boolean generated = hidden == 2 || hidden == 3;

                rows.add(new Row(List.of(
                        Value.ofNull(),
                        Value.ofNull(),
                        Value.of(tableName),
                        Value.of(columnName),
                        Value.of(shape.jdbcType()),
                        Value.of(shape.typeName()),
                        Value.of(shape.columnSize()),
                        Value.of(UNKNOWN_COLUMN_SIZE),
                        Value.of(shape.decimalDigits()),
                        Value.of(10),
                        Value.of(notNull ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullable),
                        Value.ofNull(),
                        Value.of(columnDefault),
                        Value.of(0),
                        Value.of(0),
                        Value.of(UNKNOWN_COLUMN_SIZE),
                        Value.of(cid + 1),
                        Value.of(notNull ? "NO" : "YES"),
                        Value.ofNull(),
                        Value.ofNull(),
                        Value.ofNull(),
                        Value.ofNull(),
                        Value.of(autoIncrement ? "YES" : "NO"),
                        Value.of(generated ? "YES" : "NO"))));
            }
        }
        return new StmtResult(columns, rows, 0, null);
    }

    private static ColumnShape columnShape(String declaredType) {
        String typeName = declaredType == null ? "TEXT" : declaredType.toUpperCase(Locale.ENGLISH);
        int jdbcType;
        int decimalDigits = DEFAULT_DECIMAL_DIGITS;
        if (INTEGER_TYPE.matcher(typeName).find()) {
            jdbcType = Types.INTEGER;
            decimalDigits = 0;
        } else if (VARCHAR_TYPE.matcher(typeName).find()) {
            jdbcType = Types.VARCHAR;
            decimalDigits = 0;
        } else if (FLOAT_TYPE.matcher(typeName).find()) {
            jdbcType = Types.FLOAT;
        } else {
            jdbcType = Types.VARCHAR;
        }

        int columnSize = UNKNOWN_COLUMN_SIZE;
        int startOfDimension = typeName.indexOf('(');
        if (startOfDimension > 0) {
            int endOfDimension = typeName.indexOf(')', startOfDimension);
            if (endOfDimension > 0) {
                int dimensionSeparator = typeName.indexOf(',', startOfDimension);
                String integerPart;
                String decimalPart;
                if (dimensionSeparator > 0) {
                    integerPart = typeName.substring(startOfDimension + 1, dimensionSeparator);
                    decimalPart = typeName.substring(dimensionSeparator + 1, endOfDimension);
                } else {
                    integerPart = typeName.substring(startOfDimension + 1, endOfDimension);
                    decimalPart = null;
                }
                try {
                    int integerValue = Integer.parseUnsignedInt(integerPart.trim());
                    if (decimalPart != null) {
                        decimalDigits = Integer.parseUnsignedInt(decimalPart.trim());
                        columnSize = integerValue + decimalDigits;
                    } else {
                        decimalDigits = 0;
                        columnSize = integerValue;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            typeName = typeName.substring(0, startOfDimension).trim();
        }
        return new ColumnShape(jdbcType, typeName, columnSize, decimalDigits);
    }

    private static String text(Value value) {
        return value instanceof Value.Text text ? text.value() : null;
    }

    private static long integer(Value value) {
        return value instanceof Value.Integer integer ? integer.value() : 0L;
    }

    /**
     * Builds a result set from rows the driver synthesizes itself. The statement is created without
     * executing anything, because the JDBC shape is not the shape of the underlying pragma.
     */
    private ResultSet metadataResult(StmtResult result) throws SQLException {
        LibsqlStatement statement = connection.createStatementInternal();
        LibsqlResultSet resultSet = new LibsqlResultSet(statement, result, null, true);
        statement.assignResultSet(resultSet, true);
        return resultSet;
    }

    private static StmtResult primaryKeysResult(StmtResult raw, String table) {
        List<Row> rows = new ArrayList<>();
        if (raw != null && raw.rows() != null) {
            for (Row row : raw.rows()) {
                List<Value> values = row.values();
                rows.add(new Row(List.of(
                        Value.ofNull(),
                        Value.ofNull(),
                        Value.of(table),
                        Value.of(text(values.get(0))),
                        Value.of(integer(values.get(1))),
                        Value.of(primaryKeyName(text(values.get(2)))))));
            }
        }
        // xerial orders getPrimaryKeys by column name, not by the declared key sequence.
        rows.sort(Comparator.comparing(
                row -> text(row.values().get(3)), Comparator.nullsFirst(Comparator.naturalOrder())));
        return new StmtResult(PRIMARY_KEY_COLUMNS, rows, 0, null);
    }

    private static String importedKeysSql(String table) {
        String escapedTable = escapeLiteral(table == null ? "" : table);
        return "SELECT f.id AS FK_ID, f.seq AS KEY_SEQ, f.\"table\" AS PKTABLE_NAME, "
                + "f.\"from\" AS FKCOLUMN_NAME, f.\"to\" AS PKCOLUMN_NAME, "
                + "f.on_update AS UPDATE_RULE, f.on_delete AS DELETE_RULE, "
                + "c.sql AS FK_CREATE_SQL, p.sql AS PK_CREATE_SQL, "
                + "(SELECT pi.name FROM pragma_table_info(f.\"table\") pi WHERE pi.pk = 1) AS PK_FIRST_COLUMN "
                + "FROM pragma_foreign_key_list('" + escapedTable + "') f "
                + "LEFT JOIN sqlite_schema c ON lower(c.name) = lower('" + escapedTable + "') "
                + "LEFT JOIN sqlite_schema p ON lower(p.name) = lower(f.\"table\") "
                + "ORDER BY f.id, f.seq";
    }

    private static String exportedKeysSql(String parentTable, String childTable) {
        String escapedParent = escapeLiteral(parentTable == null ? "" : parentTable);
        StringBuilder sql = new StringBuilder()
                .append("SELECT f.id AS FK_ID, f.seq AS KEY_SEQ, ")
                .append("COALESCE(p.name, f.\"table\") AS PKTABLE_NAME, f.\"from\" AS FKCOLUMN_NAME, ")
                .append("COALESCE(f.\"to\", (SELECT pi.name FROM pragma_table_info(f.\"table\") pi WHERE pi.pk = 1)) AS PKCOLUMN_NAME, ")
                .append("f.on_update AS UPDATE_RULE, f.on_delete AS DELETE_RULE, ")
                .append("m.name AS FKTABLE_NAME, m.sql AS FK_CREATE_SQL, p.sql AS PK_CREATE_SQL, ")
                .append("(SELECT group_concat(pi.name, ',') FROM pragma_table_info(f.\"table\") pi WHERE pi.pk > 0) AS PK_COLUMNS ")
                .append("FROM sqlite_schema m JOIN pragma_foreign_key_list(m.name) f ")
                .append("LEFT JOIN sqlite_schema p ON lower(p.name) = lower(f.\"table\") ")
                .append("WHERE m.type = 'table' AND lower(f.\"table\") = lower('")
                .append(escapedParent)
                .append("')");
        if (childTable != null) {
            sql.append(" AND lower(m.name) = lower('").append(escapeLiteral(childTable)).append("')");
        }
        return sql.append(" ORDER BY m.name, f.id, f.seq").toString();
    }

    private static StmtResult importedKeysResult(
            StmtResult raw, String catalog, String schema, String table) {
        List<ForeignKeyRow> rows = new ArrayList<>();
        if (raw != null && raw.rows() != null) {
            for (Row row : raw.rows()) {
                List<Value> values = row.values();
                long fkId = integer(values.get(0));
                String pkColumn = text(values.get(4));
                if (pkColumn == null) {
                    pkColumn = text(values.get(9));
                }
                rows.add(new ForeignKeyRow(
                        catalog,
                        schema,
                        text(values.get(2)),
                        pkColumn,
                        catalog,
                        schema,
                        table,
                        text(values.get(3)),
                        (int) integer(values.get(1)) + 1,
                        keyRule(text(values.get(5))),
                        keyRule(text(values.get(6))),
                        nameOrEmpty(foreignKeyName(text(values.get(7)), fkId)),
                        nameOrEmpty(primaryKeyName(text(values.get(8))))));
            }
        }
        rows.sort(Comparator.comparing(
                        ForeignKeyRow::pkTable, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(ForeignKeyRow::keySeq));
        return foreignKeyResult(rows);
    }

    private static StmtResult exportedKeysResult(
            StmtResult raw, String pkCatalog, String pkSchema, String fkCatalog, String fkSchema) {
        List<ForeignKeyRow> rows = new ArrayList<>();
        if (raw != null && raw.rows() != null) {
            for (Row row : raw.rows()) {
                List<Value> values = row.values();
                long fkId = integer(values.get(0));
                String pkColumn = text(values.get(4));
                String pkName = primaryKeyName(text(values.get(9)));
                if (!containsColumn(text(values.get(10)), pkColumn)) {
                    pkName = null;
                }
                rows.add(new ForeignKeyRow(
                        pkCatalog,
                        pkSchema,
                        text(values.get(2)),
                        pkColumn,
                        fkCatalog,
                        fkSchema,
                        text(values.get(7)),
                        text(values.get(3)),
                        (int) integer(values.get(1)) + 1,
                        keyRule(text(values.get(5))),
                        keyRule(text(values.get(6))),
                        nameOrEmpty(foreignKeyName(text(values.get(8)), fkId)),
                        nameOrEmpty(pkName)));
            }
        }
        rows.sort(Comparator.comparing(
                        ForeignKeyRow::fkTable, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(ForeignKeyRow::keySeq));
        return foreignKeyResult(rows);
    }

    private static StmtResult foreignKeyResult(List<ForeignKeyRow> rows) {
        List<Row> values = new ArrayList<>(rows.size());
        for (ForeignKeyRow row : rows) {
            values.add(row.toRow());
        }
        return new StmtResult(FOREIGN_KEY_COLUMNS, values, 0, null);
    }

    private static int keyRule(String rule) {
        if (rule == null) {
            return DatabaseMetaData.importedKeyNoAction;
        }
        return switch (rule.toUpperCase(Locale.ENGLISH)) {
            case "CASCADE" -> DatabaseMetaData.importedKeyCascade;
            case "RESTRICT" -> DatabaseMetaData.importedKeyRestrict;
            case "SET NULL" -> DatabaseMetaData.importedKeySetNull;
            case "SET DEFAULT" -> DatabaseMetaData.importedKeySetDefault;
            default -> DatabaseMetaData.importedKeyNoAction;
        };
    }

    private static String primaryKeyName(String createSql) {
        if (createSql == null) {
            return null;
        }
        Matcher matcher = NAMED_PRIMARY_KEY.matcher(createSql);
        return matcher.find() ? unquoteIdentifier(matcher.group(1)) : null;
    }

    private static String foreignKeyName(String createSql, long fkId) {
        if (createSql == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        Matcher matcher = NAMED_FOREIGN_KEY.matcher(createSql);
        while (matcher.find()) {
            names.add(unquoteIdentifier(matcher.group(1)));
        }
        // SQLite numbers foreign keys from the last declaration down, so the parsed names are
        // matched to pragma ids in reverse declaration order.
        int index = names.size() - 1 - (int) fkId;
        return index >= 0 && index < names.size() ? names.get(index) : null;
    }

    private static boolean containsColumn(String columns, String column) {
        if (columns == null || column == null) {
            return false;
        }
        for (String candidate : columns.split(",")) {
            if (candidate.trim().equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    private static String nameOrEmpty(String name) {
        return name == null ? "" : name;
    }

    private static String unquoteIdentifier(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.length() > 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '`' && last == '`')
                    || (first == '[' && last == ']')
                    || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private static Row typeInfoRow(
            String typeName, int dataType, int caseSensitive, int unsigned, int autoIncrement) {
        return new Row(List.of(
                Value.of(typeName),
                Value.of(dataType),
                Value.of(0),
                Value.ofNull(),
                Value.ofNull(),
                Value.ofNull(),
                Value.of(DatabaseMetaData.typeNullable),
                Value.of(caseSensitive),
                Value.of(DatabaseMetaData.typeSearchable),
                Value.of(unsigned),
                Value.of(0),
                Value.of(autoIncrement),
                Value.ofNull(),
                Value.of(0),
                Value.of(0),
                Value.of(0),
                Value.of(0),
                Value.of(10)));
    }

    private record ForeignKeyRow(
            String pkCatalog,
            String pkSchema,
            String pkTable,
            String pkColumn,
            String fkCatalog,
            String fkSchema,
            String fkTable,
            String fkColumn,
            int keySeq,
            int updateRule,
            int deleteRule,
            String fkName,
            String pkName) {

        private Row toRow() {
            return new Row(List.of(
                    Value.of(pkCatalog),
                    Value.of(pkSchema),
                    Value.of(pkTable),
                    Value.of(pkColumn),
                    Value.of(fkCatalog),
                    Value.of(fkSchema),
                    Value.of(fkTable),
                    Value.of(fkColumn),
                    Value.of(keySeq),
                    Value.of(updateRule),
                    Value.of(deleteRule),
                    Value.of(fkName),
                    Value.of(pkName),
                    Value.of(DatabaseMetaData.importedKeyInitiallyDeferred)));
        }
    }

    private record ColumnShape(int jdbcType, String typeName, int columnSize, int decimalDigits) {}

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        String escapedTable = escapeLiteral(table == null ? "" : table);
        String sql = "SELECT p.name AS COLUMN_NAME, p.pk AS KEY_SEQ, s.sql AS CREATE_SQL "
                + "FROM pragma_table_info('" + escapedTable + "') p "
                + "LEFT JOIN sqlite_schema s ON lower(s.name) = lower('" + escapedTable + "') "
                + "WHERE p.pk > 0";
        return executeMetadataQuery(sql, null, raw -> primaryKeysResult(raw, table));
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return executeMetadataQuery(
                "SELECT 'TABLE' AS TABLE_TYPE "
                        + "UNION SELECT 'VIEW' AS TABLE_TYPE "
                        + "UNION SELECT 'SYSTEM TABLE' AS TABLE_TYPE "
                        + "UNION SELECT 'GLOBAL TEMPORARY' AS TABLE_TYPE");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    @Override public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException { return metadataResult(PROCEDURES_RESULT); }
    @Override public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException { return metadataResult(PROCEDURE_COLUMNS_RESULT); }
    @Override public ResultSet getSchemas() throws SQLException { return executeMetadataQuery("SELECT NULL AS TABLE_SCHEM, NULL AS TABLE_CATALOG WHERE 0"); }
    @Override public ResultSet getCatalogs() throws SQLException { return executeMetadataQuery("SELECT NULL AS TABLE_CAT WHERE 0"); }
    @Override public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException { return metadataResult(COLUMN_PRIVILEGES_RESULT); }
    @Override public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException { return metadataResult(TABLE_PRIVILEGES_RESULT); }
    @Override public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException { return metadataResult(BEST_ROW_IDENTIFIER_RESULT); }
    @Override public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException { return metadataResult(VERSION_COLUMNS_RESULT); }
    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return executeMetadataQuery(
                importedKeysSql(table), null, raw -> importedKeysResult(raw, catalog, schema, table));
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return executeMetadataQuery(
                exportedKeysSql(table, null), null, raw -> exportedKeysResult(raw, catalog, schema, catalog, schema));
    }

    @Override
    public ResultSet getCrossReference(
            String parentCatalog,
            String parentSchema,
            String parentTable,
            String foreignCatalog,
            String foreignSchema,
            String foreignTable)
            throws SQLException {
        if (requestsNamespace(parentCatalog)
                || requestsNamespace(parentSchema)
                || requestsNamespace(foreignCatalog)
                || requestsNamespace(foreignSchema)) {
            // The server reports no catalog and no schema, so a specific request matches nothing.
            return metadataResult(emptyMetadataResult(FOREIGN_KEY_COLUMNS));
        }
        if (parentTable == null) {
            return getExportedKeys(foreignCatalog, foreignSchema, foreignTable);
        }
        if (foreignTable == null) {
            return getImportedKeys(parentCatalog, parentSchema, parentTable);
        }
        return executeMetadataQuery(
                exportedKeysSql(parentTable, foreignTable),
                null,
                raw -> exportedKeysResult(raw, parentCatalog, parentSchema, foreignCatalog, foreignSchema));
    }

    private static boolean requestsNamespace(String name) {
        return name != null && !name.isEmpty();
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        return metadataResult(TYPE_INFO_RESULT);
    }
    @Override public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        String escapedTable = escapeLiteral(table == null ? "" : table);
        String sql = "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, '" + escapedTable + "' AS TABLE_NAME, " +
                "CASE il.\"unique\" WHEN 1 THEN 0 ELSE 1 END AS NON_UNIQUE, " +
                "NULL AS INDEX_QUALIFIER, il.name AS INDEX_NAME, 3 AS TYPE, " +
                "ii.seqno + 1 AS ORDINAL_POSITION, ii.name AS COLUMN_NAME, " +
                "'A' AS ASC_OR_DESC, 0 AS CARDINALITY, 0 AS PAGES, NULL AS FILTER_CONDITION " +
                "FROM pragma_index_list('" + escapedTable + "') il, pragma_index_info(il.name) ii";
        if (unique) {
            sql += " WHERE il.\"unique\" = 1";
        }
        sql += " ORDER BY NON_UNIQUE, TYPE, INDEX_NAME, ORDINAL_POSITION";
        return executeMetadataQuery(sql);
    }
    @Override public boolean supportsResultSetType(int type) throws SQLException { return type == ResultSet.TYPE_FORWARD_ONLY; }
    @Override public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException { return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY; }
    @Override public boolean ownUpdatesAreVisible(int type) throws SQLException { return false; }
    @Override public boolean ownDeletesAreVisible(int type) throws SQLException { return false; }
    @Override public boolean ownInsertsAreVisible(int type) throws SQLException { return false; }
    @Override public boolean othersUpdatesAreVisible(int type) throws SQLException { return false; }
    @Override public boolean othersDeletesAreVisible(int type) throws SQLException { return false; }
    @Override public boolean othersInsertsAreVisible(int type) throws SQLException { return false; }
    @Override public boolean updatesAreDetected(int type) throws SQLException { return false; }
    @Override public boolean deletesAreDetected(int type) throws SQLException { return false; }
    @Override public boolean insertsAreDetected(int type) throws SQLException { return false; }
    @Override public boolean supportsBatchUpdates() throws SQLException { return true; }
    @Override public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException { return metadataResult(UDTS_RESULT); }
    @Override public boolean supportsSavepoints() throws SQLException { return false; }
    @Override public boolean supportsNamedParameters() throws SQLException { return false; }
    @Override public boolean supportsMultipleOpenResults() throws SQLException { return false; }
    @Override public boolean supportsGetGeneratedKeys() throws SQLException { return true; }
    @Override public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public boolean supportsResultSetHoldability(int holdability) throws SQLException { return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int getResultSetHoldability() throws SQLException { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int getDatabaseMajorVersion() throws SQLException {
        String[] parts = resolveDatabaseProductVersion().split("\\.");
        try { return Integer.parseInt(parts[0]); } catch (NumberFormatException e) { return 3; }
    }
    @Override public int getDatabaseMinorVersion() throws SQLException {
        String[] parts = resolveDatabaseProductVersion().split("\\.");
        try { return parts.length > 1 ? Integer.parseInt(parts[1]) : 0; } catch (NumberFormatException e) { return 0; }
    }
    @Override public int getJDBCMajorVersion() throws SQLException { return 4; }
    @Override public int getJDBCMinorVersion() throws SQLException { return 3; }
    @Override public int getSQLStateType() throws SQLException { return sqlStateSQL; }
    @Override public boolean locatorsUpdateCopy() throws SQLException { return false; }
    @Override public boolean supportsStatementPooling() throws SQLException { return false; }
    @Override public RowIdLifetime getRowIdLifetime() throws SQLException { return RowIdLifetime.ROWID_UNSUPPORTED; }
    @Override public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException { return getSchemas(); }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException { return false; }
    @Override public boolean autoCommitFailureClosesAllResultSets() throws SQLException { return false; }
    @Override public ResultSet getClientInfoProperties() throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException { throw new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver"); }
    @Override public boolean generatedKeyAlwaysReturned() throws SQLException { return false; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
}
