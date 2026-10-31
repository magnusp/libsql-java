package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.Row;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.Value;

import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shapes {@code pragma_table_xinfo} rows into the 24-column JDBC {@link DatabaseMetaData#getColumns}
 * shape. The pragma select list is positional by contract; from {@link PragmaRow} onward every value
 * has a name.
 */
final class ColumnMetadata {

    private static final Pattern INTEGER_TYPE = Pattern.compile(".*(INT|BOOL).*");
    private static final Pattern VARCHAR_TYPE = Pattern.compile(".*(CHAR|CLOB|TEXT|BLOB).*");
    private static final Pattern FLOAT_TYPE = Pattern.compile(".*(REAL|FLOA|DOUB|DEC|NUM).*");
    private static final int UNKNOWN_COLUMN_SIZE = 2_000_000_000;
    private static final int DEFAULT_DECIMAL_DIGITS = 10;

    private ColumnMetadata() {}

    static StmtResult columns(StmtResult raw, List<Col> columns) {
        List<Row> rows = new ArrayList<>();
        if (raw != null && raw.rows() != null) {
            for (Row row : raw.rows()) {
                rows.add(JdbcRow.from(PragmaRow.fromRow(row)).toRow());
            }
        }
        return new StmtResult(columns, rows, 0, null);
    }

    /** One {@code pragma_table_xinfo} row, in the order {@code MetadataSql.columns} selects it. */
    private record PragmaRow(
            String tableName,
            String createSql,
            long cid,
            String columnName,
            String declaredType,
            boolean notNull,
            String columnDefault,
            boolean primaryKey,
            long hidden) {

        private static PragmaRow fromRow(Row row) {
            List<Value> values = row.values();
            return new PragmaRow(
                    text(values.get(0)),
                    text(values.get(1)),
                    integer(values.get(2)),
                    text(values.get(3)),
                    text(values.get(4)),
                    integer(values.get(5)) != 0,
                    text(values.get(6)),
                    integer(values.get(7)) != 0,
                    integer(values.get(8)));
        }
    }

    private record JdbcRow(
            String tableName,
            String columnName,
            ColumnShape shape,
            long ordinalPosition,
            boolean notNull,
            String columnDefault,
            boolean autoIncrement,
            boolean generated) {

        private static JdbcRow from(PragmaRow pragma) {
            return new JdbcRow(
                    pragma.tableName(),
                    pragma.columnName(),
                    columnShape(pragma.declaredType()),
                    pragma.cid() + 1,
                    pragma.notNull(),
                    pragma.columnDefault(),
                    pragma.primaryKey()
                            && SqlAutoIncrement.isAutoIncrementColumn(
                                    pragma.createSql(), pragma.columnName()),
                    pragma.hidden() == 2 || pragma.hidden() == 3);
        }

        private Row toRow() {
            return new Row(List.of(
                    Value.ofNull(), // TABLE_CAT
                    Value.ofNull(), // TABLE_SCHEM
                    Value.of(tableName),
                    Value.of(columnName),
                    Value.of(shape.jdbcType()),
                    Value.of(shape.typeName()),
                    Value.of(shape.columnSize()),
                    Value.of(UNKNOWN_COLUMN_SIZE), // BUFFER_LENGTH
                    Value.of(shape.decimalDigits()),
                    Value.of(10), // NUM_PREC_RADIX is 10 by JDBC, not DECIMAL_DIGITS
                    Value.of(notNull ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullable),
                    Value.ofNull(), // REMARKS stays null; libSQL has no column comments
                    Value.of(columnDefault),
                    Value.of(0), // SQL_DATA_TYPE
                    Value.of(0), // SQL_DATETIME_SUB
                    Value.of(UNKNOWN_COLUMN_SIZE), // CHAR_OCTET_LENGTH
                    Value.of(ordinalPosition),
                    Value.of(notNull ? "NO" : "YES"),
                    Value.ofNull(), // SCOPE_CATALOG
                    Value.ofNull(), // SCOPE_SCHEMA
                    Value.ofNull(), // SCOPE_TABLE
                    Value.ofNull(), // SOURCE_DATA_TYPE
                    Value.of(autoIncrement ? "YES" : "NO"),
                    Value.of(generated ? "YES" : "NO")));
        }
    }

    private record ColumnShape(int jdbcType, String typeName, int columnSize, int decimalDigits) {}

    private static ColumnShape columnShape(String declaredType) {
        String typeName = declaredType == null ? "TEXT" : declaredType.toUpperCase(Locale.ROOT);
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
}
