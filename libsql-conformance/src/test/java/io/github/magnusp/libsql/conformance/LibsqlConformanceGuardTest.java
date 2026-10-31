package io.github.magnusp.libsql.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class LibsqlConformanceGuardTest {

    @Test
    void acceptsALibsqlConnection() throws Exception {
        Connection connection =
                connection(
                        "libSQL JDBC Driver",
                        "SQLite",
                        "2024-10-21 16:30:22 03a9703e27c44437c39363d0baf82db4ebc94538a0f284"
                                + "11c85dda156f82alt1");

        LibsqlConformanceGuard.verify(connection);
    }

    @Test
    void rejectsAForeignDriverAndNamesTheResolvedAndExpectedDriver() {
        Connection connection = connection("SQLite JDBC", "SQLite", null);

        AssertionError failure =
                assertThrows(
                        AssertionError.class, () -> LibsqlConformanceGuard.verify(connection));

        assertThat(failure)
                .hasMessageContaining("\"SQLite JDBC\"")
                .hasMessageContaining("\"libSQL JDBC Driver\"");
    }

    @Test
    void rejectsANonLibsqlServerAndNamesTheResolvedSourceId() {
        Connection connection =
                connection(
                        "libSQL JDBC Driver",
                        "SQLite",
                        "2024-10-21 16:30:22 03a9703e27c44437c39363d0baf82db4ebc94538a0f284"
                                + "11c85dda156f82");

        AssertionError failure =
                assertThrows(
                        AssertionError.class, () -> LibsqlConformanceGuard.verify(connection));

        assertThat(failure)
                .hasMessageContaining("2024-10-21 16:30:22")
                .hasMessageContaining("alt1");
    }

    private static Connection connection(
            String driverName, String productName, String sourceId) {
        ResultSet resultSet =
                proxy(
                        ResultSet.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "next" -> true;
                                    case "getString" -> sourceId;
                                    case "close" -> null;
                                    default -> unsupported(method.getName());
                                });
        Statement statement =
                proxy(
                        Statement.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "executeQuery" -> resultSet;
                                    case "close" -> null;
                                    default -> unsupported(method.getName());
                                });
        DatabaseMetaData metadata =
                proxy(
                        DatabaseMetaData.class,
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getDriverName" -> driverName;
                                    case "getDatabaseProductName" -> productName;
                                    default -> unsupported(method.getName());
                                });
        return proxy(
                Connection.class,
                (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getMetaData" -> metadata;
                            case "createStatement" -> statement;
                            case "close" -> null;
                            default -> unsupported(method.getName());
                        });
    }

    private static Object unsupported(String methodName) {
        throw new UnsupportedOperationException(methodName);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
