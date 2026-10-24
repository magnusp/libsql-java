package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.jdbc.SqlTableReference.Resolution;
import com.github.magnusp.libsql.jdbc.SqlTableReference.Schema;
import com.github.magnusp.libsql.jdbc.SqlTableReference.Source;
import com.github.magnusp.libsql.jdbc.SqlTableReference.StatementParser;
import com.github.magnusp.libsql.jdbc.SqlTableReference.TableDefinition;
import com.github.magnusp.libsql.jdbc.SqlTableReference.TableRef;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the parser's {@code Source} view of a table, common table expression, or view.
 *
 * <p>A schema definition is read here, once per source occurrence, so the metadata cache warms
 * during parsing and a definition failure propagates to the caller. A view re-enters statement
 * parsing with the same schema and a shared cycle guard.
 */
final class TableBinding {

    private final Schema schema;
    private final Set<String> resolvingViews;

    TableBinding(Schema schema, Set<String> resolvingViews) {
        this.schema = schema;
        this.resolvingViews = resolvingViews;
    }

    Source cte(String name, List<String> columns, Resolution resolution) {
        return new Source(
                name,
                columns != null && columns.size() == resolution.labels().size()
                        ? columns
                        : resolution.labels(),
                resolution.tables(),
                true,
                null);
    }

    Source opaqueCte(String name, List<String> columns) {
        List<String> opaque = columns != null ? columns : List.of();
        return new Source(
                name, opaque, Collections.nCopies(opaque.size(), null), columns != null, null);
    }

    Source cteReference(Source cte, String qualifier) {
        return new Source(
                qualifier, cte.columns(), cte.tables(), cte.knownColumns(), cte.baseTable());
    }

    Source source(
            TableRef reference,
            String qualifier,
            StatementParser parser,
            Map<String, Source> scope)
            throws SQLException {
        TableDefinition definition = schema.definition(reference);
        if (definition == null) {
            return new Source(qualifier, List.of(), List.of(), false, reference);
        }
        if (isViewDefinition(definition.sql())) {
            Resolution view = resolveView(reference, definition, parser, scope);
            if (view == null) {
                return new Source(
                        qualifier,
                        definition.columns(),
                        Collections.nCopies(definition.columns().size(), null),
                        true,
                        null);
            }
            return new Source(qualifier, view.labels(), view.tables(), true, null);
        }
        List<String> columns = definition.columns() != null ? definition.columns() : List.of();
        return new Source(
                qualifier, columns, Collections.nCopies(columns.size(), reference), true, reference);
    }

    Source returningSource(TableRef reference, String qualifier) throws SQLException {
        TableDefinition definition = schema.definition(reference);
        if (definition == null) {
            return new Source(qualifier, List.of(), List.of(), false, reference);
        }
        if (isViewDefinition(definition.sql())) {
            List<String> columns = definition.columns();
            return new Source(
                    qualifier, columns, Collections.nCopies(columns.size(), null), true, null);
        }
        List<String> columns = definition.columns() != null ? definition.columns() : List.of();
        return new Source(
                qualifier, columns, Collections.nCopies(columns.size(), reference), true, reference);
    }

    private Resolution resolveView(
            TableRef reference,
            TableDefinition definition,
            StatementParser parser,
            Map<String, Source> scope)
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
            SqlCursor viewCursor = new SqlCursor(viewTokens, 0);
            int as = viewCursor.indexOfWord(0, viewTokens.size(), "AS");
            if (as < 0) {
                return null;
            }
            List<String> explicit = null;
            for (int i = 0; i < as; i++) {
                SqlTokenizer.Token token = viewCursor.token(i);
                if (token.depth() == 0 && token.isSymbol("(")) {
                    int close = viewCursor.matchingParen(i);
                    if (close < 0) {
                        return null;
                    }
                    explicit = viewCursor.at(1).identifiers(i + 1, close);
                    break;
                }
            }
            Resolution inner = parser
                    .onTokens(viewTokens)
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

    private static boolean isViewDefinition(String sql) {
        if (sql == null) {
            return false;
        }
        List<SqlTokenizer.Token> tokens = SqlTokenizer.tokenize(sql);
        if (tokens == null) {
            return false;
        }
        SqlCursor cursor = new SqlCursor(tokens, 0);
        int index = 0;
        if (!cursor.isWordAt(index, "CREATE")) {
            return false;
        }
        index++;
        if (cursor.isWordAt(index, "TEMP") || cursor.isWordAt(index, "TEMPORARY")) {
            index++;
        }
        return cursor.isWordAt(index, "VIEW");
    }
}
