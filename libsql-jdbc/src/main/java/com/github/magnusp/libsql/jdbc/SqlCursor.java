package com.github.magnusp.libsql.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A read-only position over a token list at one parenthesis depth.
 *
 * <p>The cursor owns the bounds guards and the depth comparisons that the parser otherwise repeats
 * at every call site. Depth follows the tokenizer convention: an opening parenthesis carries the
 * depth outside the group, and the closing parenthesis carries the depth inside it.
 */
final class SqlCursor {

    private final List<SqlTokenizer.Token> tokens;
    private final int depth;

    SqlCursor(List<SqlTokenizer.Token> tokens, int depth) {
        this.tokens = tokens;
        this.depth = depth;
    }

    SqlCursor at(int depth) {
        return depth == this.depth ? this : new SqlCursor(tokens, depth);
    }

    int depth() {
        return depth;
    }

    int size() {
        return tokens.size();
    }

    SqlTokenizer.Token token(int index) {
        return tokens.get(index);
    }

    boolean isWordAt(int index, String word) {
        if (index < 0 || index >= tokens.size()) {
            return false;
        }
        SqlTokenizer.Token token = tokens.get(index);
        return token.depth() == depth && token.isWord(word);
    }

    boolean isSymbolAt(int index, String symbol) {
        if (index < 0 || index >= tokens.size()) {
            return false;
        }
        SqlTokenizer.Token token = tokens.get(index);
        return token.depth() == depth && token.isSymbol(symbol);
    }

    String wordAt(int index) {
        if (index < 0 || index >= tokens.size()) {
            return null;
        }
        SqlTokenizer.Token token = tokens.get(index);
        return token.depth() == depth && token.type() == SqlTokenizer.Type.WORD
                ? SqlTokenizer.upper(token.text())
                : null;
    }

    String identifierAt(int index) {
        if (index < 0 || index >= tokens.size()) {
            return null;
        }
        return tokens.get(index).identifier();
    }

    int indexOfWord(int start, int end, String word) {
        for (int index = start; index < end; index++) {
            if (isWordAt(index, word)) {
                return index;
            }
        }
        return -1;
    }

    int indexOfAnyWord(int start, int end, Set<String> words) {
        for (int index = start; index < end; index++) {
            SqlTokenizer.Token token = tokens.get(index);
            if (token.depth() == depth
                    && token.type() == SqlTokenizer.Type.WORD
                    && words.contains(SqlTokenizer.upper(token.text()))) {
                return index;
            }
        }
        return -1;
    }

    int indexOfSymbol(int start, int end, String symbol) {
        for (int index = start; index < end; index++) {
            if (isSymbolAt(index, symbol)) {
                return index;
            }
        }
        return -1;
    }

    int matchingParen(int open) {
        if (open < 0 || open >= tokens.size()) {
            return -1;
        }
        int openDepth = tokens.get(open).depth();
        for (int index = open + 1; index < tokens.size(); index++) {
            SqlTokenizer.Token token = tokens.get(index);
            if (token.depth() == openDepth && token.isSymbol(")")) {
                return index;
            }
        }
        return -1;
    }

    List<String> identifiers(int start, int end) {
        List<String> identifiers = new ArrayList<>();
        int index = start;
        while (index < end) {
            String identifier = identifierAt(index);
            if (identifier == null || tokens.get(index).depth() != depth) {
                return List.of();
            }
            identifiers.add(identifier);
            index++;
            if (index < end) {
                if (!isSymbolAt(index, ",")) {
                    return List.of();
                }
                index++;
            }
        }
        return identifiers;
    }
}
