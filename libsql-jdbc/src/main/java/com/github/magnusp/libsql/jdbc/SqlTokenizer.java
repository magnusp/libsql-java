package com.github.magnusp.libsql.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tokenizes SQL text that the driver inspects locally for result metadata.
 *
 * <p>Each token carries its type, its enclosing parenthesis depth, and, for literals, the literal
 * kind. A tokenizer failure, such as an unterminated quote or an unbalanced parenthesis, returns
 * {@code null} so callers can fall back to the protocol metadata.
 */
final class SqlTokenizer {

    enum Type {
        WORD,
        IDENT,
        LITERAL,
        SYMBOL
    }

    enum LiteralKind {
        NONE,
        STRING,
        BLOB
    }

    record Token(Type type, String text, int depth, LiteralKind literalKind) {

        Token(Type type, String text, int depth) {
            this(type, text, depth, LiteralKind.NONE);
        }

        boolean isWord(String word) {
            return type == Type.WORD && text.equalsIgnoreCase(word);
        }

        boolean isSymbol(String symbol) {
            return type == Type.SYMBOL && text.equals(symbol);
        }

        String identifier() {
            if (type == Type.IDENT) {
                return text;
            }
            if (type == Type.WORD && UNQUOTED_IDENTIFIER.matcher(text).matches()) {
                return text;
            }
            return null;
        }
    }

    private static final Pattern UNQUOTED_IDENTIFIER =
            Pattern.compile("[A-Za-z_\\x80-\\uFFFF][A-Za-z0-9_$\\x80-\\uFFFF]*");

    private SqlTokenizer() {}

    static List<Token> tokenize(String sql) {
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
            if (c == '\'' || ((c == 'x' || c == 'X') && i + 1 < sql.length() && sql.charAt(i + 1) == '\'')) {
                boolean blob = c != '\'';
                int end = scanQuoted(sql, blob ? i + 1 : i, '\'');
                if (end < 0) {
                    return null;
                }
                tokens.add(new Token(
                        Type.LITERAL,
                        sql.substring(i, end),
                        depth,
                        blob ? LiteralKind.BLOB : LiteralKind.STRING));
                i = end;
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

    static boolean isWordAt(List<Token> tokens, int index, int depth, String word) {
        if (index < 0 || index >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(index);
        return token.depth() == depth && token.isWord(word);
    }

    static boolean isSymbolAt(List<Token> tokens, int index, String symbol, int depth) {
        if (index < 0 || index >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(index);
        return token.depth() == depth && token.isSymbol(symbol);
    }

    static String wordAt(List<Token> tokens, int index, int depth) {
        if (index < 0 || index >= tokens.size()) {
            return null;
        }
        Token token = tokens.get(index);
        return token.depth() == depth && token.type() == Type.WORD ? upper(token.text()) : null;
    }

    static String identifierAt(List<Token> tokens, int index) {
        if (index < 0 || index >= tokens.size()) {
            return null;
        }
        return tokens.get(index).identifier();
    }

    static int indexOfWord(List<Token> tokens, int start, int end, int depth, String word) {
        for (int index = start; index < end; index++) {
            if (isWordAt(tokens, index, depth, word)) {
                return index;
            }
        }
        return -1;
    }

    static int indexOfAnyWord(List<Token> tokens, int start, int end, int depth, Set<String> words) {
        for (int index = start; index < end; index++) {
            Token token = tokens.get(index);
            if (token.depth() == depth && token.type() == Type.WORD && words.contains(upper(token.text()))) {
                return index;
            }
        }
        return -1;
    }

    static int indexOfSymbol(List<Token> tokens, int start, int end, int depth, String symbol) {
        for (int index = start; index < end; index++) {
            if (isSymbolAt(tokens, index, symbol, depth)) {
                return index;
            }
        }
        return -1;
    }

    static int matchingParen(List<Token> tokens, int open) {
        if (open < 0 || open >= tokens.size()) {
            return -1;
        }
        int depth = tokens.get(open).depth();
        for (int index = open + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.depth() == depth && token.isSymbol(")")) {
                return index;
            }
        }
        return -1;
    }

    static List<String> identifiers(List<Token> tokens, int start, int end, int depth) {
        List<String> identifiers = new ArrayList<>();
        int index = start;
        while (index < end) {
            String identifier = identifierAt(tokens, index);
            if (identifier == null || tokens.get(index).depth() != depth) {
                return List.of();
            }
            identifiers.add(identifier);
            index++;
            if (index < end) {
                if (!isSymbolAt(tokens, index, ",", depth)) {
                    return List.of();
                }
                index++;
            }
        }
        return identifiers;
    }

    static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
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
        return c >= 0x80
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '$';
    }
}
