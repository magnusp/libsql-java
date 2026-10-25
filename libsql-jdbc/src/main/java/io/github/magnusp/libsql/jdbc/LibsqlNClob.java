package io.github.magnusp.libsql.jdbc;

import java.sql.NClob;

/**
 * Read-only {@link NClob} over the materialized text of one result column.
 *
 * <p>SQLite stores national and regular character data as the same UTF-8 text, so the national
 * variant only adds the {@link NClob} interface to {@link LibsqlClob}.
 */
final class LibsqlNClob extends LibsqlClob implements NClob {

    LibsqlNClob(String text) {
        super(text);
    }
}
