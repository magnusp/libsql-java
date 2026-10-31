package io.github.magnusp.libsql.jdbc.flyway;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * A throwaway sqld container for the Flyway integration tests.
 *
 * <p>Flyway owns the schema here, so unlike the jOOQ support class there is no
 * out-of-band bootstrap. The reset helpers exist only to isolate tests: every
 * test starts from an empty database.
 */
@Testcontainers
abstract class FlywaySqldSupport {

    private static final int PORT = 8080;
    private static final String LIBSQL_IMAGE = requireLibsqlImage();

    @Container
    static final GenericContainer<?> LIBSQL = new GenericContainer<>(DockerImageName.parse(LIBSQL_IMAGE))
            .withExposedPorts(PORT)
            .withEnv("SQLD_NODE", "primary")
            .withEnv("SQLD_DISABLE_INTELLIGENT_THROTTLING", "true")
            .withEnv("SQLD_CONNECTION_CREATION_TIMEOUT_SEC", "30")
            .waitingFor(Wait.forHttp("/v2/pipeline")
                    .forPort(PORT)
                    .forStatusCode(405)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    static String jdbcUrl() {
        return "jdbc:libsql:http://" + LIBSQL.getHost() + ":" + LIBSQL.getMappedPort(PORT);
    }

    static String httpUrl() {
        return "http://" + LIBSQL.getHost() + ":" + LIBSQL.getMappedPort(PORT);
    }

    static DataSource dataSource() {
        return new DriverManagerDataSource(jdbcUrl());
    }

    /** A DataSource that disables foreign keys before handing the connection to Flyway. */
    static DataSource dataSourceWithForeignKeysDisabled() {
        return new DriverManagerDataSource(jdbcUrl(), true);
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl());
    }

    static void resetDatabase() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // Auto-commit statements share the connection's stream, so the PRAGMA applies to
            // every DROP that follows instead of forcing a foreign-key-aware drop order.
            statement.execute("PRAGMA foreign_keys = OFF");
            for (String view : names(statement, "view")) {
                statement.execute("DROP VIEW IF EXISTS \"" + view + "\"");
            }
            for (String table : names(statement, "table")) {
                statement.execute("DROP TABLE IF EXISTS \"" + table + "\"");
            }
        }
    }

    static List<String> userTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table'"
                                + " AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    static boolean tableExists(String name) throws SQLException {
        try (Connection connection = connect();
                var statement = connection.prepareStatement(
                        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    static int scalarInt(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static List<String> names(Statement statement, String type) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = '" + type + "'"
                        + " AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }

    private static String requireLibsqlImage() {
        String image = System.getProperty("libsql.image");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(
                    "libsql.image is not set. Run the integration tests with Maven so the"
                            + " pinned sqld image from the root pom is used.");
        }
        return image;
    }

    /**
     * The smallest DataSource over DriverManager. Flyway's URL-based configuration
     * picks the driver from the URL before it connects, which fails for libSQL; a
     * DataSource is how an application with a pool would use the driver.
     */
    static final class DriverManagerDataSource implements DataSource {

        private final String url;
        private final boolean disableForeignKeys;

        DriverManagerDataSource(String url) {
            this(url, false);
        }

        DriverManagerDataSource(String url, boolean disableForeignKeys) {
            this.url = url;
            this.disableForeignKeys = disableForeignKeys;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = DriverManager.getConnection(url);
            if (!disableForeignKeys) {
                return connection;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = OFF");
                return connection;
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("no parent logger");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("cannot unwrap to " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
