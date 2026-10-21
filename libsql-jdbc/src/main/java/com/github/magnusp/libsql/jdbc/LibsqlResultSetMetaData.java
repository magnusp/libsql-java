package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LibsqlResultSetMetaData implements ResultSetMetaData {

    private static final Pattern COLUMN_TYPECAST =
            Pattern.compile("(?i)cast\\(.*?\\s+as\\s+(.*?)\\s*\\)");
    private static final Pattern COLUMN_PRECISION = Pattern.compile(".*?\\((.*?)\\)");

    private final List<Col> cols;
    private final List<Row> rows;
    private final String sql;
    private final LibsqlConnection connection;

    private boolean tableContextResolved;
    private TableInfo tableContext;

    public LibsqlResultSetMetaData(List<Col> cols) {
        this(cols, List.of(), null, null);
    }

    LibsqlResultSetMetaData(List<Col> cols, List<Row> rows, String sql, LibsqlConnection connection) {
        this.cols = cols != null ? cols : List.of();
        this.rows = rows != null ? rows : List.of();
        this.sql = sql;
        this.connection = connection;
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
        getCol(column);
        TableInfo table = resolveTableContext();
        ColumnInfo info = table != null ? table.columns().get(columnName(column)) : null;
        return info != null
                && info.primaryKey()
                && table.createSql() != null
                && stripSqlCommentsAndLiterals(table.createSql())
                        .toUpperCase(Locale.ENGLISH)
                        .contains("AUTOINCREMENT");
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
        getCol(column);
        TableInfo table = resolveTableContext();
        ColumnInfo info = table != null ? table.columns().get(columnName(column)) : null;
        if (info == null) {
            return columnNullableUnknown;
        }
        return info.notNull() ? columnNoNulls : columnNullable;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return switch (getColumnType(column)) {
            case Types.TINYINT,
                    Types.SMALLINT,
                    Types.INTEGER,
                    Types.BIGINT,
                    Types.NUMERIC,
                    Types.DECIMAL,
                    Types.REAL,
                    Types.FLOAT,
                    Types.DOUBLE -> true;
            default -> false;
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
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1).split(",")[0].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
                try {
                    return Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    @Override
    public String getTableName(int column) throws SQLException {
        getCol(column);
        TableInfo table = resolveTableContext();
        if (table == null || !table.columns().containsKey(columnName(column))) {
            return "";
        }
        return table.name();
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
        Value sample = sampleValue(column);

        return switch (typeName) {
            case "BOOLEAN" -> Types.BOOLEAN;
            case "TINYINT" -> Types.TINYINT;
            case "SMALLINT", "INT2" -> Types.SMALLINT;
            case "BIGINT", "INT8", "UNSIGNED BIG INT" -> Types.BIGINT;
            case "DATE" -> Types.DATE;
            case "DATETIME", "TIMESTAMP" -> Types.TIMESTAMP;
            case "INT", "INTEGER", "MEDIUMINT" -> integerType(column);
            case "DECIMAL" -> Types.DECIMAL;
            case "DOUBLE", "DOUBLE PRECISION" -> Types.DOUBLE;
            case "NUMERIC" -> Types.NUMERIC;
            case "REAL" -> Types.REAL;
            case "FLOAT" -> Types.FLOAT;
            case "CHARACTER", "NCHAR", "NATIVE CHARACTER", "CHAR" -> Types.CHAR;
            case "CLOB" -> Types.CLOB;
            case "VARCHAR", "VARYING CHARACTER", "NVARCHAR", "TEXT" -> Types.VARCHAR;
            case "BINARY" -> Types.BINARY;
            case "BLOB" -> Types.BLOB;
            default -> inferTypeFromValue(sample);
        };
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        getCol(column);
        String declType = declType(column);
        if (declType != null) {
            int paren = declType.indexOf('(');
            String name = (paren >= 0 ? declType.substring(0, paren) : declType)
                    .trim()
                    .toUpperCase(Locale.ENGLISH);
            if (!name.isEmpty()) {
                return name;
            }
        }
        return switch (sampleType(column)) {
            case INTEGER -> "INTEGER";
            case FLOAT -> "FLOAT";
            case BLOB -> "BLOB";
            case TEXT -> "TEXT";
            default -> "NUMERIC";
        };
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
        Value sample = sampleValue(column);
        if (sample != null) {
            return switch (sample) {
                case Value.Integer i -> integerClassName(column);
                case Value.Float f -> Double.class.getName();
                case Value.Text t -> String.class.getName();
                case Value.Blob b -> Object.class.getName();
                case Value.Null n -> Object.class.getName();
            };
        }
        if (!rows.isEmpty()) {
            return Object.class.getName();
        }
        return switch (getColumnType(column)) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> Integer.class.getName();
            case Types.BIGINT -> Long.class.getName();
            case Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> Double.class.getName();
            case Types.CHAR, Types.VARCHAR, Types.CLOB -> String.class.getName();
            case Types.BOOLEAN -> Boolean.class.getName();
            default -> Object.class.getName();
        };
    }

    private String integerClassName(int column) {
        Value firstNonInteger = null;
        boolean outsideIntRange = false;
        for (Row row : rows) {
            List<Value> values = row.values();
            if (values == null || column < 1 || column > values.size()) {
                continue;
            }
            Value value = values.get(column - 1);
            if (value instanceof Value.Integer) {
                if (!isIntInRange(value)) {
                    outsideIntRange = true;
                }
            } else if (value != null
                    && !(value instanceof Value.Null)
                    && firstNonInteger == null) {
                firstNonInteger = value;
            }
        }
        if (firstNonInteger instanceof Value.Float) {
            return Double.class.getName();
        }
        if (firstNonInteger instanceof Value.Text) {
            return String.class.getName();
        }
        if (firstNonInteger != null) {
            return Object.class.getName();
        }
        return outsideIntRange ? Long.class.getName() : Integer.class.getName();
    }

    private String declType(int column) throws SQLException {
        Col col = getCol(column);
        String declType = col.decltype();
        if (declType != null && !declType.isBlank()) {
            return declType;
        }
        String name = col.name();
        if (name != null) {
            Matcher matcher = COLUMN_TYPECAST.matcher(name);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private String columnName(int column) throws SQLException {
        String name = getCol(column).name();
        return name != null ? name.toLowerCase(Locale.ROOT) : "";
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

    private SampleType sampleType(int column) {
        Value sample = sampleValue(column);
        if (sample instanceof Value.Integer) {
            return SampleType.INTEGER;
        }
        if (sample instanceof Value.Float) {
            return SampleType.FLOAT;
        }
        if (sample instanceof Value.Blob) {
            return SampleType.BLOB;
        }
        if (sample instanceof Value.Text) {
            return SampleType.TEXT;
        }
        return SampleType.NULL;
    }

    private static boolean isIntInRange(Value value) {
        return value instanceof Value.Integer i
                && i.value() >= Integer.MIN_VALUE
                && i.value() <= Integer.MAX_VALUE;
    }

    private int integerType(int column) {
        Value firstNonInteger = null;
        boolean outsideIntRange = false;
        for (Row row : rows) {
            List<Value> values = row.values();
            if (values == null || column < 1 || column > values.size()) {
                continue;
            }
            Value value = values.get(column - 1);
            if (value == null || value instanceof Value.Null) {
                continue;
            }
            if (value instanceof Value.Integer) {
                if (!isIntInRange(value)) {
                    outsideIntRange = true;
                }
            } else if (firstNonInteger == null) {
                firstNonInteger = value;
            }
        }
        if (firstNonInteger != null) {
            return inferTypeFromValue(firstNonInteger);
        }
        return outsideIntRange ? Types.BIGINT : Types.INTEGER;
    }

    private static int inferTypeFromValue(Value sample) {
        if (sample instanceof Value.Integer i) {
            return i.value() >= Integer.MIN_VALUE && i.value() <= Integer.MAX_VALUE
                    ? Types.INTEGER
                    : Types.BIGINT;
        }
        if (sample instanceof Value.Float) {
            return Types.FLOAT;
        }
        if (sample instanceof Value.Text) {
            return Types.VARCHAR;
        }
        if (sample instanceof Value.Blob) {
            return Types.BLOB;
        }
        return Types.NUMERIC;
    }

    private TableInfo resolveTableContext() throws SQLException {
        if (!tableContextResolved) {
            TableInfo resolved = null;
            if (connection != null && sql != null) {
                SqlTableReference.TableRef reference = SqlTableReference.parse(sql);
                if (reference != null) {
                    resolved = loadTableInfo(reference);
                }
            }
            tableContext = resolved;
            tableContextResolved = true;
        }
        return tableContext;
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
                + "FROM " + master + " m JOIN pragma_table_info(?, ?) p "
                + "WHERE m.type IN ('table', 'view') AND m.name COLLATE NOCASE = ?";
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
                + "FROM " + quotedSchema + ".sqlite_master m JOIN pragma_table_info(?, ?) p "
                + "WHERE m.type IN ('table', 'view') AND m.name COLLATE NOCASE = ?";
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
        Map<String, ColumnInfo> columns = new HashMap<>();
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
                        new ColumnInfo(integer(values.get(3)) != 0, integer(values.get(4)) != 0));
            }
        }
        return new TableInfo(tableName != null ? tableName : fallbackName, createSql, columns);
    }

    private static String stripSqlCommentsAndLiterals(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                result.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                result.append(' ');
                i = end + 2;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(sql, i, c);
                if (end < 0) {
                    break;
                }
                result.append(' ');
                i = end;
                continue;
            }
            if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                if (end < 0) {
                    break;
                }
                result.append(' ');
                i = end + 1;
                continue;
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    private static String text(Value value) {
        return value instanceof Value.Text t ? t.value() : null;
    }

    private static long integer(Value value) {
        return value instanceof Value.Integer i ? i.value() : 0L;
    }

    private enum SampleType {
        INTEGER,
        FLOAT,
        BLOB,
        TEXT,
        NULL
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
