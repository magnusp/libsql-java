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
import java.util.regex.Pattern;

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

    private enum Type {
        WORD,
        IDENT,
        SYMBOL
    }

    private record Token(Type type, String text, int depth) {}

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

    private static final Pattern UNQUOTED_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private static final Set<String> FROM_END = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "WINDOW", "RETURNING",
            "UNION", "INTERSECT", "EXCEPT");

    private static final Set<String> JOIN_START =
            Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER");

    private static final Set<String> SET_OPERATORS = Set.of("UNION", "INTERSECT", "EXCEPT");

    private static final Set<String> NOT_AN_ALIAS = Set.of(
            "AS", "ON", "USING", "INDEXED", "NOT", "COLLATE", "IS", "LIKE", "GLOB", "REGEXP",
            "MATCH", "ESCAPE", "BETWEEN", "IN", "CASE", "WHEN", "THEN", "ELSE", "END", "CAST",
            "EXISTS", "AND", "OR", "NULL");

    private SqlTableReference() {}

    static Resolution resolve(String sql, Schema schema) throws SQLException {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        List<Token> tokens = tokenize(sql);
        if (tokens == null) {
            return null;
        }
        return new Parser(tokens, schema, new HashSet<>())
                .parseStatement(0, tokens.size(), 0, new HashMap<>());
    }

    private static final class Parser {

        private final List<Token> tokens;
        private final Schema schema;
        private final Set<String> resolvingViews;

        private Parser(List<Token> tokens, Schema schema, Set<String> resolvingViews) {
            this.tokens = tokens;
            this.schema = schema;
            this.resolvingViews = resolvingViews;
        }

        private Resolution parseStatement(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            int index = skipSymbols(start, end, depth, ";");
            while (end > index && isSymbolAt(end - 1, ";", depth)) {
                end--;
            }
            if (index >= end) {
                return null;
            }
            String keyword = wordAt(index, depth);
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
            if (isWordAt(index, depth, "RECURSIVE")) {
                index++;
            }
            while (index < end) {
                String name = identifier(index);
                if (name == null) {
                    return null;
                }
                index++;
                List<String> columns = null;
                if (isSymbolAt(index, "(", depth)) {
                    int close = matchingParen(index);
                    if (close < 0 || close >= end) {
                        return null;
                    }
                    columns = identifiers(index + 1, close, depth + 1);
                    index = close + 1;
                }
                if (!isWordAt(index, depth, "AS")) {
                    return null;
                }
                index++;
                if (isWordAt(index, depth, "NOT")) {
                    index += 2;
                } else if (isWordAt(index, depth, "MATERIALIZED")) {
                    index++;
                }
                if (!isSymbolAt(index, "(", depth)) {
                    return null;
                }
                int close = matchingParen(index);
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
                }
                index = close + 1;
                if (isSymbolAt(index, ",", depth)) {
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
            while (isWordAt(index, depth, "ALL") || isWordAt(index, depth, "DISTINCT")) {
                index++;
            }
            int setOperator = indexOfAnyWord(index, end, depth, SET_OPERATORS);
            int selectEnd = setOperator >= 0 ? setOperator : end;
            int from = indexOfWord(index, selectEnd, depth, "FROM");
            int listEnd = from >= 0 ? from : selectEnd;
            List<Source> sources = List.of();
            if (from >= 0) {
                int fromEnd = indexOfAnyWord(from + 1, selectEnd, depth, FROM_END);
                if (fromEnd < 0) {
                    fromEnd = selectEnd;
                }
                List<Source> parsed = parseSources(from + 1, fromEnd, depth, scope);
                if (parsed == null || parsed.isEmpty()) {
                    return null;
                }
                sources = parsed;
            }
            return parseSelectItems(index, listEnd, depth, sources, scope);
        }

        private List<Source> parseSources(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            List<Source> sources = new ArrayList<>();
            int index = start;
            while (index < end) {
                SourceList parsed = parseSource(index, end, depth, scope);
                if (parsed == null) {
                    return null;
                }
                sources.addAll(parsed.sources());
                index = parsed.next();
                index = skipIndexModifiers(index, end, depth);
                index = skipJoinCondition(index, end, depth);
                if (isSymbolAt(index, ",", depth)) {
                    index++;
                    continue;
                }
                if (index < end && isJoinStart(index, end, depth)) {
                    index = skipToJoin(index, end, depth);
                    if (index < 0) {
                        break;
                    }
                    index++;
                    continue;
                }
                break;
            }
            return sources;
        }

        private SourceList parseSource(int start, int end, int depth, Map<String, Source> scope)
                throws SQLException {
            int index = start;
            if (isSymbolAt(index, "(", depth)) {
                int close = matchingParen(index);
                if (close < 0 || close >= end) {
                    return null;
                }
                int innerDepth = depth + 1;
                String innerKeyword = wordAt(index + 1, innerDepth);
                if ("SELECT".equals(innerKeyword) || "WITH".equals(innerKeyword)) {
                    Resolution inner = parseStatement(index + 1, close, innerDepth, scope);
                    index = close + 1;
                    String alias = null;
                    if (isWordAt(index, depth, "AS")) {
                        index++;
                        alias = identifier(index);
                        if (alias == null) {
                            return null;
                        }
                        index++;
                    } else if (isAlias(index, depth)) {
                        alias = identifier(index);
                        index++;
                    }
                    List<String> aliasColumns = null;
                    if (isSymbolAt(index, "(", depth)) {
                        int columnsEnd = matchingParen(index);
                        if (columnsEnd < 0 || columnsEnd >= end) {
                            return null;
                        }
                        aliasColumns = identifiers(index + 1, columnsEnd, depth + 1);
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
                List<Source> grouped = parseSources(index + 1, close, innerDepth, scope);
                if (grouped == null) {
                    return null;
                }
                index = close + 1;
                String alias = null;
                if (isWordAt(index, depth, "AS")) {
                    index++;
                    alias = identifier(index);
                    if (alias == null) {
                        return null;
                    }
                    index++;
                } else if (isAlias(index, depth)) {
                    alias = identifier(index);
                    index++;
                }
                if (alias == null) {
                    return new SourceList(grouped, index);
                }
                List<String> labels = new ArrayList<>();
                List<TableRef> tables = new ArrayList<>();
                for (Source source : grouped) {
                    if (source.knownColumns()) {
                        labels.addAll(source.columns());
                        tables.addAll(source.tables());
                    }
                }
                return new SourceList(
                        List.of(new Source(alias, labels, tables, true, null)), index);
            }

            String first = identifier(index);
            if (first == null) {
                return null;
            }
            index++;
            String schemaName = null;
            if (isSymbolAt(index, ".", depth)) {
                index++;
                String second = identifier(index);
                if (second == null) {
                    return null;
                }
                index++;
                if (isSymbolAt(index, ".", depth)) {
                    return null;
                }
                schemaName = first;
                first = second;
            }
            String alias = null;
            if (isWordAt(index, depth, "AS")) {
                index++;
                alias = identifier(index);
                if (alias == null) {
                    return null;
                }
                index++;
            } else if (isAlias(index, depth)) {
                alias = identifier(index);
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
                List<Token> viewTokens = tokenize(definition.sql());
                if (viewTokens == null) {
                    return null;
                }
                int as = indexOfWordIn(viewTokens, 0, viewTokens.size(), 0, "AS");
                if (as < 0) {
                    return null;
                }
                List<String> explicit = null;
                for (int i = 0; i < as; i++) {
                    Token token = viewTokens.get(i);
                    if (token.depth() == 0 && isSymbol(token, "(")) {
                        int close = matchingParenIn(viewTokens, i);
                        if (close < 0) {
                            return null;
                        }
                        explicit = identifiersIn(viewTokens, i + 1, close, 1);
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
            int returning = indexOfWord(start, end, depth, "RETURNING");
            if (returning < 0) {
                return null;
            }
            TableRef target = targetTable(start, returning, depth);
            if (target == null) {
                return null;
            }
            TableDefinition definition = schema.definition(target);
            Source source;
            if (definition == null) {
                source = new Source(target.table(), List.of(), List.of(), false, target);
            } else if (isViewDefinition(definition.sql())) {
                List<String> columns = definition.columns();
                source = new Source(
                        target.table(), columns, Collections.nCopies(columns.size(), null), true, null);
            } else {
                List<String> columns = definition.columns() != null ? definition.columns() : List.of();
                source = new Source(
                        target.table(),
                        columns,
                        Collections.nCopies(columns.size(), target),
                        true,
                        target);
            }
            return parseSelectItems(returning + 1, end, depth, List.of(source), scope);
        }

        private TableRef targetTable(int start, int end, int depth) {
            String keyword = wordAt(start, depth);
            if (keyword == null) {
                return null;
            }
            int index;
            switch (keyword) {
                case "INSERT", "REPLACE" -> {
                    index = indexOfWord(start, end, depth, "INTO");
                    if (index < 0) {
                        return null;
                    }
                    index++;
                }
                case "UPDATE" -> {
                    index = start + 1;
                    if (isWordAt(index, depth, "OR")) {
                        index += 2;
                    }
                }
                case "DELETE" -> {
                    index = indexOfWord(start, end, depth, "FROM");
                    if (index < 0) {
                        return null;
                    }
                    index++;
                }
                default -> {
                    return null;
                }
            }
            String first = identifier(index);
            if (first == null) {
                return null;
            }
            index++;
            String schemaName = null;
            if (isSymbolAt(index, ".", depth)) {
                index++;
                String second = identifier(index);
                if (second == null) {
                    return null;
                }
                schemaName = first;
                first = second;
            }
            return new TableRef(schemaName, first);
        }

        private Resolution parseSelectItems(
                int start, int end, int depth, List<Source> sources, Map<String, Source> scope)
                throws SQLException {
            List<String> labels = new ArrayList<>();
            List<TableRef> tables = new ArrayList<>();
            int index = start;
            while (index < end) {
                int comma = indexOfSymbol(index, end, depth, ",");
                int itemEnd = comma < 0 ? end : comma;
                parseItem(index, itemEnd, depth, sources, scope, labels, tables);
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
                List<Source> sources,
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
            int as = indexOfWord(start, end, depth, "AS");
            if (as >= 0 && as + 1 < end) {
                String candidate = identifier(as + 1);
                if (candidate != null) {
                    alias = candidate;
                    expressionEnd = as;
                }
            } else {
                int implicit = implicitAlias(start, end, depth);
                if (implicit >= 0) {
                    alias = identifier(implicit);
                    expressionEnd = implicit;
                }
            }
            if (expressionEnd <= start) {
                labels.add(alias != null ? alias : "");
                tables.add(null);
                return;
            }

            if (expressionEnd - start == 1 && isSymbol(tokens.get(start), "*")) {
                for (Source source : sources) {
                    if (source.knownColumns()) {
                        labels.addAll(source.columns());
                        tables.addAll(source.tables());
                    }
                }
                return;
            }

            ColumnRef qualifiedStar = qualifiedStar(start, expressionEnd);
            if (qualifiedStar != null) {
                Source source =
                        findSource(qualifiedStar.parts().get(qualifiedStar.parts().size() - 1), sources);
                if (source != null && source.knownColumns()) {
                    labels.addAll(source.columns());
                    tables.addAll(source.tables());
                }
                return;
            }

            ColumnRef column = simpleColumn(start, expressionEnd);
            if (column != null) {
                labels.add(alias != null ? alias : column.column());
                tables.add(resolveColumn(column, sources));
                return;
            }

            if (isSymbol(tokens.get(start), "(") && matchingParen(start) == expressionEnd - 1) {
                int innerDepth = depth + 1;
                String innerKeyword = wordAt(start + 1, innerDepth);
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

        private TableRef resolveColumn(ColumnRef column, List<Source> sources) {
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
            Token last = tokens.get(end - 1);
            if (identifierOf(last) == null) {
                return -1;
            }
            Token previous = tokens.get(end - 2);
            if (isSymbol(previous, ".") || isSymbol(previous, "(") || isSymbol(previous, ",")) {
                return -1;
            }
            if (previous.type() == Type.SYMBOL) {
                return -1;
            }
            if (previous.type() == Type.WORD && NOT_AN_ALIAS.contains(upper(previous.text()))) {
                return -1;
            }
            return end - 1;
        }

        private ColumnRef simpleColumn(int start, int end) {
            List<String> parts = new ArrayList<>();
            int index = start;
            String first = identifier(index);
            if (first == null) {
                return null;
            }
            parts.add(first);
            index++;
            while (index < end) {
                if (!isSymbol(tokens.get(index), ".")) {
                    return null;
                }
                index++;
                if (index >= end) {
                    return null;
                }
                String part = identifier(index);
                if (part == null) {
                    return null;
                }
                parts.add(part);
                index++;
            }
            return parts.size() <= 3 ? new ColumnRef(parts) : null;
        }

        private ColumnRef qualifiedStar(int start, int end) {
            if (end - start < 3 || !isSymbol(tokens.get(end - 1), "*")) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            int index = start;
            while (index < end - 1) {
                String part = identifier(index);
                if (part == null) {
                    return null;
                }
                parts.add(part);
                index++;
                if (index == end - 1) {
                    break;
                }
                if (!isSymbol(tokens.get(index), ".")) {
                    return null;
                }
                index++;
            }
            if (index != end - 1 || !isSymbol(tokens.get(end - 2), ".")) {
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
                if (isWordAt(index, depth, "INDEXED")) {
                    index += 3;
                    continue;
                }
                if (isWordAt(index, depth, "NOT") && isWordAt(index + 1, depth, "INDEXED")) {
                    index += 3;
                    continue;
                }
                break;
            }
            return index;
        }

        private int skipJoinCondition(int start, int end, int depth) {
            if (!isWordAt(start, depth, "ON") && !isWordAt(start, depth, "USING")) {
                return start;
            }
            int index = start + 1;
            while (index < end) {
                if (tokens.get(index).depth() == depth) {
                    if (isSymbol(tokens.get(index), ",") || isJoinStart(index, end, depth)) {
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
            Token token = tokens.get(index);
            return token.depth() == depth
                    && token.type() == Type.WORD
                    && JOIN_START.contains(upper(token.text()));
        }

        private int skipToJoin(int start, int end, int depth) {
            for (int index = start; index < end; index++) {
                if (isWordAt(index, depth, "JOIN")) {
                    return index;
                }
            }
            return -1;
        }

        private boolean isAlias(int index, int depth) {
            if (index >= tokens.size()) {
                return false;
            }
            Token token = tokens.get(index);
            if (token.depth() != depth || identifierOf(token) == null) {
                return false;
            }
            if (token.type() == Type.WORD) {
                String keyword = upper(token.text());
                return !FROM_END.contains(keyword)
                        && !JOIN_START.contains(keyword)
                        && !NOT_AN_ALIAS.contains(keyword);
            }
            return true;
        }

        private List<String> identifiers(int start, int end, int depth) {
            List<String> identifiers = new ArrayList<>();
            int index = start;
            while (index < end) {
                String identifier = identifier(index);
                if (identifier == null || tokens.get(index).depth() != depth) {
                    return List.of();
                }
                identifiers.add(identifier);
                index++;
                if (index < end) {
                    if (!isSymbol(tokens.get(index), ",")) {
                        return List.of();
                    }
                    index++;
                }
            }
            return identifiers;
        }

        private int skipSymbols(int start, int end, int depth, String symbol) {
            int index = start;
            while (index < end && isSymbolAt(index, symbol, depth)) {
                index++;
            }
            return index;
        }

        private int indexOfWord(int start, int end, int depth, String word) {
            for (int index = start; index < end; index++) {
                if (isWordAt(index, depth, word)) {
                    return index;
                }
            }
            return -1;
        }

        private int indexOfAnyWord(int start, int end, int depth, Set<String> words) {
            for (int index = start; index < end; index++) {
                Token token = tokens.get(index);
                if (token.depth() == depth && token.type() == Type.WORD && words.contains(upper(token.text()))) {
                    return index;
                }
            }
            return -1;
        }

        private int indexOfSymbol(int start, int end, int depth, String symbol) {
            for (int index = start; index < end; index++) {
                if (isSymbolAt(index, symbol, depth)) {
                    return index;
                }
            }
            return -1;
        }

        private boolean isWordAt(int index, int depth, String word) {
            if (index < 0 || index >= tokens.size()) {
                return false;
            }
            Token token = tokens.get(index);
            return token.depth() == depth && token.type() == Type.WORD && upper(token.text()).equals(word);
        }

        private boolean isSymbolAt(int index, String symbol, int depth) {
            if (index < 0 || index >= tokens.size()) {
                return false;
            }
            Token token = tokens.get(index);
            return token.depth() == depth && isSymbol(token, symbol);
        }

        private String wordAt(int index, int depth) {
            if (index < 0 || index >= tokens.size()) {
                return null;
            }
            Token token = tokens.get(index);
            return token.depth() == depth && token.type() == Type.WORD ? upper(token.text()) : null;
        }

        private String identifier(int index) {
            if (index < 0 || index >= tokens.size()) {
                return null;
            }
            return identifierOf(tokens.get(index));
        }

        private int matchingParen(int open) {
            return matchingParenIn(tokens, open);
        }
    }

    private static int matchingParenIn(List<Token> tokens, int open) {
        if (open < 0 || open >= tokens.size()) {
            return -1;
        }
        int depth = tokens.get(open).depth();
        for (int index = open + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.depth() == depth && isSymbol(token, ")")) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOfWordIn(List<Token> tokens, int start, int end, int depth, String word) {
        for (int index = start; index < end; index++) {
            Token token = tokens.get(index);
            if (token.depth() == depth && token.type() == Type.WORD && upper(token.text()).equals(word)) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> identifiersIn(List<Token> tokens, int start, int end, int depth) {
        List<String> identifiers = new ArrayList<>();
        int index = start;
        while (index < end) {
            Token token = tokens.get(index);
            if (token.depth() != depth) {
                return List.of();
            }
            String identifier = identifierOf(token);
            if (identifier == null) {
                return List.of();
            }
            identifiers.add(identifier);
            index++;
            if (index < end) {
                Token separator = tokens.get(index);
                if (separator.depth() != depth || !isSymbol(separator, ",")) {
                    return List.of();
                }
                index++;
            }
        }
        return identifiers;
    }

    private static boolean isViewDefinition(String sql) {
        if (sql == null) {
            return false;
        }
        List<Token> tokens = tokenize(sql);
        if (tokens == null) {
            return false;
        }
        int index = 0;
        if (!isWord(tokens, index, "CREATE")) {
            return false;
        }
        index++;
        if (isWord(tokens, index, "TEMP") || isWord(tokens, index, "TEMPORARY")) {
            index++;
        }
        return isWord(tokens, index, "VIEW");
    }

    private static boolean isWord(List<Token> tokens, int index, String word) {
        if (index < 0 || index >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(index);
        return token.depth() == 0 && token.type() == Type.WORD && upper(token.text()).equals(word);
    }

    private static boolean isSymbol(Token token, String symbol) {
        return token.type() == Type.SYMBOL && token.text().equals(symbol);
    }

    private static String identifierOf(Token token) {
        if (token.type() == Type.IDENT) {
            return token.text();
        }
        if (token.type() == Type.WORD && UNQUOTED_IDENTIFIER.matcher(token.text()).matches()) {
            return token.text();
        }
        return null;
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
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
                i = scanQuoted(sql, i, '\'');
                if (i < 0) {
                    return null;
                }
                continue;
            }
            if (c == '"' || c == '`') {
                int end = scanQuoted(sql, i, c);
                if (end < 0) {
                    return null;
                }
                tokens.add(new Token(Type.IDENT, unescape(sql.substring(i + 1, end - 1), c), depth));
                i = end;
                continue;
            }
            if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                if (end < 0) {
                    return null;
                }
                tokens.add(new Token(Type.IDENT, sql.substring(i + 1, end), depth));
                i = end + 1;
                continue;
            }
            if (c == '(') {
                tokens.add(new Token(Type.SYMBOL, "(", depth));
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth == 0) {
                    return null;
                }
                depth--;
                tokens.add(new Token(Type.SYMBOL, ")", depth));
                i++;
                continue;
            }
            if (c == ',' || c == '.' || c == ';' || c == '*') {
                tokens.add(new Token(Type.SYMBOL, String.valueOf(c), depth));
                i++;
                continue;
            }
            if (isWordChar(c)) {
                int start = i;
                while (i < sql.length() && isWordChar(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Type.WORD, sql.substring(start, i), depth));
                continue;
            }
            tokens.add(new Token(Type.SYMBOL, String.valueOf(c), depth));
            i++;
        }
        if (depth != 0) {
            return null;
        }
        return tokens;
    }

    private static int scanQuoted(String sql, int start, char quote) {
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
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '$';
    }
}
