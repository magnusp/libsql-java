package io.github.magnusp.libsql.conformance;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class LibsqlConformanceGuard {

    static final String EXPECTED_DRIVER_NAME = "libSQL JDBC Driver";
    static final String EXPECTED_DATABASE_PRODUCT_NAME = "SQLite (libSQL)";
    static final String LIBSQL_SOURCE_ID_SUFFIX = "alt1";

    private static final String PROBE_URL = "jdbc:sqlite::memory:";

    private LibsqlConformanceGuard() {}

    static void verify() {
        try (Connection connection = DriverManager.getConnection(PROBE_URL)) {
            verify(connection);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Conformance suite could not obtain a JDBC connection from " + PROBE_URL, e);
        }
    }

    static void verify(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String driverName = metadata.getDriverName();
        String productName = metadata.getDatabaseProductName();

        if (!EXPECTED_DRIVER_NAME.equals(driverName)
                || !EXPECTED_DATABASE_PRODUCT_NAME.equals(productName)) {
            throw new AssertionError(
                    "Conformance suite is not connected to libSQL: expected driver \""
                            + EXPECTED_DRIVER_NAME
                            + "\" and database product \""
                            + EXPECTED_DATABASE_PRODUCT_NAME
                            + "\", but resolved driver \""
                            + driverName
                            + "\" and database product \""
                            + productName
                            + "\"");
        }

        String sourceId = readSourceId(connection);
        if (sourceId == null || !sourceId.endsWith(LIBSQL_SOURCE_ID_SUFFIX)) {
            throw new AssertionError(
                    "Conformance suite is not connected to a libSQL server: expected SQLite"
                            + " source id ending \""
                            + LIBSQL_SOURCE_ID_SUFFIX
                            + "\", but resolved \""
                            + sourceId
                            + "\" through driver \""
                            + driverName
                            + "\"");
        }
    }

    private static String readSourceId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT sqlite_source_id()")) {
            if (!resultSet.next()) {
                throw new AssertionError(
                        "Conformance suite could not read a SQLite source id from "
                                + "the libSQL connection");
            }
            return resultSet.getString(1);
        }
    }
}
