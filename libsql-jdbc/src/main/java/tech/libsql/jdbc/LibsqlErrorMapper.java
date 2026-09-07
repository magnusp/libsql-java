package tech.libsql.jdbc;

import tech.libsql.client.LibsqlException;

import java.sql.BatchUpdateException;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

public class LibsqlErrorMapper {

    public static SQLException toSQLException(LibsqlException e) {
        String code = e.getCode();
        String message = e.getMessage() != null ? e.getMessage() : "Unknown libSQL error";

        if (code != null) {
            String upper = code.toUpperCase();
            if (upper.contains("CONSTRAINT") || upper.contains("SQLITE_CONSTRAINT")) {
                return new SQLIntegrityConstraintViolationException(message, "23000", 19, e);
            }
            if (upper.contains("BUSY") || upper.contains("SQLITE_BUSY") || upper.contains("LOCKED")) {
                return new SQLTransientConnectionException(message, "08001", 5, e);
            }
            if (upper.contains("SYNTAX") || upper.contains("SQLITE_ERROR")) {
                return new SQLSyntaxErrorException(message, "42000", 1, e);
            }
            if (upper.contains("AUTH") || upper.contains("JWT")) {
                return new SQLInvalidAuthorizationSpecException(message, "28000", e);
            }
        }

        if (e.getHttpStatus() == 401 || e.getHttpStatus() == 403) {
            return new SQLInvalidAuthorizationSpecException(message, "28000", e);
        }
        if (e.getHttpStatus() == 429 || e.getHttpStatus() == 503) {
            return new SQLTransientConnectionException(message, "08001", e);
        }

        return new SQLException(message, "HY000", e);
    }
}
