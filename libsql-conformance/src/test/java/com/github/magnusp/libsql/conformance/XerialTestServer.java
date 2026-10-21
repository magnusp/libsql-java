package com.github.magnusp.libsql.conformance;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class XerialTestServer {

    private static final String IMAGE =
            System.getProperty(
                    "libsql.image",
                    "ghcr.io/tursodatabase/libsql-server@sha256:6dd3eb276d9d3604e4a48ac4a999a2e267814732d57d7e94c04ba71482333a67");
    private static final int HTTP_PORT = 8080;
    private static final int ADMIN_PORT = 9090;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, Integer> NAMESPACES = new ConcurrentHashMap<>();

    private static GenericContainer<?> container;
    private static String httpBaseUrl;
    private static String adminBaseUrl;
    private static boolean shutdownHookInstalled;

    private XerialTestServer() {}

    public static synchronized void start() {
        if (container != null) {
            return;
        }

        GenericContainer<?> started =
                new GenericContainer<>(IMAGE)
                        .withExposedPorts(HTTP_PORT, ADMIN_PORT)
                        .withEnv("SQLD_NODE", "primary")
                        .withEnv("SQLD_DISABLE_INTELLIGENT_THROTTLING", "true")
                        .withEnv("SQLD_CONNECTION_CREATION_TIMEOUT_SEC", "30")
                        .withCommand(
                                "sqld",
                                "--http-listen-addr",
                                "0.0.0.0:" + HTTP_PORT,
                                "--enable-namespaces",
                                "--max-active-namespaces",
                                "1000",
                                "--admin-listen-addr",
                                "0.0.0.0:" + ADMIN_PORT)
                        .waitingFor(
                                Wait.forHttp("/v2/pipeline")
                                        .forPort(HTTP_PORT)
                                        .forStatusCodeMatching(
                                                status -> status >= 200 && status < 500)
                                        .withStartupTimeout(Duration.ofSeconds(120)));

        started.start();
        container = started;
        httpBaseUrl =
                "http://" + started.getHost() + ":" + started.getMappedPort(HTTP_PORT);
        adminBaseUrl =
                "http://" + started.getHost() + ":" + started.getMappedPort(ADMIN_PORT);

        if (!shutdownHookInstalled) {
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(XerialTestServer::stop, "xerial-sqld-shutdown"));
            shutdownHookInstalled = true;
        }
    }

    public static synchronized void stop() {
        if (container == null) {
            return;
        }
        try {
            container.stop();
        } finally {
            container = null;
            httpBaseUrl = null;
            adminBaseUrl = null;
            NAMESPACES.clear();
        }
    }

    public static String jdbcUrl(String namespace) {
        start();
        ensureNamespace(namespace);
        return "jdbc:sqlite:" + httpBaseUrl + "/dev/" + namespace;
    }

    public static void releaseNamespace(String namespace) {
        String baseUrl;
        synchronized (XerialTestServer.class) {
            int active = NAMESPACES.getOrDefault(namespace, 0);
            if (active == 0) {
                return;
            }
            if (active > 1) {
                NAMESPACES.put(namespace, active - 1);
                return;
            }
            NAMESPACES.remove(namespace);
            if (container == null) {
                return;
            }
            baseUrl = adminBaseUrl;
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/namespaces/" + namespace))
                        .timeout(REQUEST_TIMEOUT)
                        .DELETE()
                        .build();

        try {
            HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static synchronized void ensureNamespace(String namespace) {
        int active = NAMESPACES.getOrDefault(namespace, 0);
        if (active > 0) {
            NAMESPACES.put(namespace, active + 1);
            return;
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/v1/namespaces/" + namespace + "/create"))
                        .header("Content-Type", "application/json")
                        .timeout(REQUEST_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();

        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create namespace " + namespace, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while creating namespace " + namespace, e);
        }

        if (response.statusCode() != 200 && response.statusCode() != 409) {
            throw new IllegalStateException(
                    "Failed to create namespace "
                            + namespace
                            + ": HTTP "
                            + response.statusCode()
                            + " "
                            + response.body());
        }

        NAMESPACES.put(namespace, 1);
    }
}
