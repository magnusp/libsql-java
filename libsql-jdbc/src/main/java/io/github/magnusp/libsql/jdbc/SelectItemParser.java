package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.jdbc.SqlTableReference.ColumnRef;
import io.github.magnusp.libsql.jdbc.SqlTableReference.Resolution;
import io.github.magnusp.libsql.jdbc.SqlTableReference.ResolutionBuilder;
import io.github.magnusp.libsql.jdbc.SqlTableReference.Source;
import io.github.magnusp.libsql.jdbc.SqlTableReference.StatementParser;
import io.github.magnusp.libsql.jdbc.SqlTableReference.TableRef;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the select list of a statement: item boundaries, explicit and implicit aliases, {@code *}
 * expansion, simple column references, scalar subqueries, and fallback expression labels.
 */
final class SelectItemParser {

    private final SqlCursor cursor;
    private final StatementParser parser;
    private final FromClauseParser.FromClause from;
    private final Map<String, Source> scope;

    SelectItemParser(
            SqlCursor cursor,
            StatementParser parser,
            FromClauseParser.FromClause from,
            Map<String, Source> scope) {
        this.cursor = cursor;
        this.parser = parser;
        this.from = from;
        this.scope = scope;
    }

    Resolution parseItems(int start, int end) throws SQLException {
        ResolutionBuilder items = new ResolutionBuilder();
        int index = start;
        while (index < end) {
            int comma = cursor.indexOfSymbol(index, end, ",");
            int itemEnd = comma < 0 ? end : comma;
            parseItem(index, itemEnd, items);
            if (comma < 0) {
                break;
            }
            index = comma + 1;
        }
        return items.build();
    }

    private void parseItem(int start, int end, ResolutionBuilder items) throws SQLException {
        if (start >= end) {
            items.add("", null);
            return;
        }
        String alias = null;
        int expressionEnd = end;
        int as = cursor.indexOfWord(start, end, "AS");
        if (as >= 0 && as + 1 < end) {
            String candidate = cursor.identifierAt(as + 1);
            if (candidate != null) {
                alias = candidate;
                expressionEnd = as;
            }
        } else {
            int implicit = implicitAlias(start, end);
            if (implicit >= 0) {
                alias = cursor.identifierAt(implicit);
                expressionEnd = implicit;
            }
        }
        if (expressionEnd <= start) {
            items.add(alias != null ? alias : "", null);
            return;
        }

        if (expressionEnd - start == 1 && cursor.token(start).isSymbol("*")) {
            items.addAll(from.star());
            return;
        }

        ColumnRef qualifiedStar = qualifiedStar(start, expressionEnd);
        if (qualifiedStar != null) {
            Source source = findSource(
                    qualifiedStar.parts().get(qualifiedStar.parts().size() - 1), from.sources());
            if (source != null && source.knownColumns()) {
                items.addAll(source.columns(), source.tables());
            }
            return;
        }

        ColumnRef column = simpleColumn(start, expressionEnd);
        if (column != null) {
            items.add(alias != null ? alias : column.column(), resolveColumn(column, from));
            return;
        }

        if (cursor.token(start).isSymbol("(")
                && cursor.matchingParen(start) == expressionEnd - 1) {
            int innerDepth = cursor.depth() + 1;
            String innerKeyword = cursor.at(innerDepth).wordAt(start + 1);
            if ("SELECT".equals(innerKeyword) || "WITH".equals(innerKeyword)) {
                Resolution inner = parser.parseStatement(start + 1, expressionEnd - 1, innerDepth, scope);
                if (inner != null && inner.tables().size() == 1) {
                    items.add(alias != null ? alias : inner.labels().get(0), inner.tables().get(0));
                    return;
                }
            }
        }

        items.add(alias != null ? alias : expressionLabel(start, expressionEnd), null);
    }

    private TableRef resolveColumn(ColumnRef column, FromClauseParser.FromClause from) {
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
            if (ResolutionBuilder.containsIgnoreCase(from.mergedColumns(), column.column())) {
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

    private int implicitAlias(int start, int end) {
        if (end - start < 2) {
            return -1;
        }
        SqlTokenizer.Token last = cursor.token(end - 1);
        if (last.identifier() == null) {
            return -1;
        }
        if (last.type() == SqlTokenizer.Type.WORD
                && SqlTableReference.NOT_AN_ALIAS.contains(SqlTokenizer.upper(last.text()))) {
            return -1;
        }
        SqlTokenizer.Token previous = cursor.token(end - 2);
        if (previous.isSymbol(".") || previous.isSymbol("(") || previous.isSymbol(",")) {
            return -1;
        }
        if (previous.type() == SqlTokenizer.Type.SYMBOL && !previous.isSymbol(")")) {
            return -1;
        }
        if (previous.type() == SqlTokenizer.Type.WORD
                && SqlTableReference.NOT_AN_ALIAS.contains(SqlTokenizer.upper(previous.text()))) {
            return -1;
        }
        return end - 1;
    }

    private ColumnRef simpleColumn(int start, int end) {
        List<String> parts = new ArrayList<>();
        int index = start;
        String first = cursor.identifierAt(index);
        if (first == null) {
            return null;
        }
        parts.add(first);
        index++;
        while (index < end) {
            if (!cursor.token(index).isSymbol(".")) {
                return null;
            }
            index++;
            if (index >= end) {
                return null;
            }
            String part = cursor.identifierAt(index);
            if (part == null) {
                return null;
            }
            parts.add(part);
            index++;
        }
        return parts.size() <= 3 ? new ColumnRef(parts) : null;
    }

    private ColumnRef qualifiedStar(int start, int end) {
        if (end - start < 3 || !cursor.token(end - 1).isSymbol("*")) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        int index = start;
        while (index < end - 1) {
            String part = cursor.identifierAt(index);
            if (part == null) {
                return null;
            }
            parts.add(part);
            index++;
            if (index == end - 1) {
                break;
            }
            if (!cursor.token(index).isSymbol(".")) {
                return null;
            }
            index++;
        }
        if (index != end - 1 || !cursor.token(end - 2).isSymbol(".")) {
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
            label.append(cursor.token(i).text());
        }
        return label.toString();
    }
}
