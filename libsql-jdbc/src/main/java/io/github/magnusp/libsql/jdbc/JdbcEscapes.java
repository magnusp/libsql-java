package io.github.magnusp.libsql.jdbc;

/**
 * Finds JDBC escape sequences in SQL text.
 *
 * <p>The driver sends SQL to sqld verbatim, so a sequence such as {@code {fn abs(-1)}} would fail as
 * SQLite syntax instead of being translated. Only a real escape needs rejecting: braces inside
 * string literals, quoted identifiers, and comments do not count, and a brace that does not start
 * with an escape code is left to the server.
 */
final class JdbcEscapes {

    private JdbcEscapes() {}

    /**
     * Whether the SQL contains a JDBC escape sequence. A null or brace-free string contains none.
     */
    static boolean contains(String sql) {
        if (sql == null || sql.indexOf('{') < 0) {
            return false;
        }
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == c) {
                        if (i + 1 < n && sql.charAt(i + 1) == c) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
            } else if (c == '[') {
                i++;
                while (i < n && sql.charAt(i) != ']') {
                    i++;
                }
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else if (c == '{') {
                int code = i + 1;
                while (code < n && Character.isWhitespace(sql.charAt(code))) {
                    code++;
                }
                if (code < n && "dDtTfFoOeEcC".indexOf(sql.charAt(code)) >= 0) {
                    return true;
                }
                i++;
            } else {
                i++;
            }
        }
        return false;
    }
}
