package io.github.magnusp.libsql.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Parameter metadata for a prepared statement over the Hrana transport.
 *
 * <p>The placeholder count is derived locally from the SQL text, but the parameter type, precision,
 * and scale stay unknown because Hrana has no describe request. Every call checks the owning
 * statement first, so metadata obtained before {@link LibsqlStatement#close()} still throws once
 * the statement is closed.
 */
final class ParameterMetaDataView implements ParameterMetaData {

    private final LibsqlStatement statement;
    private final int parameterCount;

    ParameterMetaDataView(LibsqlStatement statement, int parameterCount) {
        this.statement = statement;
        this.parameterCount = parameterCount;
    }

    @Override
    public int getParameterCount() throws SQLException {
        statement.checkClosed();
        return parameterCount;
    }

    @Override
    public int isNullable(int param) throws SQLException {
        checkParameterIndex(param);
        return parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        checkParameterIndex(param);
        return true;
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        checkParameterIndex(param);
        return 0;
    }

    @Override
    public int getScale(int param) throws SQLException {
        checkParameterIndex(param);
        return 0;
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        checkParameterIndex(param);
        // Hrana has no describe statement, so a parameter type is unknown until it is executed.
        return Types.OTHER;
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        checkParameterIndex(param);
        return "OTHER";
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        checkParameterIndex(param);
        return Object.class.getName();
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        checkParameterIndex(param);
        return parameterModeIn;
    }

    private void checkParameterIndex(int param) throws SQLException {
        statement.checkClosed();
        if (param < 1 || param > parameterCount) {
            throw new SQLException("Parameter index out of range: " + param);
        }
    }

    // The statement, not the view, owns the Wrapper surface, so unwrap still reaches the statement.

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return statement.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return statement.isWrapperFor(iface);
    }
}
