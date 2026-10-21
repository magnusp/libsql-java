package com.github.magnusp.libsql.conformance;

import com.github.magnusp.libsql.jdbc.LibsqlDriver;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

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
            XerialTestServer.releaseNamespace(target.namespace());
            throw e;
        }
        if (connection == null) {
            XerialTestServer.releaseNamespace(target.namespace());
            return null;
        }
        try {
            return (Connection)
                    Proxy.newProxyInstance(
                            XerialConformanceDriver.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            new NamespaceClosingHandler(connection, target.namespace()));
        } catch (RuntimeException e) {
            XerialTestServer.releaseNamespace(target.namespace());
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

    private static Target rewrite(String url) {
        String target = url.substring(PREFIX.length());
        String namespace;
        if (target.isEmpty() || ":memory:".equals(target)) {
            namespace = "mem_" + UUID.randomUUID().toString().replace("-", "");
        } else {
            namespace =
                    "file_"
                            + UUID.nameUUIDFromBytes(target.getBytes(StandardCharsets.UTF_8))
                                    .toString()
                                    .replace("-", "");
        }
        return new Target(XerialTestServer.jdbcUrl(namespace), namespace);
    }

    private record Target(String url, String namespace) {}

    private static final class NamespaceClosingHandler implements InvocationHandler {

        private final Connection connection;
        private final String namespace;
        private boolean closed;

        private NamespaceClosingHandler(Connection connection, String namespace) {
            this.connection = connection;
            this.namespace = namespace;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                close();
                return null;
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
            } finally {
                XerialTestServer.releaseNamespace(namespace);
            }
        }
    }
}
