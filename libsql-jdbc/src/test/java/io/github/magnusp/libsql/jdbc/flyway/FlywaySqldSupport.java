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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl());
    }

    static void resetDatabase() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String view : names(statement, "view")) {
                statement.execute("DROP VIEW IF EXISTS \"" + view + "\"");
            }
            // A PRAGMA does not survive into the next auto-commit statement, so the
            // drop order has to respect the foreign-key graph instead of disabling
            // checks.
            for (String table : dropOrder(statement, names(statement, "table"))) {
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

    /** Orders children before the parents they reference. */
    private static List<String> dropOrder(Statement statement, List<String> tables) throws SQLException {
        var parents = new LinkedHashMap<String, List<String>>();
        var childCount = new LinkedHashMap<String, Integer>();
        for (String table : tables) {
            List<String> references = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list(\"" + table + "\")")) {
                while (resultSet.next()) {
                    String parent = resultSet.getString("table");
                    if (tables.contains(parent) && !references.contains(parent)) {
                        references.add(parent);
                    }
                }
            }
            parents.put(table, references);
            childCount.put(table, 0);
        }
        for (List<String> references : parents.values()) {
            for (String parent : references) {
                childCount.merge(parent, 1, Integer::sum);
            }
        }

        List<String> order = new ArrayList<>();
        var remaining = new LinkedHashSet<>(tables);
        while (!remaining.isEmpty()) {
            String leaf = remaining.stream()
                    .filter(table -> childCount.get(table) == 0)
                    .findFirst()
                    .orElseGet(() -> remaining.iterator().next());
            order.add(leaf);
            remaining.remove(leaf);
            for (String parent : parents.get(leaf)) {
                childCount.merge(parent, -1, Integer::sum);
            }
        }
        return order;
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

        DriverManagerDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
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
