package io.github.magnusp.libsql.jdbc.jooq;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A throwaway sqld container for the jOOQ integration tests.
 *
 * <p>The schema is created over the Hrana HTTP API rather than through the
 * driver. That keeps a broken metadata implementation from hiding tables from
 * jOOQ code generation, which would make the test fail for the wrong reason.
 */
@Testcontainers
abstract class JooqSqldSupport {

    private static final int PORT = 8080;
    private static final String LIBSQL_IMAGE = requireLibsqlImage();
    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE author ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " name TEXT NOT NULL,"
                    + " country TEXT)",
            "CREATE TABLE book ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " author_id INTEGER NOT NULL REFERENCES author (id),"
                    + " title TEXT NOT NULL,"
                    + " price REAL,"
                    + " in_print INTEGER NOT NULL DEFAULT 1)",
            "CREATE INDEX book_author_id_idx ON book (author_id)");

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

    static void createSchema() {
        String baseUrl = "http://" + LIBSQL.getHost() + ":" + LIBSQL.getMappedPort(PORT);
        String requests = SCHEMA.stream()
                .map(sql -> "{\"type\":\"execute\",\"stmt\":{\"sql\":\"" + escape(sql) + "\"}}")
                .collect(Collectors.joining(",", "", ",{\"type\":\"close\"}"));
        String body = "{\"requests\":[" + requests + "]}";

        HttpResponse<String> response;
        try {
            response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/v3/pipeline"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("schema bootstrap request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("schema bootstrap interrupted", e);
        }

        if (response.statusCode() != 200 || response.body().contains("\"type\":\"error\"")) {
            throw new IllegalStateException(
                    "schema bootstrap failed: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private static String escape(String sql) {
        return sql.replace("\\", "\\\\").replace("\"", "\\\"");
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
