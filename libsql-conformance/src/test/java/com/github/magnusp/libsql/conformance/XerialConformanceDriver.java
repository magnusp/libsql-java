package com.github.magnusp.libsql.conformance;

import com.github.magnusp.libsql.jdbc.LibsqlDriver;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
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
 * same namespace. Query strings are ignored when deriving the namespace, so URL pragmas have no
 * effect on the conformance server.
 */
public final class XerialConformanceDriver implements Driver {

    private static final String PREFIX = "jdbc:sqlite:";
    private static final String HTTP_PREFIX = PREFIX + "http://";
    private static final String HTTPS_PREFIX = PREFIX + "https://";

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
        Target target = rewrite(url);
        Connection connection;
        try {
            connection = delegate.connect(target.url(), info);
        } catch (SQLException e) {
            releaseAfterConnectFailure(target, e);
            throw e;
        }
        if (connection == null) {
            release(target);
            return null;
        }
        try {
            return (Connection)
                    Proxy.newProxyInstance(
                            XerialConformanceDriver.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            new NamespaceClosingHandler(
                                    connection, target.namespace(), releaser(target)));
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

    private static Target rewrite(String url) {
        String target = url.substring(PREFIX.length());
        String path = stripQuery(target);
        if (path.isEmpty() || ":memory:".equals(path)) {
            String namespace = "mem_" + UUID.randomUUID().toString().replace("-", "");
            return new Target(XerialTestServer.jdbcUrl(namespace, false), namespace, false);
        }
        String normalized = Path.of(path).toAbsolutePath().normalize().toString();
        String namespace =
                "file_"
                        + UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", "");
        return new Target(XerialTestServer.jdbcUrl(namespace, true), namespace, true);
    }

    private static String stripQuery(String target) {
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    private record Target(String url, String namespace, boolean retained) {}

    static final class NamespaceClosingHandler implements InvocationHandler {

        private final Connection connection;
        private final String namespace;
        private final Consumer<String> release;
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
            if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                close();
                return null;
            }
            if ("abort".equals(method.getName()) && method.getParameterCount() == 1) {
                abort((Executor) args[0]);
                return null;
            }
            if ("unwrap".equals(method.getName()) && method.getParameterCount() == 1) {
                Class<?> iface = (Class<?>) args[0];
                if (iface != null && iface.isInstance(proxy)) {
                    return proxy;
                }
            }
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
}
