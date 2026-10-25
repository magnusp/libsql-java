package io.github.magnusp.libsql.jdbc;

import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * A savepoint created by {@link LibsqlConnection}. Unnamed savepoints carry a generated id and use
 * {@code SQLITE_SAVEPOINT_<id>} as their SQL name, matching xerial sqlite-jdbc; named savepoints
 * carry the caller's name. Following {@code java.sql.Savepoint}, reading the id of a named
 * savepoint and reading the name of an unnamed one both throw.
 */
final class LibsqlSavepoint implements Savepoint {

    private final int id;
    private final String name;
    private final String sqlName;

    LibsqlSavepoint(int id, String name) {
        this.id = id;
        this.name = name;
        this.sqlName = name == null ? "SQLITE_SAVEPOINT_" + id : name;
    }

    String sqlName() {
        return sqlName;
    }

    /**
     * The SQL name of any savepoint handed to this driver: its own name, else the name or generated
     * id a foreign {@link Savepoint} reports. The messages are part of the JDBC contract; the
     * connection keeps only the transitions.
     */
    static String sqlNameOf(Savepoint savepoint) throws SQLException {
        if (savepoint == null) {
            throw new SQLException("Savepoint cannot be null");
        }
        if (savepoint instanceof LibsqlSavepoint libsqlSavepoint) {
            return libsqlSavepoint.sqlName();
        }
        try {
            String name = savepoint.getSavepointName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (SQLException ignored) {
            // An unnamed foreign savepoint has no name; its id identifies it below.
        }
        try {
            return "SQLITE_SAVEPOINT_" + savepoint.getSavepointId();
        } catch (SQLException e) {
            throw new SQLException("Savepoint has no usable name or id", e);
        }
    }

    @Override
    public int getSavepointId() throws SQLException {
        if (name != null) {
            throw new SQLException("Named savepoint has no id: " + name);
        }
        return id;
    }

    @Override
    public String getSavepointName() throws SQLException {
        if (name == null) {
            throw new SQLException("Unnamed savepoint has no name: " + id);
        }
        return name;
    }
}
