package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class TableMetadataResolver {

    private final LibsqlConnection connection;
    private final String sql;
    private final int columnCount;
    private final Map<String, TableInfo> tableInfoCache = new LinkedHashMap<>();

    private boolean tableContextResolved;
    private SqlTableReference.Resolution columnOrigins;

    TableMetadataResolver(LibsqlConnection connection, String sql, int columnCount) {
        this.connection = connection;
        this.sql = sql;
        this.columnCount = columnCount;
    }

    TableInfo tableForColumn(int column) throws SQLException {
        if (column < 1 || column > columnCount) {
            throw new SQLException("Invalid column index: " + column + ", must be between 1 and " + columnCount);
        }
        SqlTableReference.TableRef reference = originTable(column);
        return reference != null ? tableInfo(reference) : null;
    }

    private SqlTableReference.TableRef originTable(int column) throws SQLException {
        SqlTableReference.Resolution resolution = resolveColumnOrigins();
        return resolution != null ? resolution.table(column - 1) : null;
    }

    private SqlTableReference.Resolution resolveColumnOrigins() throws SQLException {
        if (!tableContextResolved) {
            SqlTableReference.Resolution resolved = null;
            if (connection != null && sql != null) {
                resolved = SqlTableReference.resolve(sql, reference -> {
                    TableInfo info = tableInfo(reference);
                    if (info == null) {
                        return null;
                    }
                    return new SqlTableReference.TableDefinition(
                            info.createSql(), new ArrayList<>(info.columns().keySet()));
                }, columnCount);
            }
            columnOrigins = resolved;
            tableContextResolved = true;
        }
        return columnOrigins;
    }

    private TableInfo tableInfo(SqlTableReference.TableRef reference) throws SQLException {
        String schema = reference.schema() != null ? reference.schema().toLowerCase(Locale.ROOT) : "";
        String table = reference.table() != null ? reference.table().toLowerCase(Locale.ROOT) : "";
        String key = schema + "." + table;
        if (tableInfoCache.containsKey(key)) {
            return tableInfoCache.get(key);
        }
        TableInfo info = loadTableInfo(reference);
        tableInfoCache.put(key, info);
        return info;
    }

    private TableInfo loadTableInfo(SqlTableReference.TableRef reference) throws SQLException {
        String schema = reference.schema();
        String table = reference.table();
        if (schema == null) {
            TableInfo temp = loadTableInfo("temp", table);
            if (temp != null) {
                return temp;
            }
            return loadTableInfo("main", table);
        }
        return switch (schema.toLowerCase(Locale.ROOT)) {
            case "main" -> loadTableInfo("main", table);
            case "temp" -> loadTableInfo("temp", table);
            default -> loadAttachedTableInfo(schema, table);
        };
    }

    private TableInfo loadTableInfo(String schema, String table) throws SQLException {
        String master = schema.equals("temp") ? "sqlite_temp_master" : "sqlite_master";
        String query = "SELECT m.name, m.sql, p.name, p.\"notnull\", p.pk "
                + "FROM " + master + " m JOIN pragma_table_xinfo(?, ?) p "
                + "WHERE m.type IN ('table', 'view') AND m.name COLLATE NOCASE = ? "
                + "AND p.hidden != 1 "
                + "ORDER BY p.cid";
        StmtResult result = connection.executeStatement(
                new Stmt(query, List.of(Value.of(table), Value.of(schema), Value.of(table))));
        return toTableInfo(result, table);
    }

    private TableInfo loadAttachedTableInfo(String schema, String table) throws SQLException {
        String actualSchema = findSchema(schema);
        if (actualSchema == null) {
            return null;
        }
        String quotedSchema = "\"" + actualSchema.replace("\"", "\"\"") + "\"";
        String query = "SELECT m.name, m.sql, p.name, p.\"notnull\", p.pk "
                + "FROM " + quotedSchema + ".sqlite_master m JOIN pragma_table_xinfo(?, ?) p "
                + "WHERE m.type IN ('table', 'view') AND m.name COLLATE NOCASE = ? "
                + "AND p.hidden != 1 "
                + "ORDER BY p.cid";
        StmtResult result = connection.executeStatement(new Stmt(
                query, List.of(Value.of(table), Value.of(actualSchema), Value.of(table))));
        return toTableInfo(result, table);
    }

    private String findSchema(String schema) throws SQLException {
        StmtResult databases =
                connection.executeStatement(new Stmt("SELECT name FROM pragma_database_list"));
        if (databases == null || databases.rows() == null) {
            return null;
        }
        for (Row row : databases.rows()) {
            List<Value> values = row.values();
            if (values == null || values.isEmpty()) {
                continue;
            }
            String name = text(values.get(0));
            if (name != null && name.equalsIgnoreCase(schema)) {
                return name;
            }
        }
        return null;
    }

    private static TableInfo toTableInfo(StmtResult result, String fallbackName) {
        if (result == null || result.rows() == null || result.rows().isEmpty()) {
            return null;
        }
        String tableName = null;
        String createSql = null;
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        for (Row row : result.rows()) {
            List<Value> values = row.values();
            if (values == null || values.size() < 5) {
                continue;
            }
            if (tableName == null) {
                tableName = text(values.get(0));
                createSql = text(values.get(1));
            }
            String columnName = text(values.get(2));
            if (columnName != null) {
                columns.put(
                        columnName.toLowerCase(Locale.ROOT),
                        new ColumnInfo(
                                XerialColumnType.integer(values.get(3)) != 0,
                                XerialColumnType.integer(values.get(4)) != 0));
            }
        }
        return new TableInfo(tableName != null ? tableName : fallbackName, createSql, columns);
    }

    private static String text(Value value) {
        return value instanceof Value.Text t ? t.value() : null;
    }

    record ColumnInfo(boolean notNull, boolean primaryKey) {}

    record TableInfo(String name, String createSql, Map<String, ColumnInfo> columns) {}
}
