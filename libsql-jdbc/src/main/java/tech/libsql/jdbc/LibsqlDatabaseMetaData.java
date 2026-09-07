package tech.libsql.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;

public class LibsqlDatabaseMetaData implements DatabaseMetaData {

    private final LibsqlConnection connection;

    public LibsqlDatabaseMetaData(LibsqlConnection connection) {
        this.connection = connection;
    }

    @Override
    public boolean allProceduresAreCallable() throws SQLException { return false; }
    @Override
    public boolean allTablesAreSelectable() throws SQLException { return true; }
    @Override
    public String getURL() throws SQLException { return null; }
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
    public String getDatabaseProductName() throws SQLException { return "SQLite / libSQL"; }
    @Override
    public String getDatabaseProductVersion() throws SQLException { return "3.0"; }
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

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, name AS TABLE_NAME, UPPER(type) AS TABLE_TYPE, NULL AS REMARKS, NULL AS TYPE_CAT, NULL AS TYPE_SCHEM, NULL AS TYPE_NAME, NULL AS SELF_REFERENCING_COL_NAME, NULL AS REF_GENERATION FROM sqlite_master WHERE 1=1");
        if (tableNamePattern != null && !tableNamePattern.equals("%")) {
            sql.append(" AND name LIKE '").append(tableNamePattern).append("'");
        }
        sql.append(" ORDER BY TABLE_TYPE, TABLE_NAME");
        return connection.createStatement().executeQuery(sql.toString());
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        String sql = "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, m.name AS TABLE_NAME, p.name AS COLUMN_NAME, " +
                "CASE UPPER(p.type) " +
                "  WHEN 'INTEGER' THEN " + java.sql.Types.BIGINT + " " +
                "  WHEN 'INT' THEN " + java.sql.Types.BIGINT + " " +
                "  WHEN 'TEXT' THEN " + java.sql.Types.VARCHAR + " " +
                "  WHEN 'BLOB' THEN " + java.sql.Types.BLOB + " " +
                "  WHEN 'REAL' THEN " + java.sql.Types.DOUBLE + " " +
                "  ELSE " + java.sql.Types.OTHER + " " +
                "END AS DATA_TYPE, " +
                "p.type AS TYPE_NAME, 0 AS COLUMN_SIZE, 0 AS BUFFER_LENGTH, 0 AS DECIMAL_DIGITS, 10 AS NUM_PREC_RADIX, " +
                "CASE p.\"notnull\" WHEN 1 THEN 0 ELSE 1 END AS NULLABLE, " +
                "NULL AS REMARKS, p.dflt_value AS COLUMN_DEF, 0 AS SQL_DATA_TYPE, 0 AS SQL_DATETIME_SUB, 0 AS CHAR_OCTET_LENGTH, " +
                "p.cid + 1 AS ORDINAL_POSITION, " +
                "CASE p.\"notnull\" WHEN 1 THEN 'NO' ELSE 'YES' END AS IS_NULLABLE, " +
                "NULL AS SCOPE_CATALOG, NULL AS SCOPE_SCHEMA, NULL AS SCOPE_TABLE, NULL AS SOURCE_DATA_TYPE, 'NO' AS IS_AUTOINCREMENT, 'NO' AS IS_GENERATEDCOLUMN " +
                "FROM sqlite_master m JOIN pragma_table_info(m.name) p " +
                "WHERE 1=1";
        if (tableNamePattern != null && !tableNamePattern.equals("%")) {
            sql += " AND m.name LIKE '" + tableNamePattern + "'";
        }
        if (columnNamePattern != null && !columnNamePattern.equals("%")) {
            sql += " AND p.name LIKE '" + columnNamePattern + "'";
        }
        sql += " ORDER BY TABLE_NAME, ORDINAL_POSITION";
        return connection.createStatement().executeQuery(sql);
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        String sql = "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, '" + table + "' AS TABLE_NAME, name AS COLUMN_NAME, pk AS KEY_SEQ, NULL AS PK_NAME " +
                "FROM pragma_table_info('" + table + "') WHERE pk > 0 ORDER BY pk";
        return connection.createStatement().executeQuery(sql);
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return connection.createStatement().executeQuery(
                "SELECT 'TABLE' AS TABLE_TYPE UNION SELECT 'VIEW' AS TABLE_TYPE"
        );
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    @Override public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getSchemas() throws SQLException { return connection.createStatement().executeQuery("SELECT NULL AS TABLE_SCHEM, NULL AS TABLE_CATALOG WHERE 0"); }
    @Override public ResultSet getCatalogs() throws SQLException { return connection.createStatement().executeQuery("SELECT NULL AS TABLE_CAT WHERE 0"); }
    @Override public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getTypeInfo() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException { throw new UnsupportedOperationException(); }
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
    @Override public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public boolean supportsSavepoints() throws SQLException { return false; }
    @Override public boolean supportsNamedParameters() throws SQLException { return false; }
    @Override public boolean supportsMultipleOpenResults() throws SQLException { return false; }
    @Override public boolean supportsGetGeneratedKeys() throws SQLException { return false; }
    @Override public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public boolean supportsResultSetHoldability(int holdability) throws SQLException { return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public int getResultSetHoldability() throws SQLException { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public int getDatabaseMajorVersion() throws SQLException { return 3; }
    @Override public int getDatabaseMinorVersion() throws SQLException { return 0; }
    @Override public int getJDBCMajorVersion() throws SQLException { return 4; }
    @Override public int getJDBCMinorVersion() throws SQLException { return 3; }
    @Override public int getSQLStateType() throws SQLException { return sqlStateSQL; }
    @Override public boolean locatorsUpdateCopy() throws SQLException { return false; }
    @Override public boolean supportsStatementPooling() throws SQLException { return false; }
    @Override public RowIdLifetime getRowIdLifetime() throws SQLException { return RowIdLifetime.ROWID_UNSUPPORTED; }
    @Override public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException { return getSchemas(); }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException { return false; }
    @Override public boolean autoCommitFailureClosesAllResultSets() throws SQLException { return false; }
    @Override public ResultSet getClientInfoProperties() throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException { throw new UnsupportedOperationException(); }
    @Override public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException { throw new UnsupportedOperationException(); }
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
