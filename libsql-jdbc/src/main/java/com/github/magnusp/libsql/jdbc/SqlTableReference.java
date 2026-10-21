package com.github.magnusp.libsql.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class SqlTableReference {

    record TableRef(String schema, String table) {}

    private enum Type {
        WORD,
        IDENT,
        SYMBOL
    }

    private record Token(Type type, String text, int depth) {}

    private static final Pattern UNQUOTED_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private static final Set<String> REJECTED_KEYWORDS =
            Set.of("WITH", "UNION", "INTERSECT", "EXCEPT", "JOIN");

    private static final Set<String> FROM_END_KEYWORDS = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "WINDOW", "RETURNING",
            "UNION", "INTERSECT", "EXCEPT", "FROM", "JOIN", "CROSS", "INNER", "LEFT", "RIGHT",
            "FULL", "NATURAL", "OUTER", "ON", "USING", "INDEXED", "NOT", "AS");

    private SqlTableReference() {}

    static TableRef parse(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        List<Token> tokens = tokenize(sql);
        if (tokens == null) {
            return null;
        }

        int fromIndex = -1;
        int fromCount = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.depth() != 0) {
                if (fromIndex < 0
                        && token.type() == Type.WORD
                        && (upper(token.text()).equals("SELECT") || upper(token.text()).equals("FROM"))) {
                    return null;
                }
                continue;
            }
            if (token.type() != Type.WORD) {
                continue;
            }
            String keyword = upper(token.text());
            if (REJECTED_KEYWORDS.contains(keyword)) {
                return null;
            }
            if (keyword.equals("FROM")) {
                fromCount++;
                if (fromIndex < 0) {
                    fromIndex = i;
                }
            }
        }
        if (fromCount != 1) {
            return null;
        }

        int cursor = fromIndex + 1;
        if (cursor >= tokens.size()) {
            return null;
        }

        Token first = tokens.get(cursor);
        String firstName = identifier(first);
        if (firstName == null) {
            return null;
        }
        cursor++;

        String schema = null;
        String table = firstName;
        if (cursor < tokens.size() && isSymbol(tokens.get(cursor), ".")) {
            cursor++;
            if (cursor >= tokens.size()) {
                return null;
            }
            Token tableToken = tokens.get(cursor);
            String tableName = identifier(tableToken);
            if (tableName == null) {
                return null;
            }
            schema = firstName;
            table = tableName;
            cursor++;
            if (cursor < tokens.size() && isSymbol(tokens.get(cursor), ".")) {
                return null;
            }
        }

        if (cursor < tokens.size()) {
            Token next = tokens.get(cursor);
            if (isKeyword(next, "AS")) {
                cursor++;
                if (cursor >= tokens.size() || identifier(tokens.get(cursor)) == null) {
                    return null;
                }
                cursor++;
            } else if (next.type() != Type.SYMBOL && !isFromEnd(next)) {
                if (identifier(next) == null) {
                    return null;
                }
                cursor++;
            }
        }

        if (cursor >= tokens.size() || isSymbol(tokens.get(cursor), ";") || isFromEnd(tokens.get(cursor))) {
            return new TableRef(schema, table);
        }
        return null;
    }

    private static boolean isFromEnd(Token token) {
        return token.depth() == 0 && token.type() == Type.WORD
                && FROM_END_KEYWORDS.contains(upper(token.text()));
    }

    private static boolean isKeyword(Token token, String keyword) {
        return token.depth() == 0 && token.type() == Type.WORD && upper(token.text()).equals(keyword);
    }

    private static boolean isSymbol(Token token, String symbol) {
        return token.depth() == 0 && token.type() == Type.SYMBOL && token.text().equals(symbol);
    }

    private static String identifier(Token token) {
        if (token.depth() != 0) {
            return null;
        }
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
            if (c == ',' || c == '.' || c == ';') {
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
