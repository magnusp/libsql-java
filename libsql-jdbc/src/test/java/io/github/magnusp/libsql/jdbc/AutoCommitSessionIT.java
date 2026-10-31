package io.github.magnusp.libsql.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the SQLite session behavior an auto-commit JDBC connection must expose.
 *
 * <p>Every auto-commit statement runs on the connection's session stream, so
 * connection-scoped state survives between statements. sqld expires idle streams; the
 * driver recovers with a fresh stream, which resets that state.
 */
@Testcontainers
class AutoCommitSessionIT {

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

    @Test
    void lastInsertRowidIsVisibleToTheNextAutoCommitStatement() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE session_probe (id INTEGER PRIMARY KEY AUTOINCREMENT, v TEXT)");
            statement.executeUpdate("INSERT INTO session_probe (v) VALUES ('a')");

            try (ResultSet resultSet = statement.executeQuery("SELECT last_insert_rowid()")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong(1)).isPositive();
            }
        }
    }

    @Test
    void sessionStateSurvivesAutoCommitStatementsOnOneConnection() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");

            try (ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isZero();
            }
        }
    }

    @Test
    @Timeout(40)
    void anIdleExpiredStreamIsRecoveredAndSessionStateResets() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            try (ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isZero();
            }

            // sqld expires an idle Hrana stream after about ten seconds.
            Thread.sleep(12_000);

            try (ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                assertThat(resultSet.next())
                        .as("the driver retries the statement on a fresh stream")
                        .isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }

            try (ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1))
                        .as("state set before the idle expiry does not survive the fresh stream")
                        .isEqualTo(1);
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:libsql:http://" + LIBSQL.getHost() + ":" + LIBSQL.getMappedPort(PORT));
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
}
