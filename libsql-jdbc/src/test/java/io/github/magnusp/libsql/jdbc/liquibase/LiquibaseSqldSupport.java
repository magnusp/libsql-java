package io.github.magnusp.libsql.jdbc.liquibase;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * A throwaway sqld container for the Liquibase integration tests.
 *
 * <p>Every test uses its own table and changeset names, so the tests can share
 * one database without dropping each other's objects. Liquibase's own
 * {@code databasechangelog} bookkeeping is filtered by changeset id where a
 * test asserts on it.
 */
@Testcontainers
abstract class LiquibaseSqldSupport {

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
