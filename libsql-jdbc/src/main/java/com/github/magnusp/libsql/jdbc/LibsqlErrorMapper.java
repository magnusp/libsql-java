package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.client.LibsqlException;
import com.github.magnusp.libsql.hrana.codec.HranaError;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;

public class LibsqlErrorMapper {

    public static SQLException toSQLException(LibsqlException e) {
        return toSQLException(e.getCode(), e.getMessage(), e.getHttpStatus(), e);
    }

    /**
     * Maps a Hrana {@code Error} returned inside a stream, batch, or transaction. The protocol
     * leaves {@code code} unstable, so an unrecognized code falls back to categorizing the message
     * rather than losing the constraint classification.
     */
    public static SQLException toSQLException(HranaError e) {
        return toSQLException(e.code(), e.message(), 200, new LibsqlException(e));
    }

    private static SQLException toSQLException(
            String code, String message, int httpStatus, Throwable cause) {
        message = message != null ? message : "Unknown libSQL error";

        if (isConstraintViolation(code, message)) {
            return new SQLIntegrityConstraintViolationException(message, "23000", 19, cause);
        }
        if (code != null) {
            String upper = code.toUpperCase(Locale.ROOT);
            if (upper.contains("BUSY") || upper.contains("LOCKED")) {
                return new SQLTransientConnectionException(message, "08001", 5, cause);
            }
            if (upper.contains("SYNTAX") || upper.contains("SQLITE_ERROR")) {
                return new SQLSyntaxErrorException(message, "42000", 1, cause);
            }
            if (upper.contains("AUTH") || upper.contains("JWT")) {
                return new SQLInvalidAuthorizationSpecException(message, "28000", cause);
            }
        }

        if (httpStatus == 401 || httpStatus == 403) {
            return new SQLInvalidAuthorizationSpecException(message, "28000", cause);
        }
        if (httpStatus == 429 || httpStatus == 503) {
            return new SQLTransientConnectionException(message, "08001", cause);
        }

        return new SQLException(message, "HY000", cause);
    }

    /**
     * A stable category the protocol does not promise: the server may report {@code
     * SQLITE_CONSTRAINT_UNIQUE}, the older {@code SQLITE_CONSTRAINT}, or no code at all, so the
     * SQLite message text is the fallback signal.
     */
    private static boolean isConstraintViolation(String code, String message) {
        if (code != null && code.toUpperCase(Locale.ROOT).contains("CONSTRAINT")) {
            return true;
        }
        String upper = message.toUpperCase(Locale.ROOT);
        return upper.contains("CONSTRAINT FAILED") || upper.contains("UNIQUE CONSTRAINT");
    }
}
