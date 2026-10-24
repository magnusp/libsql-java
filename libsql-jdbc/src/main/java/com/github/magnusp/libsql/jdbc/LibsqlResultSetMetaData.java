package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LibsqlResultSetMetaData implements ResultSetMetaData {

    private static final Pattern COLUMN_TYPENAME = Pattern.compile("([^\\(]*)");
    private static final Pattern COLUMN_TYPECAST =
            Pattern.compile("cast\\(.*?\\s+as\\s+(.*?)\\s*\\)");
    private static final Pattern COLUMN_PRECISION = Pattern.compile(".*?\\((.*?)\\)");

    private final List<Col> cols;
    private final List<Row> rows;
    private final String sql;
    private final LibsqlConnection connection;
    private final LibsqlResultSet resultSet;

    private boolean tableContextResolved;
    private SqlTableReference.Resolution columnOrigins;
    private final Map<String, TableInfo> tableInfoCache = new LinkedHashMap<>();

    public LibsqlResultSetMetaData(List<Col> cols) {
        this(cols, List.of(), null, null, null);
    }

    LibsqlResultSetMetaData(List<Col> cols, List<Row> rows, String sql, LibsqlConnection connection) {
        this(cols, rows, sql, connection, null);
    }

    LibsqlResultSetMetaData(
            List<Col> cols, List<Row> rows, String sql, LibsqlConnection connection, LibsqlResultSet resultSet) {
        this.cols = cols != null ? cols : List.of();
        this.rows = rows != null ? rows : List.of();
        this.sql = sql;
        this.connection = connection;
        this.resultSet = resultSet;
    }

    @Override
    public int getColumnCount() throws SQLException {
        return cols.size();
    }

    private Col getCol(int column) throws SQLException {
        if (column < 1 || column > cols.size()) {
            throw new SQLException("Invalid column index: " + column + ", must be between 1 and " + cols.size());
        }
        return cols.get(column - 1);
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        TableInfo table = tableForColumn(column);
        ColumnInfo info = table != null ? table.columns().get(columnName(column)) : null;
        return info != null
                && info.primaryKey()
                && SqlAutoIncrement.isAutoIncrementColumn(table.createSql(), columnName(column));
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        getCol(column);
        return true;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        getCol(column);
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        getCol(column);
        return false;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        TableInfo table = tableForColumn(column);
        if (table == null) {
            return columnNullableUnknown;
        }
        ColumnInfo info = table.columns().get(columnName(column));
        if (info == null) {
            return columnNullable;
        }
        return info.notNull() ? columnNoNulls : columnNullable;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        if (declType(column) != null) {
            return switch (getColumnTypeName(column)) {
                case "NUMERIC", "INTEGER", "REAL" -> true;
                default -> false;
            };
        }
        // An expression column has no declared type, so signedness follows the
        // storage class of the current value, as xerial does.
        return switch (XerialColumnType.storageClass(runtimeValue(column))) {
            case INTEGER, FLOAT, NULL -> true;
            case TEXT, BLOB -> false;
        };
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        getCol(column);
        return Integer.MAX_VALUE;
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        String name = getCol(column).name();
        return name != null ? name : "";
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        return getColumnLabel(column);
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        getCol(column);
        return "";
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        String declType = declType(column);
        if (declType == null) {
            return 0;
        }
        Matcher matcher = COLUMN_PRECISION.matcher(declType);
        return matcher.find() ? Integer.parseInt(matcher.group(1).split(",")[0].trim()) : 0;
    }

    @Override
    public int getScale(int column) throws SQLException {
        String declType = declType(column);
        if (declType == null) {
            return 0;
        }
        Matcher matcher = COLUMN_PRECISION.matcher(declType);
        if (matcher.find()) {
            String[] parts = matcher.group(1).split(",");
            if (parts.length == 2) {
                return Integer.parseInt(parts[1].trim());
            }
        }
        return 0;
    }

    @Override
    public String getTableName(int column) throws SQLException {
        TableInfo table = tableForColumn(column);
        return table != null ? table.name() : "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        getCol(column);
        return "";
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        getCol(column);
        String typeName = getColumnTypeName(column);
        Value value = runtimeValue(column);
        return XerialColumnType.jdbcType(value, typeName);
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        getCol(column);
        String declType = declType(column);
        if (declType != null) {
            Matcher matcher = COLUMN_TYPENAME.matcher(declType);
            matcher.find();
            return matcher.group(1).toUpperCase(Locale.ENGLISH);
        }
        return "NUMERIC";
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        getCol(column);
        return false;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        getCol(column);
        return true;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        getCol(column);
        return true;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        getCol(column);
        return XerialColumnType.className(runtimeValue(column));
    }

    private String declType(int column) throws SQLException {
        Col col = getCol(column);
        String declType = col.decltype();
        if (declType == null) {
            String name = col.name();
            if (name != null) {
                Matcher matcher = COLUMN_TYPECAST.matcher(name);
                declType = matcher.find() ? matcher.group(1) : null;
            }
        }
        return declType;
    }

    private String columnName(int column) throws SQLException {
        String name = getCol(column).name();
        return name != null ? name.toLowerCase(Locale.ROOT) : "";
    }

    private Value runtimeValue(int column) {
        if (resultSet != null) {
            return resultSet.valueForMetadata(column);
        }
        return sampleValue(column);
    }

    private Value sampleValue(int column) {
        for (Row row : rows) {
            List<Value> values = row.values();
            if (values == null || column < 1 || column > values.size()) {
                continue;
            }
            Value value = values.get(column - 1);
            if (value != null && !(value instanceof Value.Null)) {
                return value;
            }
        }
        return null;
    }

    private TableInfo tableForColumn(int column) throws SQLException {
        getCol(column);
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
                }, cols.size());
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

    private record ColumnInfo(boolean notNull, boolean primaryKey) {}

    private record TableInfo(String name, String createSql, Map<String, ColumnInfo> columns) {}

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
