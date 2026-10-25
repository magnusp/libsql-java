package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.Row;
import io.github.magnusp.libsql.hrana.codec.Value;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

public class LibsqlResultSetMetaData implements ResultSetMetaData {

    private final List<Col> cols;
    private final List<Row> rows;
    private final String sql;
    private final LibsqlConnection connection;
    private final LibsqlResultSet resultSet;
    private final TableMetadataResolver tableMetadata;
    private final JdbcTypeMapping.JdbcType[] declaredTypes;

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
        this.tableMetadata = new TableMetadataResolver(connection, sql, this.cols.size());
        this.declaredTypes =
                resultSet != null ? resultSet.declaredTypes() : JdbcTypeMapping.declaredTypes(this.cols);
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
        TableMetadataResolver.TableInfo table = tableMetadata.tableForColumn(column);
        TableMetadataResolver.ColumnInfo info =
                table != null ? table.columns().get(columnName(column)) : null;
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
        TableMetadataResolver.TableInfo table = tableMetadata.tableForColumn(column);
        if (table == null) {
            return columnNullableUnknown;
        }
        TableMetadataResolver.ColumnInfo info = table.columns().get(columnName(column));
        if (info == null) {
            return columnNullable;
        }
        return info.notNull() ? columnNoNulls : columnNullable;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        DeclaredType declared = DeclaredType.of(getCol(column));
        if (declared.isDeclared()) {
            return switch (declared.typeName()) {
                case "NUMERIC", "INTEGER", "REAL" -> true;
                default -> false;
            };
        }
        // An expression column has no declared type, so signedness follows the
        // storage class of the current value, as xerial does.
        return switch (JdbcTypeMapping.storageClass(runtimeValue(column))) {
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
        return DeclaredType.of(getCol(column)).precision();
    }

    @Override
    public int getScale(int column) throws SQLException {
        return DeclaredType.of(getCol(column)).scale();
    }

    @Override
    public String getTableName(int column) throws SQLException {
        TableMetadataResolver.TableInfo table = tableMetadata.tableForColumn(column);
        return table != null ? table.name() : "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        getCol(column);
        return "";
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return resolvedType(column).sqlType();
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return DeclaredType.of(getCol(column)).typeName();
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
        return resolvedType(column).objectClass().getName();
    }

    private JdbcTypeMapping.JdbcType resolvedType(int column) throws SQLException {
        getCol(column);
        JdbcTypeMapping.JdbcType declared = declaredTypes[column - 1];
        return declared != null ? declared : JdbcTypeMapping.fallback(runtimeValue(column));
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
