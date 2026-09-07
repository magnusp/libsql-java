package tech.libsql.itest;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

@Testcontainers
public abstract class SqldContainerBase {

    private static final String LIBSQL_IMAGE = System.getProperty("libsql.image", "ghcr.io/tursodatabase/libsql-server:latest");
    private static final int PORT = 8080;

    @Container
    public static final GenericContainer<?> LIBSQL_CONTAINER = new GenericContainer<>(LIBSQL_IMAGE)
            .withExposedPorts(PORT)
            .withEnv("SQLD_NODE", "primary")
            .withEnv("SQLD_DISABLE_INTELLIGENT_THROTTLING", "true")
            .withEnv("SQLD_CONNECTION_CREATION_TIMEOUT_SEC", "30")
            .waitingFor(Wait.forHttp("/v2/pipeline")
                    .forPort(PORT)
                    .forStatusCode(405)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    @BeforeAll
    public static void startContainer() {
        if (!LIBSQL_CONTAINER.isRunning()) {
            LIBSQL_CONTAINER.start();
        }
    }

    public static String getHttpUrl() {
        return "http://" + LIBSQL_CONTAINER.getHost() + ":" + LIBSQL_CONTAINER.getMappedPort(PORT);
    }
}
