package com.github.magnusp.libsql.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * Exercises the conformance harness itself, without a running sqld container where possible.
 *
 * <p>The fake connection stands in for {@code LibsqlConnection}. It exposes a statement and
 * metadata whose {@code getConnection()} returns the raw connection, which is exactly the shape
 * that leaked the namespace before the driver wrapped those accessors.
 */
class XerialHarnessTest {

    private static final String ADMIN_URL = "http://admin.test";

    @TempDir Path tempDir;

    @Test
    void statementAndMetadataReturnTheWrappedConnection() throws SQLException {
        FakeConnection fake = new FakeConnection();
        Connection wrapped = wrap(fake.connection, "ns");

        assertThat(wrapped.createStatement().getConnection()).isSameAs(wrapped);
        assertThat(wrapped.getMetaData().getConnection()).isSameAs(wrapped);
        assertThat(wrapped.unwrap(Connection.class)).isSameAs(wrapped);
    }

    @Test
    void closingThroughAStatementConnectionReleasesTheNamespaceOnce() throws SQLException {
        FakeConnection fake = new FakeConnection();
        AtomicInteger releases = new AtomicInteger();
        Connection wrapped = wrap(fake.connection, "ns", namespace -> releases.incrementAndGet());

        wrapped.createStatement().getConnection().close();
        wrapped.close();

        assertThat(releases).hasValue(1);
    }

    @Test
    void fileMemoryUriIsNotRetained() {
        XerialConformanceDriver.Target memory =
                XerialConformanceDriver.plan("jdbc:sqlite:file::memory:?cache=shared");
        XerialConformanceDriver.Target memoryMode =
                XerialConformanceDriver.plan("jdbc:sqlite:file:/tmp/ignored.db?mode=memory");

        assertThat(memory.retained()).isFalse();
        assertThat(memoryMode.retained()).isFalse();
    }

    @Test
    void quarantineExcludesOnlyTheAppendixBConflicts() {
        XerialQuarantine quarantine = new XerialQuarantine();

        for (XerialQuarantine.Quarantined quarantined : XerialQuarantine.QUARANTINED) {
            assertThat(
                            quarantine
                                    .apply(
                                            descriptor(
                                                    quarantined.className(),
                                                    quarantined.methodName()))
                                    .excluded())
                    .as("%s#%s", quarantined.className(), quarantined.methodName())
                    .isTrue();
        }

        assertThat(quarantine.apply(descriptor("org.sqlite.ResultSetTest", "next")).included())
                .as("an unconflicted xerial test")
                .isTrue();
    }

    @Test
    void quarantineIsInactiveDuringTheXerialControlRun() {
        String previous = System.getProperty(XerialControl.ENABLED_PROPERTY);
        System.setProperty(XerialControl.ENABLED_PROPERTY, "xerial");
        try {
            XerialQuarantine quarantine = new XerialQuarantine();
            for (XerialQuarantine.Quarantined quarantined : XerialQuarantine.QUARANTINED) {
                assertThat(
                                quarantine
                                        .apply(
                                                descriptor(
                                                        quarantined.className(),
                                                        quarantined.methodName()))
                                        .included())
                        .as("control run keeps %s#%s", quarantined.className(),
                                quarantined.methodName())
                        .isTrue();
            }
        } finally {
            if (previous == null) {
                System.clearProperty(XerialControl.ENABLED_PROPERTY);
            } else {
                System.setProperty(XerialControl.ENABLED_PROPERTY, previous);
            }
        }
    }

    @Test
    void queryStringIsPartOfTheFileNamespace() {
        XerialConformanceDriver.Target readOnly =
                XerialConformanceDriver.plan("jdbc:sqlite:/tmp/example.db?mode=ro");
        XerialConformanceDriver.Target readWrite =
                XerialConformanceDriver.plan("jdbc:sqlite:/tmp/example.db?mode=rw");
        XerialConformanceDriver.Target repeated =
                XerialConformanceDriver.plan("jdbc:sqlite:/tmp/example.db?mode=ro");

        assertThat(readOnly.retained()).isTrue();
        assertThat(readOnly.namespace()).isNotEqualTo(readWrite.namespace());
        assertThat(repeated.namespace()).isEqualTo(readOnly.namespace());
    }

    @Test
    void sharedMemoryFileUriDoesNotKeepDataAcrossConnections() throws SQLException {
        String url = "jdbc:sqlite:file::memory:?cache=shared";
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("create table isolated_shared (id integer)");
            statement.execute("insert into isolated_shared values (1)");
        }

        assertThatThrownBy(
                        () -> {
                            try (Connection connection = DriverManager.getConnection(url);
                                    Statement statement = connection.createStatement();
                                    ResultSet resultSet =
                                            statement.executeQuery(
                                                    "select count(*) from isolated_shared")) {
                                assertThat(resultSet.next()).isTrue();
                            }
                        })
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("no such table");
    }

    @Test
    void fileNamespacesWithDifferentQueryStringsAreIsolated() throws SQLException {
        Path database = tempDir.resolve("query.db").toAbsolutePath().normalize();
        String writeUrl = "jdbc:sqlite:" + database + "?mode=rw";
        String readUrl = "jdbc:sqlite:" + database + "?mode=ro";

        try (Connection connection = DriverManager.getConnection(writeUrl);
                Statement statement = connection.createStatement()) {
            statement.execute("create table isolated_query (id integer)");
            statement.execute("insert into isolated_query values (1)");
        }

        assertThatThrownBy(
                        () -> {
                            try (Connection connection = DriverManager.getConnection(readUrl);
                                    Statement statement = connection.createStatement();
                                    ResultSet resultSet =
                                            statement.executeQuery(
                                                    "select count(*) from isolated_query")) {
                                assertThat(resultSet.next()).isTrue();
                            }
                        })
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("no such table");
    }

    @Test
    void stopFailsWhenANamespaceStillHasAnOpenConnection() {
        FakeAdmin admin = new FakeAdmin();
        String namespace = namespace();
        XerialTestServer.ensureNamespace(namespace, ADMIN_URL, admin);
        try {
            assertThatThrownBy(XerialTestServer::stopForTest)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(namespace)
                    .hasMessageContaining("open");
        } finally {
            XerialTestServer.resetNamespaceForTest(namespace);
        }
    }

    @Test
    void stopReportsASwallowedCleanupFailure() {
        FakeAdmin admin = new FakeAdmin();
        String namespace = namespace();
        XerialTestServer.ensureNamespace(namespace, ADMIN_URL, admin);
        admin.deleteStatus.set(500);
        try {
            assertThatThrownBy(
                            () ->
                                    XerialTestServer.releaseNamespace(
                                            namespace, ADMIN_URL, admin))
                    .isInstanceOf(IllegalStateException.class);

            assertThatThrownBy(XerialTestServer::stopForTest)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cleanup")
                    .hasMessageContaining(namespace);
        } finally {
            XerialTestServer.resetNamespaceForTest(namespace);
        }
    }

    private static TestDescriptor descriptor(String className, String methodName) {
        MethodSource source = MethodSource.from(className, methodName);
        return (TestDescriptor)
                Proxy.newProxyInstance(
                        TestDescriptor.class.getClassLoader(),
                        new Class<?>[] {TestDescriptor.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getSource" -> java.util.Optional.of(source);
                                    default -> null;
                                });
    }

    private static Connection wrap(Connection connection, String namespace) {
        return wrap(connection, namespace, ignored -> {});
    }

    private static Connection wrap(
            Connection connection,
            String namespace,
            java.util.function.Consumer<String> release) {
        return (Connection)
                Proxy.newProxyInstance(
                        XerialConformanceDriver.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        new XerialConformanceDriver.NamespaceClosingHandler(
                                connection, namespace, release));
    }

    private static String namespace() {
        return "harness_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static final class FakeConnection {

        private final Statement statement = statement();
        private final DatabaseMetaData metadata = metadata();
        private final Connection connection = connection();

        private Connection connection() {
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "createStatement" -> statement;
                                        case "getMetaData" -> metadata;
                                        case "isClosed" -> false;
                                        case "close" -> null;
                                        case "equals" -> proxy == args[0];
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "toString" -> "fake connection";
                                        default -> defaultValue(method.getReturnType());
                                    });
        }

        private Statement statement() {
            return (Statement)
                    Proxy.newProxyInstance(
                            Statement.class.getClassLoader(),
                            new Class<?>[] {Statement.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "getConnection" -> connection;
                                        case "isClosed" -> false;
                                        case "close" -> null;
                                        case "equals" -> proxy == args[0];
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "toString" -> "fake statement";
                                        default -> defaultValue(method.getReturnType());
                                    });
        }

        private DatabaseMetaData metadata() {
            return (DatabaseMetaData)
                    Proxy.newProxyInstance(
                            DatabaseMetaData.class.getClassLoader(),
                            new Class<?>[] {DatabaseMetaData.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "getConnection" -> connection;
                                        case "equals" -> proxy == args[0];
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "toString" -> "fake metadata";
                                        default -> defaultValue(method.getReturnType());
                                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0f;
            }
            return 0d;
        }
    }

    private static final class FakeAdmin implements XerialTestServer.AdminClient {

        private final AtomicInteger deleteStatus = new AtomicInteger(200);

        @Override
        public XerialTestServer.AdminResponse send(HttpRequest request) {
            if ("DELETE".equals(request.method())) {
                return new XerialTestServer.AdminResponse(deleteStatus.get(), "status");
            }
            return new XerialTestServer.AdminResponse(200, "{}");
        }
    }
}
