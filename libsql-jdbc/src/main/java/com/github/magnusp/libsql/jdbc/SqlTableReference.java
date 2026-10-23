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

    private record ColumnRef(List<String> parts) {
        String column() {
            return parts.get(parts.size() - 1);
        }

        String qualifier() {
            return parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
        }
    }

    private record Source(
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

    private record SourceList(List<Source> sources, int next) {}

    /**
     * The sources of a FROM clause together with the column list {@code *} expands to. The star
     * list drops the right-side duplicate of a USING or NATURAL join column, as SQLite does.
     */
    private record FromClause(
            List<Source> sources,
            List<String> starLabels,
            List<TableRef> starTables,
            List<String> mergedColumns) {

        static FromClause of(List<Source> sources) {
            List<String> labels = new ArrayList<>();
            List<TableRef> tables = new ArrayList<>();
            for (Source source : sources) {
                appendStarColumns(source, false, labels, tables);
            }
            return new FromClause(sources, labels, tables, List.of());
        }
    }

    private static final Set<String> FROM_END = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "WINDOW", "RETURNING",
            "UNION", "INTERSECT", "EXCEPT");

    private static final Set<String> JOIN_START =
            Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER");

    private static final Set<String> SET_OPERATORS = Set.of("UNION", "INTERSECT", "EXCEPT");

    private static final Set<String> NOT_AN_ALIAS = Set.of(
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

    private static final class Parser {

        private final List<SqlTokenizer.Token> tokens;
        private final Schema schema;
        private final Set<String> resolvingViews;

        private Parser(List<SqlTokenizer.Token> tokens, Schema schema, Set<String> resolvingViews) {
            this.tokens = tokens;
            this.schema = schema;
            this.resolvingViews = resolvingViews;
        }

        private Resolution parseStatement(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            int index = start;
            while (index < end && SqlTokenizer.isSymbolAt(tokens, index, ";", depth)) {
                index++;
            }
            while (end > index && SqlTokenizer.isSymbolAt(tokens, end - 1, ";", depth)) {
                end--;
            }
            if (index >= end) {
                return null;
            }
            String keyword = SqlTokenizer.wordAt(tokens, index, depth);
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
            Map<String, Source> ctes = new HashMap<>(scope);
            int index = start + 1;
            if (SqlTokenizer.isWordAt(tokens, index, depth, "RECURSIVE")) {
                index++;
            }
            while (index < end) {
                String name = SqlTokenizer.identifierAt(tokens, index);
                if (name == null) {
                    return null;
                }
                index++;
                List<String> columns = null;
                if (SqlTokenizer.isSymbolAt(tokens, index, "(", depth)) {
                    int close = SqlTokenizer.matchingParen(tokens, index);
                    if (close < 0 || close >= end) {
                        return null;
                    }
                    columns = SqlTokenizer.identifiers(tokens, index + 1, close, depth + 1);
                    index = close + 1;
                }
                if (!SqlTokenizer.isWordAt(tokens, index, depth, "AS")) {
                    return null;
                }
                index++;
                if (SqlTokenizer.isWordAt(tokens, index, depth, "NOT")) {
                    index += 2;
                } else if (SqlTokenizer.isWordAt(tokens, index, depth, "MATERIALIZED")) {
                    index++;
                }
                if (!SqlTokenizer.isSymbolAt(tokens, index, "(", depth)) {
                    return null;
                }
                int close = SqlTokenizer.matchingParen(tokens, index);
                if (close < 0 || close >= end) {
                    return null;
                }
                Resolution resolution = parseStatement(index + 1, close, depth + 1, ctes);
                if (resolution != null) {
                    ctes.put(
                            name.toLowerCase(Locale.ROOT),
                            new Source(
                                    name,
                                    columns != null && columns.size() == resolution.labels().size()
                                            ? columns
                                            : resolution.labels(),
                                    resolution.tables(),
                                    true,
                                    null));
                } else {
                    List<String> opaque = columns != null ? columns : List.of();
                    ctes.put(
                            name.toLowerCase(Locale.ROOT),
                            new Source(
                                    name,
                                    opaque,
                                    Collections.nCopies(opaque.size(), null),
                                    columns != null,
                                    null));
                }
                index = close + 1;
                if (SqlTokenizer.isSymbolAt(tokens, index, ",", depth)) {
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
            int index = start + 1;
            while (SqlTokenizer.isWordAt(tokens, index, depth, "ALL")
                    || SqlTokenizer.isWordAt(tokens, index, depth, "DISTINCT")) {
                index++;
            }
            int setOperator = SqlTokenizer.indexOfAnyWord(tokens, index, end, depth, SET_OPERATORS);
            int selectEnd = setOperator >= 0 ? setOperator : end;
            int fromIndex = SqlTokenizer.indexOfWord(tokens, index, selectEnd, depth, "FROM");
            int listEnd = fromIndex >= 0 ? fromIndex : selectEnd;
            FromClause from = FromClause.of(List.of());
            if (fromIndex >= 0) {
                int fromEnd = SqlTokenizer.indexOfAnyWord(tokens, fromIndex + 1, selectEnd, depth, FROM_END);
                if (fromEnd < 0) {
                    fromEnd = selectEnd;
                }
                FromClause parsed = parseSources(fromIndex + 1, fromEnd, depth, scope);
                if (parsed == null || parsed.sources().isEmpty()) {
                    return null;
                }
                from = parsed;
            }
            return parseSelectItems(index, listEnd, depth, from, scope);
        }

        private FromClause parseSources(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            List<Source> sources = new ArrayList<>();
            List<String> starLabels = new ArrayList<>();
            List<TableRef> starTables = new ArrayList<>();
            List<String> mergedColumns = new ArrayList<>();
            boolean natural = false;
            int index = start;
            while (index < end) {
                SourceList parsed = parseSource(index, end, depth, scope);
                if (parsed == null) {
                    return null;
                }
                int starStart = starLabels.size();
                for (Source source : parsed.sources()) {
                    sources.add(source);
                    appendStarColumns(source, natural, starLabels, starTables);
                }
                index = parsed.next();
                index = skipIndexModifiers(index, end, depth);
                if (SqlTokenizer.isWordAt(tokens, index, depth, "USING")) {
                    List<String> merged = usingColumns(index, end, depth);
                    dropStarColumns(merged, starStart, starLabels, starTables);
                    for (String column : merged) {
                        if (!containsIgnoreCase(mergedColumns, column)) {
                            mergedColumns.add(column);
                        }
                    }
                }
                index = skipJoinCondition(index, end, depth);
                if (SqlTokenizer.isSymbolAt(tokens, index, ",", depth)) {
                    natural = false;
                    index++;
                    continue;
                }
                if (index < end && isJoinStart(index, end, depth)) {
                    natural = SqlTokenizer.isWordAt(tokens, index, depth, "NATURAL");
                    index = skipToJoin(index, end, depth);
                    if (index < 0) {
                        break;
                    }
                    index++;
                    continue;
                }
                break;
            }
            return new FromClause(sources, starLabels, starTables, mergedColumns);
        }

        private SourceList parseSource(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            int index = start;
            if (SqlTokenizer.isSymbolAt(tokens, index, "(", depth)) {
                int close = SqlTokenizer.matchingParen(tokens, index);
                if (close < 0 || close >= end) {
                    return null;
                }
                int innerDepth = depth + 1;
                String innerKeyword = SqlTokenizer.wordAt(tokens, index + 1, innerDepth);
                if ("SELECT".equals(innerKeyword) || "WITH".equals(innerKeyword)) {
                    Resolution inner = parseStatement(index + 1, close, innerDepth, scope);
                    index = close + 1;
                    String alias = null;
                    if (SqlTokenizer.isWordAt(tokens, index, depth, "AS")) {
                        index++;
                        alias = SqlTokenizer.identifierAt(tokens, index);
                        if (alias == null) {
                            return null;
                        }
                        index++;
                    } else if (isAlias(index, depth)) {
                        alias = SqlTokenizer.identifierAt(tokens, index);
                        index++;
                    }
                    List<String> aliasColumns = null;
                    if (SqlTokenizer.isSymbolAt(tokens, index, "(", depth)) {
                        int columnsEnd = SqlTokenizer.matchingParen(tokens, index);
                        if (columnsEnd < 0 || columnsEnd >= end) {
                            return null;
                        }
                        aliasColumns = SqlTokenizer.identifiers(tokens, index + 1, columnsEnd, depth + 1);
                        index = columnsEnd + 1;
                    }
                    if (inner == null) {
                        inner = Resolution.EMPTY;
                    }
                    List<String> labels = aliasColumns != null && aliasColumns.size() == inner.labels().size()
                            ? aliasColumns
                            : inner.labels();
                    return new SourceList(
                            List.of(new Source(alias, labels, inner.tables(), true, null)), index);
                }
                FromClause grouped = parseSources(index + 1, close, innerDepth, scope);
                if (grouped == null) {
                    return null;
                }
                index = close + 1;
                String alias = null;
                if (SqlTokenizer.isWordAt(tokens, index, depth, "AS")) {
                    index++;
                    alias = SqlTokenizer.identifierAt(tokens, index);
                    if (alias == null) {
                        return null;
                    }
                    index++;
                } else if (isAlias(index, depth)) {
                    alias = SqlTokenizer.identifierAt(tokens, index);
                    index++;
                }
                if (alias == null) {
                    return new SourceList(grouped.sources(), index);
                }
                return new SourceList(
                        List.of(new Source(alias, grouped.starLabels(), grouped.starTables(), true, null)),
                        index);
            }

            String first = SqlTokenizer.identifierAt(tokens, index);
            if (first == null) {
                return null;
            }
            index++;
            String schemaName = null;
            if (SqlTokenizer.isSymbolAt(tokens, index, ".", depth)) {
                index++;
                String second = SqlTokenizer.identifierAt(tokens, index);
                if (second == null) {
                    return null;
                }
                index++;
                if (SqlTokenizer.isSymbolAt(tokens, index, ".", depth)) {
                    return null;
                }
                schemaName = first;
                first = second;
            }
            String alias = null;
            if (SqlTokenizer.isWordAt(tokens, index, depth, "AS")) {
                index++;
                alias = SqlTokenizer.identifierAt(tokens, index);
                if (alias == null) {
                    return null;
                }
                index++;
            } else if (isAlias(index, depth)) {
                alias = SqlTokenizer.identifierAt(tokens, index);
                index++;
            }
            TableRef reference = new TableRef(schemaName, first);
            String qualifier = alias != null ? alias : first;
            if (schemaName == null) {
                Source cte = scope.get(first.toLowerCase(Locale.ROOT));
                if (cte != null) {
                    return new SourceList(
                            List.of(new Source(
                                    qualifier,
                                    cte.columns(),
                                    cte.tables(),
                                    cte.knownColumns(),
                                    cte.baseTable())),
                            index);
                }
            }
            TableDefinition definition = schema.definition(reference);
            if (definition == null) {
                return new SourceList(
                        List.of(new Source(qualifier, List.of(), List.of(), false, reference)), index);
            }
            if (isViewDefinition(definition.sql())) {
                Resolution view = resolveView(reference, definition, depth, scope);
                if (view == null) {
                    return new SourceList(
                            List.of(new Source(
                                    qualifier,
                                    definition.columns(),
                                    Collections.nCopies(definition.columns().size(), null),
                                    true,
                                    null)),
                            index);
                }
                return new SourceList(
                        List.of(new Source(qualifier, view.labels(), view.tables(), true, null)), index);
            }
            List<String> columns = definition.columns() != null ? definition.columns() : List.of();
            return new SourceList(
                    List.of(new Source(
                            qualifier,
                            columns,
                            Collections.nCopies(columns.size(), reference),
                            true,
                            reference)),
                    index);
        }

        private Resolution resolveView(
                TableRef reference, TableDefinition definition, int depth, Map<String, Source> scope)
                throws SQLException {
            String key = (reference.schema() != null ? reference.schema() + "." : "")
                    + reference.table().toLowerCase(Locale.ROOT);
            if (!resolvingViews.add(key)) {
                return null;
            }
            try {
                List<SqlTokenizer.Token> viewTokens = SqlTokenizer.tokenize(definition.sql());
                if (viewTokens == null) {
                    return null;
                }
                int as = SqlTokenizer.indexOfWord(viewTokens, 0, viewTokens.size(), 0, "AS");
                if (as < 0) {
                    return null;
                }
                List<String> explicit = null;
                for (int i = 0; i < as; i++) {
                    SqlTokenizer.Token token = viewTokens.get(i);
                    if (token.depth() == 0 && token.isSymbol("(")) {
                        int close = SqlTokenizer.matchingParen(viewTokens, i);
                        if (close < 0) {
                            return null;
                        }
                        explicit = SqlTokenizer.identifiers(viewTokens, i + 1, close, 1);
                        break;
                    }
                }
                Resolution inner = new Parser(viewTokens, schema, resolvingViews)
                        .parseStatement(as + 1, viewTokens.size(), 0, new HashMap<>(scope));
                if (inner == null) {
                    return null;
                }
                if (explicit != null && explicit.size() == inner.labels().size()) {
                    return new Resolution(explicit, inner.tables());
                }
                return inner;
            } finally {
                resolvingViews.remove(key);
            }
        }

        private Resolution parseReturning(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            int returning = SqlTokenizer.indexOfWord(tokens, start, end, depth, "RETURNING");
            if (returning < 0) {
                return null;
            }
            Target target = targetTable(start, returning, depth);
            if (target == null) {
                return null;
            }
            String qualifier = target.alias() != null ? target.alias() : target.table().table();
            TableDefinition definition = schema.definition(target.table());
            Source source;
            if (definition == null) {
                source = new Source(qualifier, List.of(), List.of(), false, target.table());
            } else if (isViewDefinition(definition.sql())) {
                List<String> columns = definition.columns();
                source = new Source(
                        qualifier, columns, Collections.nCopies(columns.size(), null), true, null);
            } else {
                List<String> columns = definition.columns() != null ? definition.columns() : List.of();
                source = new Source(
                        qualifier,
                        columns,
                        Collections.nCopies(columns.size(), target.table()),
                        true,
                        target.table());
            }
            return parseSelectItems(returning + 1, end, depth, FromClause.of(List.of(source)), scope);
        }

        private Target targetTable(int start, int end, int depth) {
            String keyword = SqlTokenizer.wordAt(tokens, start, depth);
            if (keyword == null) {
                return null;
            }
            int index;
            switch (keyword) {
                case "INSERT", "REPLACE" -> {
                    index = SqlTokenizer.indexOfWord(tokens, start, end, depth, "INTO");
                    if (index < 0) {
                        return null;
                    }
                    index++;
                }
                case "UPDATE" -> {
                    index = start + 1;
                    if (SqlTokenizer.isWordAt(tokens, index, depth, "OR")) {
                        index += 2;
                    }
                }
                case "DELETE" -> {
                    index = SqlTokenizer.indexOfWord(tokens, start, end, depth, "FROM");
                    if (index < 0) {
                        return null;
                    }
                    index++;
                }
                default -> {
                    return null;
                }
            }
            String first = SqlTokenizer.identifierAt(tokens, index);
            if (first == null) {
                return null;
            }
            index++;
            String schemaName = null;
            if (SqlTokenizer.isSymbolAt(tokens, index, ".", depth)) {
                index++;
                String second = SqlTokenizer.identifierAt(tokens, index);
                if (second == null) {
                    return null;
                }
                schemaName = first;
                first = second;
            }
            String alias = null;
            if (SqlTokenizer.isWordAt(tokens, index, depth, "AS")) {
                alias = SqlTokenizer.identifierAt(tokens, index + 1);
                if (alias == null) {
                    return null;
                }
            }
            return new Target(new TableRef(schemaName, first), alias);
        }

        private Resolution parseSelectItems(
                int start, int end, int depth, FromClause from, Map<String, Source> scope)
                throws SQLException {
            List<String> labels = new ArrayList<>();
            List<TableRef> tables = new ArrayList<>();
            int index = start;
            while (index < end) {
                int comma = SqlTokenizer.indexOfSymbol(tokens, index, end, depth, ",");
                int itemEnd = comma < 0 ? end : comma;
                parseItem(index, itemEnd, depth, from, scope, labels, tables);
                if (comma < 0) {
                    break;
                }
                index = comma + 1;
            }
            return new Resolution(labels, tables);
        }

        private void parseItem(
                int start,
                int end,
                int depth,
                FromClause from,
                Map<String, Source> scope,
                List<String> labels,
                List<TableRef> tables)
                throws SQLException {
            if (start >= end) {
                labels.add("");
                tables.add(null);
                return;
            }
            String alias = null;
            int expressionEnd = end;
            int as = SqlTokenizer.indexOfWord(tokens, start, end, depth, "AS");
            if (as >= 0 && as + 1 < end) {
                String candidate = SqlTokenizer.identifierAt(tokens, as + 1);
                if (candidate != null) {
                    alias = candidate;
                    expressionEnd = as;
                }
            } else {
                int implicit = implicitAlias(start, end, depth);
                if (implicit >= 0) {
                    alias = SqlTokenizer.identifierAt(tokens, implicit);
                    expressionEnd = implicit;
                }
            }
            if (expressionEnd <= start) {
                labels.add(alias != null ? alias : "");
                tables.add(null);
                return;
            }

            if (expressionEnd - start == 1 && tokens.get(start).isSymbol("*")) {
                labels.addAll(from.starLabels());
                tables.addAll(from.starTables());
                return;
            }

            ColumnRef qualifiedStar = qualifiedStar(start, expressionEnd);
            if (qualifiedStar != null) {
                Source source = findSource(
                        qualifiedStar.parts().get(qualifiedStar.parts().size() - 1), from.sources());
                if (source != null && source.knownColumns()) {
                    labels.addAll(source.columns());
                    tables.addAll(source.tables());
                }
                return;
            }

            ColumnRef column = simpleColumn(start, expressionEnd);
            if (column != null) {
                labels.add(alias != null ? alias : column.column());
                tables.add(resolveColumn(column, from));
                return;
            }

            if (tokens.get(start).isSymbol("(")
                    && SqlTokenizer.matchingParen(tokens, start) == expressionEnd - 1) {
                int innerDepth = depth + 1;
                String innerKeyword = SqlTokenizer.wordAt(tokens, start + 1, innerDepth);
                if ("SELECT".equals(innerKeyword) || "WITH".equals(innerKeyword)) {
                    Resolution inner = parseStatement(start + 1, expressionEnd - 1, innerDepth, scope);
                    if (inner != null && inner.tables().size() == 1) {
                        labels.add(alias != null ? alias : inner.labels().get(0));
                        tables.add(inner.tables().get(0));
                        return;
                    }
                }
            }

            labels.add(alias != null ? alias : expressionLabel(start, expressionEnd));
            tables.add(null);
        }

        private TableRef resolveColumn(ColumnRef column, FromClause from) {
            List<Source> sources = from.sources();
            String qualifier = column.qualifier();
            if (qualifier != null) {
                Source source = findSource(qualifier, sources);
                if (source == null) {
                    return null;
                }
                TableRef origin = source.origin(column.column());
                if (origin != null) {
                    return origin;
                }
                return source.knownColumns() ? null : source.baseTable();
            }

            TableRef match = null;
            int matches = 0;
            for (Source source : sources) {
                if (source.hasColumn(column.column())) {
                    matches++;
                    match = source.origin(column.column());
                }
            }
            if (matches == 1) {
                return match;
            }
            if (matches > 1) {
                if (containsIgnoreCase(from.mergedColumns(), column.column())) {
                    for (Source source : sources) {
                        if (source.hasColumn(column.column())) {
                            return source.origin(column.column());
                        }
                    }
                }
                return null;
            }
            if (sources.size() == 1 && !sources.get(0).knownColumns()) {
                return sources.get(0).baseTable();
            }
            return null;
        }

        private Source findSource(String qualifier, List<Source> sources) {
            for (Source source : sources) {
                if (source.matchesQualifier(qualifier)) {
                    return source;
                }
            }
            return null;
        }

        private int implicitAlias(int start, int end, int depth) {
            if (end - start < 2) {
                return -1;
            }
            SqlTokenizer.Token last = tokens.get(end - 1);
            if (last.identifier() == null) {
                return -1;
            }
            if (last.type() == SqlTokenizer.Type.WORD
                    && NOT_AN_ALIAS.contains(SqlTokenizer.upper(last.text()))) {
                return -1;
            }
            SqlTokenizer.Token previous = tokens.get(end - 2);
            if (previous.isSymbol(".") || previous.isSymbol("(") || previous.isSymbol(",")) {
                return -1;
            }
            if (previous.type() == SqlTokenizer.Type.SYMBOL && !previous.isSymbol(")")) {
                return -1;
            }
            if (previous.type() == SqlTokenizer.Type.WORD
                    && NOT_AN_ALIAS.contains(SqlTokenizer.upper(previous.text()))) {
                return -1;
            }
            return end - 1;
        }

        private ColumnRef simpleColumn(int start, int end) {
            List<String> parts = new ArrayList<>();
            int index = start;
            String first = SqlTokenizer.identifierAt(tokens, index);
            if (first == null) {
                return null;
            }
            parts.add(first);
            index++;
            while (index < end) {
                if (!tokens.get(index).isSymbol(".")) {
                    return null;
                }
                index++;
                if (index >= end) {
                    return null;
                }
                String part = SqlTokenizer.identifierAt(tokens, index);
                if (part == null) {
                    return null;
                }
                parts.add(part);
                index++;
            }
            return parts.size() <= 3 ? new ColumnRef(parts) : null;
        }

        private ColumnRef qualifiedStar(int start, int end) {
            if (end - start < 3 || !tokens.get(end - 1).isSymbol("*")) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            int index = start;
            while (index < end - 1) {
                String part = SqlTokenizer.identifierAt(tokens, index);
                if (part == null) {
                    return null;
                }
                parts.add(part);
                index++;
                if (index == end - 1) {
                    break;
                }
                if (!tokens.get(index).isSymbol(".")) {
                    return null;
                }
                index++;
            }
            if (index != end - 1 || !tokens.get(end - 2).isSymbol(".")) {
                return null;
            }
            if (parts.size() < 1 || parts.size() > 2) {
                return null;
            }
            return new ColumnRef(parts);
        }

        private String expressionLabel(int start, int end) {
            StringBuilder label = new StringBuilder();
            for (int i = start; i < end; i++) {
                label.append(tokens.get(i).text());
            }
            return label.toString();
        }

        private int skipIndexModifiers(int start, int end, int depth) {
            int index = start;
            while (index < end) {
                if (SqlTokenizer.isWordAt(tokens, index, depth, "INDEXED")) {
                    index += 3;
                    continue;
                }
                if (SqlTokenizer.isWordAt(tokens, index, depth, "NOT")
                        && SqlTokenizer.isWordAt(tokens, index + 1, depth, "INDEXED")) {
                    index += 2;
                    continue;
                }
                break;
            }
            return index;
        }

        private int skipJoinCondition(int start, int end, int depth) {
            if (!SqlTokenizer.isWordAt(tokens, start, depth, "ON")
                    && !SqlTokenizer.isWordAt(tokens, start, depth, "USING")) {
                return start;
            }
            int index = start + 1;
            while (index < end) {
                if (tokens.get(index).depth() == depth) {
                    if (tokens.get(index).isSymbol(",") || isJoinStart(index, end, depth)) {
                        break;
                    }
                }
                index++;
            }
            return index;
        }

        private boolean isJoinStart(int index, int end, int depth) {
            if (index >= end) {
                return false;
            }
            SqlTokenizer.Token token = tokens.get(index);
            if (token.depth() != depth || token.type() != SqlTokenizer.Type.WORD) {
                return false;
            }
            String keyword = SqlTokenizer.upper(token.text());
            if (!JOIN_START.contains(keyword)) {
                return false;
            }
            if ("LEFT".equals(keyword) || "RIGHT".equals(keyword)) {
                return SqlTokenizer.isWordAt(tokens, index + 1, depth, "JOIN")
                        || SqlTokenizer.isWordAt(tokens, index + 1, depth, "OUTER");
            }
            return true;
        }

        private int skipToJoin(int start, int end, int depth) {
            for (int index = start; index < end; index++) {
                if (SqlTokenizer.isWordAt(tokens, index, depth, "JOIN")) {
                    return index;
                }
            }
            return -1;
        }

        private boolean isAlias(int index, int depth) {
            if (index >= tokens.size()) {
                return false;
            }
            SqlTokenizer.Token token = tokens.get(index);
            if (token.depth() != depth || token.identifier() == null) {
                return false;
            }
            if (token.type() == SqlTokenizer.Type.WORD) {
                String keyword = SqlTokenizer.upper(token.text());
                return !FROM_END.contains(keyword)
                        && !JOIN_START.contains(keyword)
                        && !NOT_AN_ALIAS.contains(keyword);
            }
            return true;
        }

        private List<String> usingColumns(int start, int end, int depth) {
            if (!SqlTokenizer.isSymbolAt(tokens, start + 1, "(", depth)) {
                return List.of();
            }
            int close = SqlTokenizer.matchingParen(tokens, start + 1);
            if (close < 0 || close > end) {
                return List.of();
            }
            return SqlTokenizer.identifiers(tokens, start + 2, close, depth + 1);
        }

    }

    private static boolean isViewDefinition(String sql) {
        if (sql == null) {
            return false;
        }
        List<SqlTokenizer.Token> tokens = SqlTokenizer.tokenize(sql);
        if (tokens == null) {
            return false;
        }
        int index = 0;
        if (!SqlTokenizer.isWordAt(tokens, index, 0, "CREATE")) {
            return false;
        }
        index++;
        if (SqlTokenizer.isWordAt(tokens, index, 0, "TEMP")
                || SqlTokenizer.isWordAt(tokens, index, 0, "TEMPORARY")) {
            index++;
        }
        return SqlTokenizer.isWordAt(tokens, index, 0, "VIEW");
    }

    private static void appendStarColumns(
            Source source, boolean merged, List<String> labels, List<TableRef> tables) {
        if (!source.knownColumns()) {
            return;
        }
        for (int i = 0; i < source.columns().size(); i++) {
            String label = source.columns().get(i);
            if (merged && containsIgnoreCase(labels, label)) {
                continue;
            }
            labels.add(label);
            tables.add(source.tables().get(i));
        }
    }

    private static void dropStarColumns(
            List<String> columns, int from, List<String> labels, List<TableRef> tables) {
        for (int i = labels.size() - 1; i >= from; i--) {
            if (containsIgnoreCase(columns, labels.get(i))) {
                labels.remove(i);
                tables.remove(i);
            }
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String candidate : values) {
            if (candidate.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
