package com.github.magnusp.libsql.conformance;

import com.github.magnusp.libsql.jdbc.LibsqlDriver;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Maps the {@code jdbc:sqlite:} URLs used by the xerial test sources onto sqld namespaces.
 *
 * <p>An empty path and {@code :memory:} map to a fresh namespace per connection. Every other path
 * is resolved against the process working directory, normalized, and hashed to a namespace that is
 * retained until the suite shuts down, so closing every connection and reopening the same path
 * observes the same data. A relative path and the equivalent absolute path therefore address the
 * same namespace. The query string is part of the namespace identity, so two URLs with different
 * options address different namespaces; the options themselves are not applied to the server.
 *
 * <p>SQLite URI filenames such as {@code file::memory:?cache=shared} and {@code ?mode=memory} are
 * in-memory databases, not files. They map to a fresh namespace per connection and are never
 * retained across tests.
 *
 * <p>The returned connection is a proxy. Statements and metadata obtained from it return the same
 * proxy from {@code getConnection()}, so closing through any JDBC accessor releases the namespace.
 */
public final class XerialConformanceDriver implements Driver {

    private static final String PREFIX = "jdbc:sqlite:";
    private static final String HTTP_PREFIX = PREFIX + "http://";
    private static final String HTTPS_PREFIX = PREFIX + "https://";
    private static final String FILE_SCHEME = "file:";

    private final LibsqlDriver delegate = new LibsqlDriver();

    public static void register() {
        try {
            DriverManager.registerDriver(new XerialConformanceDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        Target target = plan(url);
        Connection connection;
        try {
            connection =
                    delegate.connect(
                            XerialTestServer.jdbcUrl(target.namespace(), target.retained()),
                            info);
        } catch (SQLException e) {
            releaseAfterConnectFailure(target, e);
            throw e;
        }
        if (connection == null) {
            release(target);
            return null;
        }
        try {
            Connection proxy =
                    (Connection)
                            Proxy.newProxyInstance(
                                    XerialConformanceDriver.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    new NamespaceClosingHandler(
                                            connection, target.namespace(), releaser(target)));
            XerialTestServer.registerConnection(target.namespace(), connection);
            return proxy;
        } catch (RuntimeException e) {
            try {
                release(target);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        if (url == null || !url.startsWith(PREFIX)) {
            return false;
        }
        return !url.startsWith(HTTP_PREFIX) && !url.startsWith(HTTPS_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    private static void releaseAfterConnectFailure(Target target, SQLException failure) {
        try {
            release(target);
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static void release(Target target) {
        releaser(target).accept(target.namespace());
    }

    private static Consumer<String> releaser(Target target) {
        return target.retained()
                ? XerialTestServer::releaseRetainedConnection
                : XerialTestServer::releaseNamespace;
    }

    static Target plan(String url) {
        String target = url.substring(PREFIX.length());
        boolean fileUri = target.startsWith(FILE_SCHEME);
        String withoutScheme = fileUri ? target.substring(FILE_SCHEME.length()) : target;
        String path = stripQuery(withoutScheme);
        String query = queryOf(withoutScheme);
        boolean memory = path.isEmpty() || ":memory:".equals(path);
        if (!memory && fileUri) {
            memory = hasMemoryMode(query);
        }
        if (memory) {
            String namespace = "mem_" + UUID.randomUUID().toString().replace("-", "");
            return new Target(namespace, false);
        }
        String normalized = Path.of(path).toAbsolutePath().normalize().toString();
        String identity = query.isEmpty() ? normalized : normalized + "?" + query;
        String namespace =
                "file_"
                        + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", "");
        return new Target(namespace, true);
    }

    private static String stripQuery(String target) {
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    private static String queryOf(String target) {
        int query = target.indexOf('?');
        return query < 0 ? "" : target.substring(query + 1);
    }

    private static boolean hasMemoryMode(String query) {
        for (String parameter : query.split("&")) {
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            String value = equals < 0 ? "" : parameter.substring(equals + 1);
            if ("mode".equals(name) && "memory".equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    record Target(String namespace, boolean retained) {}

    static final class NamespaceClosingHandler implements InvocationHandler {

        private final Connection connection;
        private final String namespace;
        private final Consumer<String> release;
        private DatabaseMetaData metadata;
        private boolean closed;

        NamespaceClosingHandler(Connection connection, String namespace) {
            this(connection, namespace, XerialTestServer::releaseNamespace);
        }

        NamespaceClosingHandler(
                Connection connection, String namespace, Consumer<String> release) {
            this.connection = connection;
            this.namespace = namespace;
            this.release = release;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name) && method.getParameterCount() == 0) {
                close();
                return null;
            }
            if ("abort".equals(name) && method.getParameterCount() == 1) {
                abort((Executor) args[0]);
                return null;
            }
            if ("isClosed".equals(name) && method.getParameterCount() == 0) {
                return closed || connection.isClosed();
            }
            if ("equals".equals(name) && method.getParameterCount() == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name) && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            if ("getMetaData".equals(name) && method.getParameterCount() == 0) {
                if (closed) {
                    throw new SQLException("Connection is closed");
                }
                return metadata((Connection) proxy);
            }
            if (isWrapper(method)
                    && args != null
                    && args.length == 1
                    && args[0] instanceof Class<?> type
                    && type.isInstance(proxy)) {
                return name.equals("unwrap") ? proxy : true;
            }
            if (isStatementFactory(name)) {
                Object result = invokeTarget(method, args);
                return result instanceof Statement statement
                        ? wrapStatement(statement, (Connection) proxy)
                        : result;
            }
            return invokeTarget(method, args);
        }

        private DatabaseMetaData metadata(Connection proxy) throws SQLException {
            if (metadata == null) {
                metadata =
                        (DatabaseMetaData)
                                Proxy.newProxyInstance(
                                        XerialConformanceDriver.class.getClassLoader(),
                                        new Class<?>[] {DatabaseMetaData.class},
                                        new MetaDataHandler(connection.getMetaData(), proxy));
            }
            return metadata;
        }

        private Object invokeTarget(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(connection, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private synchronized void close() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                connection.close();
            } catch (SQLException e) {
                releaseWithSuppressed(e);
                throw e;
            } catch (RuntimeException e) {
                releaseWithSuppressed(e);
                throw e;
            }
            releaseNamespace();
        }

        private synchronized void abort(Executor executor) throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                connection.abort(executor);
            } catch (SQLException e) {
                releaseWithSuppressed(e);
                throw e;
            } catch (RuntimeException e) {
                releaseWithSuppressed(e);
                throw e;
            }
            releaseNamespace();
        }

        private void releaseNamespace() {
            XerialTestServer.unregisterConnection(connection);
            release.accept(namespace);
        }

        private void releaseWithSuppressed(Throwable failure) {
            try {
                releaseNamespace();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    static final class StatementHandler implements InvocationHandler {

        private final Statement statement;
        private final Connection connection;

        StatementHandler(Statement statement, Connection connection) {
            this.statement = statement;
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("getConnection".equals(name) && method.getParameterCount() == 0) {
                return connection;
            }
            if ("equals".equals(name) && method.getParameterCount() == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name) && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            if (isWrapper(method)
                    && args != null
                    && args.length == 1
                    && args[0] instanceof Class<?> type
                    && type.isInstance(proxy)) {
                return name.equals("unwrap") ? proxy : true;
            }
            try {
                return method.invoke(statement, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    static final class MetaDataHandler implements InvocationHandler {

        private final DatabaseMetaData metadata;
        private final Connection connection;

        MetaDataHandler(DatabaseMetaData metadata, Connection connection) {
            this.metadata = metadata;
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("getConnection".equals(name) && method.getParameterCount() == 0) {
                return connection;
            }
            if ("equals".equals(name) && method.getParameterCount() == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name) && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            if (isWrapper(method)
                    && args != null
                    && args.length == 1
                    && args[0] instanceof Class<?> type
                    && type.isInstance(proxy)) {
                return name.equals("unwrap") ? proxy : true;
            }
            try {
                return method.invoke(metadata, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static Statement wrapStatement(Statement statement, Connection connection) {
        Class<?> type;
        if (statement instanceof CallableStatement) {
            type = CallableStatement.class;
        } else if (statement instanceof PreparedStatement) {
            type = PreparedStatement.class;
        } else {
            type = Statement.class;
        }
        return (Statement)
                Proxy.newProxyInstance(
                        XerialConformanceDriver.class.getClassLoader(),
                        new Class<?>[] {type},
                        new StatementHandler(statement, connection));
    }

    private static boolean isStatementFactory(String name) {
        return "createStatement".equals(name)
                || "prepareStatement".equals(name)
                || "prepareCall".equals(name);
    }

    private static boolean isWrapper(Method method) {
        return "unwrap".equals(method.getName()) || "isWrapperFor".equals(method.getName());
    }
}
