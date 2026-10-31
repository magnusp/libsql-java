package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.Row;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.Value;

import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shapes {@code pragma_foreign_key_list} rows into the 14-column JDBC foreign key shape. Imported
 * and exported keys select different projections of the same pragma columns, so each direction maps
 * {@link PragmaRow} separately.
 */
final class ForeignKeyMetadata {

    private static final Pattern NAMED_PRIMARY_KEY = Pattern.compile(
            "\\bCONSTRAINT\\s*(\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|'[^']+'|[^\\s]+)\\s*PRIMARY\\s+KEY\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NAMED_FOREIGN_KEY = Pattern.compile(
            "\\bCONSTRAINT\\s+(\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|'[^']+'|[^\\s]+)\\s+FOREIGN\\s+KEY\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final List<Col> COLUMNS = List.of(
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

    private static final Comparator<JdbcRow> IMPORTED_ORDER = Comparator.comparing(
                    JdbcRow::pkTable, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingInt(JdbcRow::keySeq);

    private static final Comparator<JdbcRow> EXPORTED_ORDER = Comparator.comparing(
                    JdbcRow::fkTable, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingInt(JdbcRow::keySeq);

    private ForeignKeyMetadata() {}

    static StmtResult empty() {
        return new StmtResult(COLUMNS, List.of(), 0, null);
    }

    static StmtResult fromImported(StmtResult raw, String catalog, String schema, String table) {
        List<JdbcRow> rows = new ArrayList<>();
        if (raw != null && raw.rows() != null) {
            for (Row row : raw.rows()) {
                PragmaRow pragma = PragmaRow.fromImported(row);
                String pkColumn = pragma.pkColumn();
                if (pkColumn == null) {
                    pkColumn = pragma.pkFirstColumn();
                }
                rows.add(new JdbcRow(
                        catalog,
                        schema,
                        pragma.pkTable(),
                        pkColumn,
                        catalog,
                        schema,
                        table,
                        pragma.fkColumn(),
                        (int) pragma.seq() + 1,
                        keyRule(pragma.updateRule()),
                        keyRule(pragma.deleteRule()),
                        nameOrEmpty(foreignKeyName(pragma.fkCreateSql(), pragma.fkId())),
                        nameOrEmpty(primaryKeyName(pragma.pkCreateSql()))));
            }
        }
        rows.sort(IMPORTED_ORDER);
        return foreignKeyResult(rows);
    }

    static StmtResult fromExported(
            StmtResult raw, String pkCatalog, String pkSchema, String fkCatalog, String fkSchema) {
        List<JdbcRow> rows = new ArrayList<>();
        if (raw != null && raw.rows() != null) {
            for (Row row : raw.rows()) {
                PragmaRow pragma = PragmaRow.fromExported(row);
                String pkName = primaryKeyName(pragma.pkCreateSql());
                if (!containsColumn(pragma.pkColumns(), pragma.pkColumn())) {
                    pkName = null;
                }
                rows.add(new JdbcRow(
                        pkCatalog,
                        pkSchema,
                        pragma.pkTable(),
                        pragma.pkColumn(),
                        fkCatalog,
                        fkSchema,
                        pragma.fkTable(),
                        pragma.fkColumn(),
                        (int) pragma.seq() + 1,
                        keyRule(pragma.updateRule()),
                        keyRule(pragma.deleteRule()),
                        nameOrEmpty(foreignKeyName(pragma.fkCreateSql(), pragma.fkId())),
                        nameOrEmpty(pkName)));
            }
        }
        rows.sort(EXPORTED_ORDER);
        return foreignKeyResult(rows);
    }

    private static StmtResult foreignKeyResult(List<JdbcRow> rows) {
        List<Row> values = new ArrayList<>(rows.size());
        for (JdbcRow row : rows) {
            values.add(row.toRow());
        }
        return new StmtResult(COLUMNS, values, 0, null);
    }

    private static int keyRule(String rule) {
        if (rule == null) {
            return DatabaseMetaData.importedKeyNoAction;
        }
        return switch (rule.toUpperCase(Locale.ROOT)) {
            case "CASCADE" -> DatabaseMetaData.importedKeyCascade;
            case "RESTRICT" -> DatabaseMetaData.importedKeyRestrict;
            case "SET NULL" -> DatabaseMetaData.importedKeySetNull;
            case "SET DEFAULT" -> DatabaseMetaData.importedKeySetDefault;
            default -> DatabaseMetaData.importedKeyNoAction;
        };
    }

    static String primaryKeyName(String createSql) {
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

    /**
     * One {@code pragma_foreign_key_list} row. The imported and exported selects project different
     * columns at offsets 7-10, so each direction has its own mapping.
     */
    private record PragmaRow(
            long fkId,
            long seq,
            String pkTable,
            String fkColumn,
            String pkColumn,
            String updateRule,
            String deleteRule,
            String fkTable,
            String fkCreateSql,
            String pkCreateSql,
            String pkFirstColumn,
            String pkColumns) {

        private static PragmaRow fromImported(Row row) {
            List<Value> values = row.values();
            return new PragmaRow(
                    integer(values.get(0)),
                    integer(values.get(1)),
                    text(values.get(2)),
                    text(values.get(3)),
                    text(values.get(4)),
                    text(values.get(5)),
                    text(values.get(6)),
                    null,
                    text(values.get(7)),
                    text(values.get(8)),
                    text(values.get(9)),
                    null);
        }

        private static PragmaRow fromExported(Row row) {
            List<Value> values = row.values();
            return new PragmaRow(
                    integer(values.get(0)),
                    integer(values.get(1)),
                    text(values.get(2)),
                    text(values.get(3)),
                    text(values.get(4)),
                    text(values.get(5)),
                    text(values.get(6)),
                    text(values.get(7)),
                    text(values.get(8)),
                    text(values.get(9)),
                    null,
                    text(values.get(10)));
        }
    }

    private record JdbcRow(
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

    private static String text(Value value) {
        return value instanceof Value.Text text ? text.value() : null;
    }

    private static long integer(Value value) {
        return value instanceof Value.Integer integer ? integer.value() : 0L;
    }
}
