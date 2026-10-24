package com.github.magnusp.libsql.jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the origin table of each result column in a SQLite statement.
 *
 * <p>Resolved shapes:
 *
 * <ul>
 *   <li>simple queries, including {@code SELECT *} and schema-qualified names
 *   <li>column aliases, including aliases over joined, subquery, and view columns
 *   <li>joins, including comma joins and explicit {@code JOIN} clauses
 *   <li>subqueries in the FROM clause, scalar subqueries, and common table expressions
 *   <li>the leftmost branch of a compound SELECT
 *   <li>{@code INSERT}, {@code UPDATE}, and {@code DELETE} with {@code RETURNING}
 *   <li>views, which resolve to the base table that supplies each column
 * </ul>
 *
 * <p>Expressions, literals, and other shapes whose origin SQLite does not expose as a direct
 * column reference do not resolve. Callers report the JDBC empty table name and unknown
 * nullability for those columns.
 */
final class SqlTableReference {

    record TableRef(String schema, String table) {}

    record Target(TableRef table, String alias) {}

    interface Schema {
        TableDefinition definition(TableRef table) throws SQLException;
    }

    record TableDefinition(String sql, List<String> columns) {}

    record Resolution(List<String> labels, List<TableRef> tables) {
        static final Resolution EMPTY = new Resolution(List.of(), List.of());

        TableRef table(int index) {
            return index >= 0 && index < tables.size() ? tables.get(index) : null;
        }
    }

    /**
     * Parses a statement from a token range. A view definition re-enters statement parsing through
     * {@link #onTokens}, which keeps the same schema and cycle guard.
     */
    interface StatementParser {
        Resolution parseStatement(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException;

        StatementParser onTokens(List<SqlTokenizer.Token> tokens);
    }

    record ColumnRef(List<String> parts) {
        String column() {
            return parts.get(parts.size() - 1);
        }

        String qualifier() {
            return parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
        }
    }

    record Source(
            String qualifier,
            List<String> columns,
            List<TableRef> tables,
            boolean knownColumns,
            TableRef baseTable) {

        TableRef origin(String column) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).equalsIgnoreCase(column)) {
                    return tables.get(i);
                }
            }
            return null;
        }

        boolean hasColumn(String column) {
            for (String candidate : columns) {
                if (candidate.equalsIgnoreCase(column)) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesQualifier(String qualifier) {
            return this.qualifier != null && this.qualifier.equalsIgnoreCase(qualifier);
        }
    }

    /**
     * Collects result columns so that every label is paired with the table that supplies it. All
     * additions go through one method, which keeps the two lists the same length by construction.
     */
    static final class ResolutionBuilder {

        private final List<String> labels = new ArrayList<>();
        private final List<TableRef> tables = new ArrayList<>();

        void add(String label, TableRef table) {
            labels.add(label);
            tables.add(table);
        }

        void addAll(List<String> labels, List<TableRef> tables) {
            if (labels.size() != tables.size()) {
                throw new IllegalArgumentException("labels and tables must have the same size");
            }
            this.labels.addAll(labels);
            this.tables.addAll(tables);
        }

        void addAll(ResolutionBuilder other) {
            addAll(other.labels, other.tables);
        }

        int size() {
            return labels.size();
        }

        boolean containsLabel(String label) {
            return containsIgnoreCase(labels, label);
        }

        void dropLabels(List<String> columns, int from) {
            for (int i = labels.size() - 1; i >= from; i--) {
                if (containsIgnoreCase(columns, labels.get(i))) {
                    labels.remove(i);
                    tables.remove(i);
                }
            }
        }

        List<String> labels() {
            return Collections.unmodifiableList(new ArrayList<>(labels));
        }

        List<TableRef> tables() {
            return Collections.unmodifiableList(new ArrayList<>(tables));
        }

        Resolution build() {
            return new Resolution(labels(), tables());
        }

        static boolean containsIgnoreCase(List<String> values, String value) {
            for (String candidate : values) {
                if (candidate.equalsIgnoreCase(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    static final Set<String> FROM_END = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "WINDOW", "RETURNING",
            "UNION", "INTERSECT", "EXCEPT");

    static final Set<String> JOIN_START =
            Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER");

    private static final Set<String> SET_OPERATORS = Set.of("UNION", "INTERSECT", "EXCEPT");

    static final Set<String> NOT_AN_ALIAS = Set.of(
            "AS", "ON", "USING", "INDEXED", "NOT", "COLLATE", "IS", "ISNULL", "NOTNULL", "LIKE",
            "GLOB", "REGEXP", "MATCH", "ESCAPE", "BETWEEN", "IN", "CASE", "WHEN", "THEN", "ELSE",
            "END", "CAST", "EXISTS", "AND", "OR", "NULL");

    private SqlTableReference() {}

    static Resolution resolve(String sql, Schema schema) throws SQLException {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        List<SqlTokenizer.Token> tokens = SqlTokenizer.tokenize(sql);
        if (tokens == null) {
            return null;
        }
        return new Parser(tokens, schema, new HashSet<>())
                .parseStatement(0, tokens.size(), 0, new HashMap<>());
    }

    /**
     * Resolves column origins and discards the resolution when the parser expansion does not match
     * the result width. The protocol column list is authoritative for width, and a shifted
     * expansion would attribute every later column to the wrong table.
     */
    static Resolution resolve(String sql, Schema schema, int columnCount) throws SQLException {
        Resolution resolution = resolve(sql, schema);
        if (resolution == null || resolution.labels().size() != columnCount) {
            return null;
        }
        return resolution;
    }

    private static final class Parser implements StatementParser {

        private final SqlCursor cursor;
        private final Schema schema;
        private final Set<String> resolvingViews;
        private final TableBinding binding;

        private Parser(List<SqlTokenizer.Token> tokens, Schema schema, Set<String> resolvingViews) {
            this.cursor = new SqlCursor(tokens, 0);
            this.schema = schema;
            this.resolvingViews = resolvingViews;
            this.binding = new TableBinding(schema, resolvingViews);
        }

        @Override
        public StatementParser onTokens(List<SqlTokenizer.Token> tokens) {
            return new Parser(tokens, schema, resolvingViews);
        }

        @Override
        public Resolution parseStatement(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            SqlCursor cursor = this.cursor.at(depth);
            int index = start;
            while (index < end && cursor.isSymbolAt(index, ";")) {
                index++;
            }
            while (end > index && cursor.isSymbolAt(end - 1, ";")) {
                end--;
            }
            if (index >= end) {
                return null;
            }
            String keyword = cursor.wordAt(index);
            if (keyword == null) {
                return null;
            }
            return switch (keyword) {
                case "WITH" -> parseWith(index, end, depth, scope);
                case "SELECT" -> parseSelect(index, end, depth, scope);
                case "INSERT", "REPLACE", "UPDATE", "DELETE" -> parseReturning(index, end, depth, scope);
                default -> null;
            };
        }

        private Resolution parseWith(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            SqlCursor cursor = this.cursor.at(depth);
            Map<String, Source> ctes = new HashMap<>(scope);
            int index = start + 1;
            if (cursor.isWordAt(index, "RECURSIVE")) {
                index++;
            }
            while (index < end) {
                String name = cursor.identifierAt(index);
                if (name == null) {
                    return null;
                }
                index++;
                List<String> columns = null;
                if (cursor.isSymbolAt(index, "(")) {
                    int close = cursor.matchingParen(index);
                    if (close < 0 || close >= end) {
                        return null;
                    }
                    columns = cursor.at(depth + 1).identifiers(index + 1, close);
                    index = close + 1;
                }
                if (!cursor.isWordAt(index, "AS")) {
                    return null;
                }
                index++;
                if (cursor.isWordAt(index, "NOT")) {
                    index += 2;
                } else if (cursor.isWordAt(index, "MATERIALIZED")) {
                    index++;
                }
                if (!cursor.isSymbolAt(index, "(")) {
                    return null;
                }
                int close = cursor.matchingParen(index);
                if (close < 0 || close >= end) {
                    return null;
                }
                Resolution resolution = parseStatement(index + 1, close, depth + 1, ctes);
                if (resolution != null) {
                    ctes.put(name.toLowerCase(Locale.ROOT), binding.cte(name, columns, resolution));
                } else {
                    ctes.put(name.toLowerCase(Locale.ROOT), binding.opaqueCte(name, columns));
                }
                index = close + 1;
                if (cursor.isSymbolAt(index, ",")) {
                    index++;
                    continue;
                }
                break;
            }
            if (index >= end) {
                return null;
            }
            return parseStatement(index, end, depth, ctes);
        }

        private Resolution parseSelect(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            SqlCursor cursor = this.cursor.at(depth);
            int index = start + 1;
            while (cursor.isWordAt(index, "ALL") || cursor.isWordAt(index, "DISTINCT")) {
                index++;
            }
            int setOperator = cursor.indexOfAnyWord(index, end, SET_OPERATORS);
            int selectEnd = setOperator >= 0 ? setOperator : end;
            int fromIndex = cursor.indexOfWord(index, selectEnd, "FROM");
            int listEnd = fromIndex >= 0 ? fromIndex : selectEnd;
            FromClauseParser.FromClause from = FromClauseParser.FromClause.of(List.of());
            if (fromIndex >= 0) {
                int fromEnd = cursor.indexOfAnyWord(fromIndex + 1, selectEnd, FROM_END);
                if (fromEnd < 0) {
                    fromEnd = selectEnd;
                }
                FromClauseParser.FromClause parsed = new FromClauseParser(cursor, binding, this, scope)
                        .parseSources(fromIndex + 1, fromEnd);
                if (parsed == null || parsed.sources().isEmpty()) {
                    return null;
                }
                from = parsed;
            }
            return new SelectItemParser(cursor, this, from, scope).parseItems(index, listEnd);
        }

        private Resolution parseReturning(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            SqlCursor cursor = this.cursor.at(depth);
            int returning = cursor.indexOfWord(start, end, "RETURNING");
            if (returning < 0) {
                return null;
            }
            Target target = targetTable(start, returning, depth);
            if (target == null) {
                return null;
            }
            String qualifier = target.alias() != null ? target.alias() : target.table().table();
            Source source = binding.returningSource(target.table(), qualifier);
            return new SelectItemParser(
                            cursor,
                            this,
                            FromClauseParser.FromClause.of(List.of(source)),
                            scope)
                    .parseItems(returning + 1, end);
        }

        private Target targetTable(int start, int end, int depth) {
            SqlCursor cursor = this.cursor.at(depth);
            String keyword = cursor.wordAt(start);
            if (keyword == null) {
                return null;
            }
            int index;
            switch (keyword) {
                case "INSERT", "REPLACE" -> {
                    index = cursor.indexOfWord(start, end, "INTO");
                    if (index < 0) {
                        return null;
                    }
                    index++;
                }
                case "UPDATE" -> {
                    index = start + 1;
                    if (cursor.isWordAt(index, "OR")) {
                        index += 2;
                    }
                }
                case "DELETE" -> {
                    index = cursor.indexOfWord(start, end, "FROM");
                    if (index < 0) {
                        return null;
                    }
                    index++;
                }
                default -> {
                    return null;
                }
            }
            String first = cursor.identifierAt(index);
            if (first == null) {
                return null;
            }
            index++;
            String schemaName = null;
            if (cursor.isSymbolAt(index, ".")) {
                index++;
                String second = cursor.identifierAt(index);
                if (second == null) {
                    return null;
                }
                schemaName = first;
                first = second;
            }
            String alias = null;
            if (cursor.isWordAt(index, "AS")) {
                alias = cursor.identifierAt(index + 1);
                if (alias == null) {
                    return null;
                }
            }
            return new Target(new TableRef(schemaName, first), alias);
        }
    }
}
