package com.github.magnusp.libsql.jdbc;

import java.util.ArrayList;
import java.util.List;

/** SQLite AUTOINCREMENT grammar for a column definition. */
final class SqlAutoIncrement {

    private SqlAutoIncrement() {}

    static boolean isAutoIncrementColumn(String createSql, String columnName) {
        if (createSql == null || columnName == null) {
            return false;
        }
        List<List<SqlTokenizer.Token>> definitions = tableDefinitions(createSql);
        if (definitions == null) {
            return false;
        }
        for (List<SqlTokenizer.Token> definition : definitions) {
            if (columnDefinitionIsAutoIncrement(definition, columnName)
                    || tableConstraintIsAutoIncrement(definition, columnName)) {
                return true;
            }
        }
        return false;
    }

    private static List<List<SqlTokenizer.Token>> tableDefinitions(String createSql) {
        List<SqlTokenizer.Token> tokens = SqlTokenizer.tokenize(createSql);
        if (tokens == null) {
            return null;
        }
        int open = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).isSymbol("(")) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            return null;
        }

        List<List<SqlTokenizer.Token>> definitions = new ArrayList<>();
        List<SqlTokenizer.Token> current = new ArrayList<>();
        for (int i = open + 1; i < tokens.size(); i++) {
            SqlTokenizer.Token token = tokens.get(i);
            if (token.isSymbol(")") && token.depth() == 0) {
                if (!current.isEmpty()) {
                    definitions.add(current);
                }
                return definitions;
            }
            if (token.isSymbol(",") && token.depth() == 1) {
                definitions.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(token);
        }
        return null;
    }

    private static boolean columnDefinitionIsAutoIncrement(
            List<SqlTokenizer.Token> definition, String columnName) {
        int start = skipConstraintName(definition);
        if (start >= definition.size()) {
            return false;
        }
        if (isConstraintKeyword(definition.get(start))) {
            return false;
        }
        String name = definition.get(start).identifier();
        if (name == null || !name.equalsIgnoreCase(columnName)) {
            return false;
        }
        return containsKeyword(definition, start + 1, definition.size(), "AUTOINCREMENT", 1);
    }

    private static boolean tableConstraintIsAutoIncrement(
            List<SqlTokenizer.Token> definition, String columnName) {
        int start = skipConstraintName(definition);
        if (start + 1 >= definition.size()
                || !definition.get(start).isWord("PRIMARY")
                || !definition.get(start + 1).isWord("KEY")) {
            return false;
        }
        int open = -1;
        for (int i = start + 2; i < definition.size(); i++) {
            if (definition.get(i).isSymbol("(")) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            return false;
        }

        int partStart = open + 1;
        for (int i = open + 1; i < definition.size(); i++) {
            SqlTokenizer.Token token = definition.get(i);
            if ((token.isSymbol(",") && token.depth() == 2)
                    || (token.isSymbol(")") && token.depth() == 1)) {
                if (indexedColumnIsAutoIncrement(definition, partStart, i, columnName)) {
                    return true;
                }
                partStart = i + 1;
            }
        }
        return false;
    }

    private static boolean indexedColumnIsAutoIncrement(
            List<SqlTokenizer.Token> definition, int start, int end, String columnName) {
        if (start >= end) {
            return false;
        }
        String name = definition.get(start).identifier();
        return name != null
                && name.equalsIgnoreCase(columnName)
                && containsKeyword(definition, start + 1, end, "AUTOINCREMENT", 2);
    }

    private static int skipConstraintName(List<SqlTokenizer.Token> definition) {
        if (definition.size() >= 2 && definition.get(0).isWord("CONSTRAINT")) {
            return 2;
        }
        return 0;
    }

    private static boolean isConstraintKeyword(SqlTokenizer.Token token) {
        return token.isWord("PRIMARY")
                || token.isWord("UNIQUE")
                || token.isWord("CHECK")
                || token.isWord("FOREIGN");
    }

    private static boolean containsKeyword(
            List<SqlTokenizer.Token> tokens, int start, int end, String keyword, int depth) {
        for (int i = start; i < end; i++) {
            SqlTokenizer.Token token = tokens.get(i);
            if (token.depth() == depth && token.isWord(keyword)) {
                return true;
            }
        }
        return false;
    }
}
