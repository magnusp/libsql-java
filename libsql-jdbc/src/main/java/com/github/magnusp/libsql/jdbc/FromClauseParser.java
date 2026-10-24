package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.jdbc.SqlTableReference.Resolution;
import com.github.magnusp.libsql.jdbc.SqlTableReference.ResolutionBuilder;
import com.github.magnusp.libsql.jdbc.SqlTableReference.Source;
import com.github.magnusp.libsql.jdbc.SqlTableReference.StatementParser;
import com.github.magnusp.libsql.jdbc.SqlTableReference.TableRef;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a FROM clause: its sources, joins, index hints, and {@code USING} columns, and the star
 * list that {@code *} expands to. The star list drops the right-side duplicate of a {@code USING}
 * or {@code NATURAL} join column, as SQLite does.
 */
final class FromClauseParser {

    record SourceList(List<Source> sources, int next) {}

    record FromClause(
            List<Source> sources, ResolutionBuilder star, List<String> mergedColumns) {

        static FromClause of(List<Source> sources) {
            ResolutionBuilder star = new ResolutionBuilder();
            for (Source source : sources) {
                appendStarColumns(source, false, star);
            }
            return new FromClause(sources, star, List.of());
        }
    }

    private final SqlCursor cursor;
    private final TableBinding binding;
    private final StatementParser parser;
    private final Map<String, Source> scope;

    FromClauseParser(
            SqlCursor cursor,
            TableBinding binding,
            StatementParser parser,
            Map<String, Source> scope) {
        this.cursor = cursor;
        this.binding = binding;
        this.parser = parser;
        this.scope = scope;
    }

    FromClause parseSources(int start, int end) throws SQLException {
        List<Source> sources = new ArrayList<>();
        ResolutionBuilder star = new ResolutionBuilder();
        List<String> mergedColumns = new ArrayList<>();
        boolean natural = false;
        int index = start;
        while (index < end) {
            SourceList parsed = parseSource(index, end);
            if (parsed == null) {
                return null;
            }
            int starStart = star.size();
            for (Source source : parsed.sources()) {
                sources.add(source);
                appendStarColumns(source, natural, star);
            }
            index = parsed.next();
            index = skipIndexModifiers(index, end);
            if (cursor.isWordAt(index, "USING")) {
                List<String> merged = usingColumns(index, end);
                star.dropLabels(merged, starStart);
                for (String column : merged) {
                    if (!ResolutionBuilder.containsIgnoreCase(mergedColumns, column)) {
                        mergedColumns.add(column);
                    }
                }
            }
            index = skipJoinCondition(index, end);
            if (cursor.isSymbolAt(index, ",")) {
                natural = false;
                index++;
                continue;
            }
            if (index < end && isJoinStart(index, end)) {
                natural = cursor.isWordAt(index, "NATURAL");
                index = skipToJoin(index, end);
                if (index < 0) {
                    break;
                }
                index++;
                continue;
            }
            break;
        }
        return new FromClause(sources, star, mergedColumns);
    }

    private SourceList parseSource(int start, int end) throws SQLException {
        int index = start;
        if (cursor.isSymbolAt(index, "(")) {
            int close = cursor.matchingParen(index);
            if (close < 0 || close >= end) {
                return null;
            }
            int innerDepth = cursor.depth() + 1;
            String innerKeyword = cursor.at(innerDepth).wordAt(index + 1);
            if ("SELECT".equals(innerKeyword) || "WITH".equals(innerKeyword)) {
                Resolution inner = parser.parseStatement(index + 1, close, innerDepth, scope);
                index = close + 1;
                String alias = null;
                if (cursor.isWordAt(index, "AS")) {
                    index++;
                    alias = cursor.identifierAt(index);
                    if (alias == null) {
                        return null;
                    }
                    index++;
                } else if (isAlias(index)) {
                    alias = cursor.identifierAt(index);
                    index++;
                }
                List<String> aliasColumns = null;
                if (cursor.isSymbolAt(index, "(")) {
                    int columnsEnd = cursor.matchingParen(index);
                    if (columnsEnd < 0 || columnsEnd >= end) {
                        return null;
                    }
                    aliasColumns = cursor.at(innerDepth).identifiers(index + 1, columnsEnd);
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
            FromClause grouped = new FromClauseParser(cursor.at(innerDepth), binding, parser, scope)
                    .parseSources(index + 1, close);
            if (grouped == null) {
                return null;
            }
            index = close + 1;
            String alias = null;
            if (cursor.isWordAt(index, "AS")) {
                index++;
                alias = cursor.identifierAt(index);
                if (alias == null) {
                    return null;
                }
                index++;
            } else if (isAlias(index)) {
                alias = cursor.identifierAt(index);
                index++;
            }
            if (alias == null) {
                return new SourceList(grouped.sources(), index);
            }
            return new SourceList(
                    List.of(new Source(
                            alias, grouped.star().labels(), grouped.star().tables(), true, null)),
                    index);
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
            index++;
            if (cursor.isSymbolAt(index, ".")) {
                return null;
            }
            schemaName = first;
            first = second;
        }
        String alias = null;
        if (cursor.isWordAt(index, "AS")) {
            index++;
            alias = cursor.identifierAt(index);
            if (alias == null) {
                return null;
            }
            index++;
        } else if (isAlias(index)) {
            alias = cursor.identifierAt(index);
            index++;
        }
        SqlTableReference.TableRef reference = new TableRef(schemaName, first);
        String qualifier = alias != null ? alias : first;
        if (schemaName == null) {
            Source cte = scope.get(first.toLowerCase(Locale.ROOT));
            if (cte != null) {
                return new SourceList(List.of(binding.cteReference(cte, qualifier)), index);
            }
        }
        return new SourceList(List.of(binding.source(reference, qualifier, parser, scope)), index);
    }

    private int skipIndexModifiers(int start, int end) {
        int index = start;
        while (index < end) {
            if (cursor.isWordAt(index, "INDEXED")) {
                index += 3;
                continue;
            }
            if (cursor.isWordAt(index, "NOT") && cursor.isWordAt(index + 1, "INDEXED")) {
                index += 2;
                continue;
            }
            break;
        }
        return index;
    }

    private int skipJoinCondition(int start, int end) {
        if (!cursor.isWordAt(start, "ON") && !cursor.isWordAt(start, "USING")) {
            return start;
        }
        int index = start + 1;
        while (index < end) {
            if (cursor.token(index).depth() == cursor.depth()) {
                if (cursor.token(index).isSymbol(",") || isJoinStart(index, end)) {
                    break;
                }
            }
            index++;
        }
        return index;
    }

    private boolean isJoinStart(int index, int end) {
        if (index >= end) {
            return false;
        }
        SqlTokenizer.Token token = cursor.token(index);
        if (token.depth() != cursor.depth() || token.type() != SqlTokenizer.Type.WORD) {
            return false;
        }
        String keyword = SqlTokenizer.upper(token.text());
        if (!SqlTableReference.JOIN_START.contains(keyword)) {
            return false;
        }
        if ("LEFT".equals(keyword) || "RIGHT".equals(keyword)) {
            return cursor.isWordAt(index + 1, "JOIN") || cursor.isWordAt(index + 1, "OUTER");
        }
        return true;
    }

    private int skipToJoin(int start, int end) {
        for (int index = start; index < end; index++) {
            if (cursor.isWordAt(index, "JOIN")) {
                return index;
            }
        }
        return -1;
    }

    private boolean isAlias(int index) {
        if (index >= cursor.size()) {
            return false;
        }
        SqlTokenizer.Token token = cursor.token(index);
        if (token.depth() != cursor.depth() || token.identifier() == null) {
            return false;
        }
        if (token.type() == SqlTokenizer.Type.WORD) {
            String keyword = SqlTokenizer.upper(token.text());
            return !SqlTableReference.FROM_END.contains(keyword)
                    && !SqlTableReference.JOIN_START.contains(keyword)
                    && !SqlTableReference.NOT_AN_ALIAS.contains(keyword);
        }
        return true;
    }

    private List<String> usingColumns(int start, int end) {
        if (!cursor.isSymbolAt(start + 1, "(")) {
            return List.of();
        }
        int close = cursor.matchingParen(start + 1);
        if (close < 0 || close > end) {
            return List.of();
        }
        return cursor.at(cursor.depth() + 1).identifiers(start + 2, close);
    }

    private static void appendStarColumns(Source source, boolean merged, ResolutionBuilder star) {
        if (!source.knownColumns()) {
            return;
        }
        for (int i = 0; i < source.columns().size(); i++) {
            String label = source.columns().get(i);
            if (merged && star.containsLabel(label)) {
                continue;
            }
            star.add(label, source.tables().get(i));
        }
    }
}
