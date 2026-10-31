package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.Row;
import io.github.magnusp.libsql.hrana.codec.Stmt;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.Value;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Holds the generated-key result of one statement.
 *
 * <p>The owning statement guards every method with its {@code stateLock}, the same lock that
 * guards the current result set. A key result is built from the rowid the server sent on the
 * statement result, never from a follow-up query. A statement that did not insert reports an empty
 * one-row-wide result whose column is named {@code 1}, matching sqlite-jdbc 3.53.4.0.
 */
final class GeneratedKeys {

    private static final Set<String> STATEMENT_KEYWORDS =
            Set.of("SELECT", "VALUES", "INSERT", "REPLACE", "UPDATE", "DELETE");

    private ResultSet current;

    void update(LibsqlStatement owner, String sql, StmtResult result) throws SQLException {
        clear();
        if (sql != null
                && result != null
                && result.lastInsertRowid() != null
                && isInsertSql(sql)) {
            current = resultSet(owner, List.of(result.lastInsertRowid()));
        }
    }

    /**
     * Builds the generated keys for a batch from the {@code BatchResult} step results. Each insert
     * step contributes one row, matching the java.sql shape of a result set with one row per
     * generated key. Steps that are not inserts contribute nothing because Hrana leaves {@code
     * last_insert_rowid} undefined for them.
     */
    void updateBatch(LibsqlStatement owner, List<Stmt> statements, List<StmtResult> results)
            throws SQLException {
        clear();
        List<Long> rowids = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            StmtResult result = results.get(i);
            String sql = statements.get(i).sql();
            if (result != null
                    && result.lastInsertRowid() != null
                    && sql != null
                    && isInsertSql(sql)) {
                rowids.add(result.lastInsertRowid());
            }
        }
        if (!rowids.isEmpty()) {
            current = resultSet(owner, rowids);
        }
    }

    ResultSet currentOrEmpty(LibsqlStatement owner) {
        if (current == null) {
            StmtResult empty = new StmtResult(List.of(new Col("1", null)), List.of(), 0, null);
            current = new LibsqlResultSet(owner, empty);
        }
        return current;
    }

    void clear() throws SQLException {
        ResultSet keys = current;
        current = null;
        if (keys instanceof LibsqlResultSet libsqlKeys) {
            libsqlKeys.closeDetached();
        } else if (keys != null) {
            keys.close();
        }
    }

    private static LibsqlResultSet resultSet(LibsqlStatement owner, List<Long> rowids) {
        List<Row> rows = new ArrayList<>(rowids.size());
        for (Long rowid : rowids) {
            rows.add(new Row(List.of(Value.of(rowid))));
        }
        StmtResult keys = new StmtResult(
                List.of(new Col("last_insert_rowid()", null)),
                rows,
                0,
                rowids.get(rowids.size() - 1));
        return new LibsqlResultSet(owner, keys);
    }

    private static boolean isInsertSql(String sql) {
        List<String> script = SqlScript.split(sql);
        String statementSql = script.isEmpty() ? sql : script.get(script.size() - 1);
        List<SqlTokenizer.Token> tokens = SqlTokenizer.tokenize(statementSql);
        if (tokens == null) {
            return false;
        }
        String first = SqlTokenizer.wordAt(tokens, 0, 0);
        if ("INSERT".equals(first) || "REPLACE".equals(first)) {
            return true;
        }
        if (!"WITH".equals(first)) {
            return false;
        }
        int index = SqlTokenizer.indexOfAnyWord(tokens, 1, tokens.size(), 0, STATEMENT_KEYWORDS);
        String statement = SqlTokenizer.wordAt(tokens, index, 0);
        return "INSERT".equals(statement) || "REPLACE".equals(statement);
    }
}
