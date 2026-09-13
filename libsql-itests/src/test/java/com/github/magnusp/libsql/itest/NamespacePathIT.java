package com.github.magnusp.libsql.itest;

import com.github.magnusp.libsql.client.LibsqlClientConfig;
import com.github.magnusp.libsql.client.LibsqlHttpClient;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class NamespacePathIT {

    private static final String LIBSQL_IMAGE = System.getProperty("libsql.image", "ghcr.io/tursodatabase/libsql-server:latest");
    private static final int HTTP_PORT = 8080;
    private static final int ADMIN_PORT = 9090;

    @Container
    public static final GenericContainer<?> SQLD_CONTAINER = new GenericContainer<>(LIBSQL_IMAGE)
            .withExposedPorts(HTTP_PORT, ADMIN_PORT)
            .withCommand(
                    "sqld",
                    "--http-listen-addr", "0.0.0.0:" + HTTP_PORT,
                    "--enable-namespaces",
                    "--admin-listen-addr", "0.0.0.0:" + ADMIN_PORT
            )
            .waitingFor(Wait.forHttp("/v2/pipeline")
                    .forPort(HTTP_PORT)
                    .forStatusCode(405)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static String httpBaseUrl;
    private static String adminBaseUrl;

    @BeforeAll
    static void setUpNamespaces() throws IOException, InterruptedException {
        if (!SQLD_CONTAINER.isRunning()) {
            SQLD_CONTAINER.start();
        }

        httpBaseUrl = "http://" + SQLD_CONTAINER.getHost() + ":" + SQLD_CONTAINER.getMappedPort(HTTP_PORT);
        adminBaseUrl = "http://" + SQLD_CONTAINER.getHost() + ":" + SQLD_CONTAINER.getMappedPort(ADMIN_PORT);

        createNamespace("ns_alpha");
        createNamespace("ns_beta");
    }

    private static void createNamespace(String namespace) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(adminBaseUrl + "/v1/namespaces/" + namespace + "/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(200, 409);
    }

    @Test
    void testNamespaceIsolationViaJdbcPath() throws SQLException {
        String alphaJdbcUrl = "jdbc:libsql:" + httpBaseUrl + "/dev/ns_alpha";
        String betaJdbcUrl = "jdbc:libsql:" + httpBaseUrl + "/dev/ns_beta";

        // In ns_alpha: create table and insert alpha-specific data
        try (Connection connAlpha = DriverManager.getConnection(alphaJdbcUrl);
             Statement stmtAlpha = connAlpha.createStatement()) {
            stmtAlpha.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, tenant TEXT)");
            stmtAlpha.executeUpdate("INSERT INTO users VALUES (1, 'Alice', 'alpha')");
        }

        // In ns_beta: create table with same name and insert beta-specific data
        try (Connection connBeta = DriverManager.getConnection(betaJdbcUrl);
             Statement stmtBeta = connBeta.createStatement()) {
            stmtBeta.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, tenant TEXT)");
            stmtBeta.executeUpdate("INSERT INTO users VALUES (1, 'Bob', 'beta')");
        }

        // Verify data isolation in ns_alpha
        try (Connection connAlpha = DriverManager.getConnection(alphaJdbcUrl);
             PreparedStatement ps = connAlpha.prepareStatement("SELECT name, tenant FROM users WHERE id = ?")) {
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("Alice");
                assertThat(rs.getString("tenant")).isEqualTo("alpha");
                assertThat(rs.next()).isFalse();
            }
        }

        // Verify data isolation in ns_beta
        try (Connection connBeta = DriverManager.getConnection(betaJdbcUrl);
             PreparedStatement ps = connBeta.prepareStatement("SELECT name, tenant FROM users WHERE id = ?")) {
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("Bob");
                assertThat(rs.getString("tenant")).isEqualTo("beta");
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void testNamespaceIsolationViaHttpClientPath() {
        String alphaUrl = httpBaseUrl + "/dev/ns_alpha";
        String betaUrl = httpBaseUrl + "/dev/ns_beta";

        LibsqlHttpClient clientAlpha = new LibsqlHttpClient(new LibsqlClientConfig(alphaUrl, null));
        LibsqlHttpClient clientBeta = new LibsqlHttpClient(new LibsqlClientConfig(betaUrl, null));

        clientAlpha.executeOneShot(new Stmt("CREATE TABLE IF NOT EXISTS client_tab (key TEXT, val TEXT)"));
        clientAlpha.executeOneShot(new Stmt("INSERT INTO client_tab VALUES (?, ?)", List.of(Value.of("k1"), Value.of("v_alpha"))));

        clientBeta.executeOneShot(new Stmt("CREATE TABLE IF NOT EXISTS client_tab (key TEXT, val TEXT)"));
        clientBeta.executeOneShot(new Stmt("INSERT INTO client_tab VALUES (?, ?)", List.of(Value.of("k1"), Value.of("v_beta"))));

        StmtResult alphaRes = clientAlpha.executeOneShot(new Stmt("SELECT val FROM client_tab WHERE key = 'k1'"));
        StmtResult betaRes = clientBeta.executeOneShot(new Stmt("SELECT val FROM client_tab WHERE key = 'k1'"));

        assertThat(alphaRes.rows()).hasSize(1);
        assertThat(alphaRes.rows().get(0).values().get(0)).isEqualTo(new Value.Text("v_alpha"));

        assertThat(betaRes.rows()).hasSize(1);
        assertThat(betaRes.rows().get(0).values().get(0)).isEqualTo(new Value.Text("v_beta"));
    }
}
