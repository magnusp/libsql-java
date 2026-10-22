package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
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
        return info != null && info.primaryKey() && isAutoIncrementColumn(table.createSql(), columnName(column));
    }

    static boolean isAutoIncrementColumn(String createSql, String columnName) {
        if (createSql == null || columnName == null) {
            return false;
        }
        List<List<Token>> definitions = tableDefinitions(createSql);
        if (definitions == null) {
            return false;
        }
        for (List<Token> definition : definitions) {
            if (columnDefinitionIsAutoIncrement(definition, columnName)
                    || tableConstraintIsAutoIncrement(definition, columnName)) {
                return true;
            }
        }
        return false;
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
        String typeName = getColumnTypeName(column);
        return "NUMERIC".equals(typeName) || "INTEGER".equals(typeName) || "REAL".equals(typeName);
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
        SampleType valueType = storageType(value);

        if (valueType == SampleType.INTEGER || valueType == SampleType.NULL) {
            if ("BOOLEAN".equals(typeName)) {
                return Types.BOOLEAN;
            }
            if ("TINYINT".equals(typeName)) {
                return Types.TINYINT;
            }
            if ("SMALLINT".equals(typeName) || "INT2".equals(typeName)) {
                return Types.SMALLINT;
            }
            if ("BIGINT".equals(typeName)
                    || "INT8".equals(typeName)
                    || "UNSIGNED BIG INT".equals(typeName)) {
                return Types.BIGINT;
            }
            if ("DATE".equals(typeName)) {
                return Types.DATE;
            }
            if ("DATETIME".equals(typeName) || "TIMESTAMP".equals(typeName)) {
                return Types.TIMESTAMP;
            }
            if (valueType == SampleType.INTEGER
                    || "INT".equals(typeName)
                    || "INTEGER".equals(typeName)
                    || "MEDIUMINT".equals(typeName)) {
                long val = integer(value);
                return val > Integer.MAX_VALUE || val < Integer.MIN_VALUE ? Types.BIGINT : Types.INTEGER;
            }
        }

        if (valueType == SampleType.FLOAT || valueType == SampleType.NULL) {
            if ("DECIMAL".equals(typeName)) {
                return Types.DECIMAL;
            }
            if ("DOUBLE".equals(typeName) || "DOUBLE PRECISION".equals(typeName)) {
                return Types.DOUBLE;
            }
            if ("NUMERIC".equals(typeName)) {
                return Types.NUMERIC;
            }
            if ("REAL".equals(typeName)) {
                return Types.REAL;
            }
            if (valueType == SampleType.FLOAT || "FLOAT".equals(typeName)) {
                return Types.FLOAT;
            }
        }

        if (valueType == SampleType.TEXT || valueType == SampleType.NULL) {
            if ("CHARACTER".equals(typeName)
                    || "NCHAR".equals(typeName)
                    || "NATIVE CHARACTER".equals(typeName)
                    || "CHAR".equals(typeName)) {
                return Types.CHAR;
            }
            if ("CLOB".equals(typeName)) {
                return Types.CLOB;
            }
            if ("DATE".equals(typeName)) {
                return Types.DATE;
            }
            if ("DATETIME".equals(typeName) || "TIMESTAMP".equals(typeName)) {
                return Types.TIMESTAMP;
            }
            if (valueType == SampleType.TEXT
                    || "VARCHAR".equals(typeName)
                    || "VARYING CHARACTER".equals(typeName)
                    || "NVARCHAR".equals(typeName)
                    || "TEXT".equals(typeName)) {
                return Types.VARCHAR;
            }
        }

        if (valueType == SampleType.BLOB || valueType == SampleType.NULL) {
            if ("BINARY".equals(typeName)) {
                return Types.BINARY;
            }
            if (valueType == SampleType.BLOB || "BLOB".equals(typeName)) {
                return Types.BLOB;
            }
        }

        return Types.NUMERIC;
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
        return className(runtimeValue(column));
    }

    private static String className(Value value) {
        if (value instanceof Value.Integer i) {
            long val = i.value();
            return val > Integer.MAX_VALUE || val < Integer.MIN_VALUE
                    ? Long.class.getName()
                    : Integer.class.getName();
        }
        if (value instanceof Value.Float) {
            return Double.class.getName();
        }
        if (value instanceof Value.Text) {
            return String.class.getName();
        }
        return Object.class.getName();
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

    private SampleType sampleType(int column) {
        return storageType(sampleValue(column));
    }

    private static SampleType storageType(Value value) {
        if (value instanceof Value.Integer) {
            return SampleType.INTEGER;
        }
        if (value instanceof Value.Float) {
            return SampleType.FLOAT;
        }
        if (value instanceof Value.Blob) {
            return SampleType.BLOB;
        }
        if (value instanceof Value.Text) {
            return SampleType.TEXT;
        }
        return SampleType.NULL;
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
                        new ColumnInfo(integer(values.get(3)) != 0, integer(values.get(4)) != 0));
            }
        }
        return new TableInfo(tableName != null ? tableName : fallbackName, createSql, columns);
    }

    private static List<List<Token>> tableDefinitions(String createSql) {
        List<Token> tokens = tokenize(createSql);
        if (tokens == null) {
            return null;
        }
        int open = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (isSymbol(tokens.get(i), "(")) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            return null;
        }

        List<List<Token>> definitions = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        for (int i = open + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (isSymbol(token, ")") && token.depth() == 0) {
                if (!current.isEmpty()) {
                    definitions.add(current);
                }
                return definitions;
            }
            if (isSymbol(token, ",") && token.depth() == 1) {
                definitions.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(token);
        }
        return null;
    }

    private static boolean columnDefinitionIsAutoIncrement(List<Token> definition, String columnName) {
        int start = skipConstraintName(definition);
        if (start >= definition.size()) {
            return false;
        }
        if (isConstraintKeyword(definition.get(start))) {
            return false;
        }
        String name = identifier(definition.get(start));
        if (name == null || !name.equalsIgnoreCase(columnName)) {
            return false;
        }
        return containsKeyword(definition, start + 1, definition.size(), "AUTOINCREMENT", 1);
    }

    private static boolean tableConstraintIsAutoIncrement(List<Token> definition, String columnName) {
        int start = skipConstraintName(definition);
        if (start + 1 >= definition.size()
                || !isWord(definition.get(start), "PRIMARY")
                || !isWord(definition.get(start + 1), "KEY")) {
            return false;
        }
        int open = -1;
        for (int i = start + 2; i < definition.size(); i++) {
            if (isSymbol(definition.get(i), "(")) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            return false;
        }

        int partStart = open + 1;
        for (int i = open + 1; i < definition.size(); i++) {
            Token token = definition.get(i);
            if ((isSymbol(token, ",") && token.depth() == 2) || (isSymbol(token, ")") && token.depth() == 1)) {
                if (indexedColumnIsAutoIncrement(definition, partStart, i, columnName)) {
                    return true;
                }
                partStart = i + 1;
            }
        }
        return false;
    }

    private static boolean indexedColumnIsAutoIncrement(
            List<Token> definition, int start, int end, String columnName) {
        if (start >= end) {
            return false;
        }
        String name = identifier(definition.get(start));
        return name != null
                && name.equalsIgnoreCase(columnName)
                && containsKeyword(definition, start + 1, end, "AUTOINCREMENT", 2);
    }

    private static int skipConstraintName(List<Token> definition) {
        if (definition.size() >= 2 && isWord(definition.get(0), "CONSTRAINT")) {
            return 2;
        }
        return 0;
    }

    private static boolean isConstraintKeyword(Token token) {
        return isWord(token, "PRIMARY")
                || isWord(token, "UNIQUE")
                || isWord(token, "CHECK")
                || isWord(token, "FOREIGN");
    }

    private static boolean containsKeyword(
            List<Token> tokens, int start, int end, String keyword, int depth) {
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.depth() == depth && isWord(token, keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWord(Token token, String word) {
        return token.type() == TokenType.WORD && token.text().equalsIgnoreCase(word);
    }

    private static boolean isSymbol(Token token, String symbol) {
        return token.type() == TokenType.SYMBOL && token.text().equals(symbol);
    }

    private static String identifier(Token token) {
        return switch (token.type()) {
            case IDENT, WORD -> token.text();
            case SYMBOL -> null;
        };
    }

    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return null;
                }
                i = end + 2;
                continue;
            }
            if (c == '\'') {
                i = skipQuoted(sql, i, c);
                if (i < 0) {
                    return null;
                }
                continue;
            }
            if (c == '"' || c == '`') {
                int end = skipQuoted(sql, i, c);
                if (end < 0) {
                    return null;
                }
                tokens.add(new Token(TokenType.IDENT, unescape(sql.substring(i + 1, end - 1), c), depth));
                i = end;
                continue;
            }
            if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                if (end < 0) {
                    return null;
                }
                tokens.add(new Token(TokenType.IDENT, sql.substring(i + 1, end), depth));
                i = end + 1;
                continue;
            }
            if (c == '(') {
                tokens.add(new Token(TokenType.SYMBOL, "(", depth));
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth == 0) {
                    return null;
                }
                depth--;
                tokens.add(new Token(TokenType.SYMBOL, ")", depth));
                i++;
                continue;
            }
            if (isWordChar(c)) {
                int start = i;
                while (i < sql.length() && isWordChar(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(TokenType.WORD, sql.substring(start, i), depth));
                continue;
            }
            tokens.add(new Token(TokenType.SYMBOL, String.valueOf(c), depth));
            i++;
        }
        if (depth != 0) {
            return null;
        }
        return tokens;
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

    private static String unescape(String content, char quote) {
        String doubled = new String(new char[] {quote, quote});
        return content.replace(doubled, String.valueOf(quote));
    }

    private static boolean isWordChar(char c) {
        return c >= 0x80
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '$';
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

    private enum TokenType {
        WORD,
        IDENT,
        SYMBOL
    }

    private record Token(TokenType type, String text, int depth) {}

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
