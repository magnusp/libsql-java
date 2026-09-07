package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

public class LibsqlResultSetMetaData implements ResultSetMetaData {

    private final List<Col> cols;

    public LibsqlResultSetMetaData(List<Col> cols) {
        this.cols = cols != null ? cols : List.of();
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
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        return false;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return columnNullableUnknown;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        int type = getColumnType(column);
        return type == Types.INTEGER || type == Types.BIGINT || type == Types.FLOAT || type == Types.DOUBLE;
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return 50;
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
        return "";
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return 0;
    }

    @Override
    public int getScale(int column) throws SQLException {
        return 0;
    }

    @Override
    public String getTableName(int column) throws SQLException {
        return "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        return "";
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        String decltype = getCol(column).decltype();
        if (decltype == null) return Types.OTHER;
        String upper = decltype.toUpperCase();
        if (upper.contains("INT")) return Types.BIGINT;
        if (upper.contains("CHAR") || upper.contains("TEXT") || upper.contains("CLOB")) return Types.VARCHAR;
        if (upper.contains("BLOB")) return Types.BLOB;
        if (upper.contains("REAL") || upper.contains("FLOA") || upper.contains("DOUB")) return Types.DOUBLE;
        if (upper.contains("NUM") || upper.contains("DEC")) return Types.NUMERIC;
        if (upper.contains("BOOL")) return Types.BOOLEAN;
        return Types.OTHER;
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        String decltype = getCol(column).decltype();
        return decltype != null ? decltype : "OTHER";
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        return true;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return switch (getColumnType(column)) {
            case Types.BIGINT -> Long.class.getName();
            case Types.DOUBLE -> Double.class.getName();
            case Types.VARCHAR -> String.class.getName();
            case Types.BLOB -> byte[].class.getName();
            case Types.BOOLEAN -> Boolean.class.getName();
            default -> Object.class.getName();
        };
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
