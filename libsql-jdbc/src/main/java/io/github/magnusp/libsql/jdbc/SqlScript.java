package io.github.magnusp.libsql.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a SQL string into individual SQLite statements.
 *
 * <p>sqld accepts one statement per Hrana {@code Stmt}. A JDBC caller may hand the driver a
 * script, so the driver splits it locally and sends each statement in order on the connection's
 * stream. Semicolons inside quoted text and comments do not terminate a statement, and a trigger
 * body ({@code CREATE TRIGGER ... BEGIN ... END}) is one statement even though SQLite terminates
 * its inner statements with semicolons.
 *
 * <p>The splitter mirrors SQLite's lexical rules closely enough for statements the driver
 * executes itself. A malformed input is left to sqld to reject; the splitter never throws.
 */
final class SqlScript {

    private static final String CREATE = "CREATE";
    private static final String TRIGGER = "TRIGGER";

    private SqlScript() {}

    /** Returns the non-empty statements in {@code sql}, in order. */
    static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return statements;
        }

        int start = 0;
        int i = 0;
        boolean triggerCandidate = false;
        boolean triggerBody = false;
        boolean triggerEnd = false;
        int caseDepth = 0;
        List<String> leadingWords = new ArrayList<>(3);

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
                i = end < 0 ? sql.length() : end + 2;
                continue;
            }
            if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
                continue;
            }
            if (c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                i = end < 0 ? sql.length() : end + 1;
                continue;
            }
            if (isWordChar(c)) {
                int wordStart = i;
                while (i < sql.length() && isWordChar(sql.charAt(i))) {
                    i++;
                }
                String word = sql.substring(wordStart, i).toUpperCase(Locale.ROOT);
                if (leadingWords.size() < 3) {
                    leadingWords.add(word);
                }
                if (!triggerCandidate && isCreateTriggerPrefix(leadingWords)) {
                    triggerCandidate = true;
                }
                if (triggerCandidate) {
                    if (TRIGGER.equals(word)) {
                        // The TRIGGER keyword itself carries no body state.
                    } else if ("BEGIN".equals(word) && !triggerBody) {
                        triggerBody = true;
                    } else if ("CASE".equals(word)) {
                        caseDepth++;
                    } else if ("END".equals(word)) {
                        if (caseDepth > 0) {
                            caseDepth--;
                        } else if (triggerBody) {
                            triggerEnd = true;
                        }
                    }
                }
                continue;
            }
            if (c == ';') {
                if (triggerBody && !triggerEnd) {
                    i++;
                    continue;
                }
                addStatement(statements, sql, start, i);
                start = i + 1;
                triggerCandidate = false;
                triggerBody = false;
                triggerEnd = false;
                caseDepth = 0;
                leadingWords.clear();
                i++;
                continue;
            }
            i++;
        }

        addStatement(statements, sql, start, sql.length());
        return statements;
    }

    private static boolean isCreateTriggerPrefix(List<String> leadingWords) {
        if (leadingWords.size() < 2 || !CREATE.equals(leadingWords.get(0))) {
            return false;
        }
        String second = leadingWords.get(1);
        if (TRIGGER.equals(second)) {
            return true;
        }
        return ("TEMP".equals(second) || "TEMPORARY".equals(second))
                && leadingWords.size() >= 3
                && TRIGGER.equals(leadingWords.get(2));
    }

    private static void addStatement(List<String> statements, String sql, int start, int end) {
        String statement = sql.substring(start, end).strip();
        if (!statement.isEmpty() && hasSql(statement)) {
            statements.add(statement);
        }
    }

    /** True when {@code text} has more than whitespace and comments. */
    private static boolean hasSql(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                i += 2;
                while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
                continue;
            }
            return true;
        }
        return false;
    }

    /** Returns the index just past the closing quote, or the end of the text. */
    private static int skipQuoted(String sql, int start, char quote) {
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
        return sql.length();
    }

    private static boolean isWordChar(char c) {
        return c >= 0x80
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '$';
    }
}
