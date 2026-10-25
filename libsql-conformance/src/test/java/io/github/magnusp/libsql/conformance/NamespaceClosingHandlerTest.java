package io.github.magnusp.libsql.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class NamespaceClosingHandlerTest {

    @Test
    void closeReleasesNamespaceOnlyOnce() throws SQLException {
        List<String> released = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Connection connection = proxy(connection(calls, false, false), released::add);

        connection.close();
        connection.close();

        assertThat(released).containsExactly("ns");
        assertThat(calls).containsExactly("close");
    }

    @Test
    void closingAnUnwrappedConnectionReleasesNamespace() throws SQLException {
        List<String> released = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Connection connection = proxy(connection(calls, false, false), released::add);

        Connection unwrapped = connection.unwrap(Connection.class);
        assertThat(unwrapped).isSameAs(connection);

        unwrapped.close();

        assertThat(released).containsExactly("ns");
        assertThat(calls).containsExactly("close");
    }

    @Test
    void abortReleasesNamespace() throws SQLException {
        List<String> released = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Connection connection = proxy(connection(calls, false, false), released::add);

        connection.abort(Runnable::run);

        assertThat(released).containsExactly("ns");
        assertThat(calls).containsExactly("abort");
    }

    @Test
    void closeFailureStillReleasesNamespace() {
        List<String> released = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Connection connection = proxy(connection(calls, true, false), released::add);

        assertThatThrownBy(connection::close)
                .isInstanceOf(SQLException.class)
                .hasMessage("close failed");

        assertThat(released).containsExactly("ns");
    }

    @Test
    void abortFailureStillReleasesNamespace() {
        List<String> released = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Connection connection = proxy(connection(calls, false, true), released::add);

        assertThatThrownBy(() -> connection.abort(Runnable::run))
                .isInstanceOf(SQLException.class)
                .hasMessage("abort failed");

        assertThat(released).containsExactly("ns");
    }

    private static Connection proxy(Connection connection, Consumer<String> release) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        new XerialConformanceDriver.NamespaceClosingHandler(
                                connection, "ns", release));
    }

    private static Connection connection(
            List<String> calls, boolean failClose, boolean failAbort) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "close" -> {
                                        calls.add("close");
                                        if (failClose) {
                                            throw new SQLException("close failed");
                                        }
                                        yield null;
                                    }
                                    case "abort" -> {
                                        calls.add("abort");
                                        if (failAbort) {
                                            throw new SQLException("abort failed");
                                        }
                                        yield null;
                                    }
                                    case "toString" -> "fake connection";
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == args[0];
                                    default ->
                                            throw new UnsupportedOperationException(
                                                    method.getName());
                                });
    }
}
