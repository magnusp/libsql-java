package com.github.magnusp.libsql.conformance;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class XerialTestServer {

    private static final String IMAGE =
            System.getProperty(
                    "libsql.image",
                    "ghcr.io/tursodatabase/libsql-server@sha256:6dd3eb276d9d3604e4a48ac4a999a2e267814732d57d7e94c04ba71482333a67");
    private static final int HTTP_PORT = 8080;
    private static final int ADMIN_PORT = 9090;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int DELETE_ATTEMPTS = 3;
    private static final Duration DELETE_RETRY_DELAY = Duration.ofMillis(100);

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AdminClient DEFAULT_ADMIN =
            request -> {
                HttpResponse<String> response =
                        HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                return new AdminResponse(response.statusCode(), response.body());
            };
    private static final Map<String, Integer> NAMESPACES = new ConcurrentHashMap<>();
    private static final Set<String> RETAINED_NAMESPACES = ConcurrentHashMap.newKeySet();
    private static final Map<String, RuntimeException> FAILED_NAMESPACES =
            new ConcurrentHashMap<>();
    private static final Map<String, Set<Connection>> CONNECTIONS = new ConcurrentHashMap<>();

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
                                        .forStatusCodeMatching(XerialTestServer::isReadyStatus)
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
        RuntimeException failure = openConnectionFailure();
        failure = merge(failure, failedNamespacesFailure());
        failure = merge(failure, reapRetainedNamespaces());
        try {
            container.stop();
        } catch (RuntimeException stopFailure) {
            failure = merge(failure, stopFailure);
        } finally {
            resetState();
        }
        if (failure != null) {
            throw failure;
        }
    }

    static synchronized void stopForTest() {
        RuntimeException failure = openConnectionFailure();
        failure = merge(failure, failedNamespacesFailure());
        if (failure != null) {
            throw failure;
        }
    }

    static synchronized void resetNamespaceForTest(String namespace) {
        NAMESPACES.remove(namespace);
        RETAINED_NAMESPACES.remove(namespace);
        FAILED_NAMESPACES.remove(namespace);
        CONNECTIONS.remove(namespace);
    }

    private static RuntimeException merge(RuntimeException failure, RuntimeException next) {
        if (next == null) {
            return failure;
        }
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private static void resetState() {
        container = null;
        httpBaseUrl = null;
        adminBaseUrl = null;
        resetNamespaces();
    }

    private static void resetNamespaces() {
        NAMESPACES.clear();
        RETAINED_NAMESPACES.clear();
        FAILED_NAMESPACES.clear();
        CONNECTIONS.clear();
    }

    private static RuntimeException openConnectionFailure() {
        List<String> open = new ArrayList<>();
        RuntimeException cleanupFailure = null;
        for (String namespace : new ArrayList<>(CONNECTIONS.keySet())) {
            Set<Connection> connections = CONNECTIONS.get(namespace);
            if (connections == null) {
                continue;
            }
            boolean stillOpen = false;
            for (Connection connection : new ArrayList<>(connections)) {
                try {
                    if (connection.isClosed()) {
                        unregisterConnection(connection);
                        try {
                            releaseTrackedConnection(namespace);
                        } catch (RuntimeException e) {
                            cleanupFailure = merge(cleanupFailure, e);
                        }
                    } else {
                        stillOpen = true;
                    }
                } catch (SQLException | RuntimeException e) {
                    stillOpen = true;
                }
            }
            if (stillOpen) {
                open.add(namespace);
            }
        }
        for (String namespace : new ArrayList<>(NAMESPACES.keySet())) {
            Integer active = NAMESPACES.get(namespace);
            if (active != null && active > 0 && !open.contains(namespace)) {
                open.add(namespace);
            }
        }
        if (open.isEmpty()) {
            return cleanupFailure;
        }
        IllegalStateException failure =
                new IllegalStateException(
                        "Conformance session stopped with "
                                + open.size()
                                + " namespace(s) still open: "
                                + open);
        if (cleanupFailure != null) {
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }

    private static void releaseTrackedConnection(String namespace) {
        if (RETAINED_NAMESPACES.contains(namespace)) {
            releaseConnection(namespace);
        } else {
            releaseNamespace(namespace);
        }
    }

    private static RuntimeException failedNamespacesFailure() {
        if (FAILED_NAMESPACES.isEmpty()) {
            return null;
        }
        IllegalStateException failure =
                new IllegalStateException(
                        "Namespace cleanup failed for: " + FAILED_NAMESPACES.keySet());
        FAILED_NAMESPACES.values().forEach(failure::addSuppressed);
        return failure;
    }

    private static RuntimeException reapRetainedNamespaces() {
        return reapRetainedNamespaces(adminBaseUrl, DEFAULT_ADMIN);
    }

    private static RuntimeException reapRetainedNamespaces(
            String adminUrl, AdminClient admin) {
        RuntimeException failure = null;
        for (String namespace : RETAINED_NAMESPACES) {
            try {
                deleteNamespace(namespace, adminUrl, admin);
            } catch (RuntimeException e) {
                failure = merge(failure, e);
            }
        }
        RETAINED_NAMESPACES.clear();
        return failure;
    }

    static synchronized void registerConnection(String namespace, Connection connection) {
        CONNECTIONS
                .computeIfAbsent(
                        namespace,
                        ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(connection);
    }

    static synchronized void unregisterConnection(Connection connection) {
        Iterator<Map.Entry<String, Set<Connection>>> iterator =
                CONNECTIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Set<Connection>> entry = iterator.next();
            entry.getValue().remove(connection);
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    public static String jdbcUrl(String namespace) {
        return jdbcUrl(namespace, false);
    }

    static String jdbcUrl(String namespace, boolean retained) {
        start();
        ensureNamespace(namespace, retained, adminBaseUrl, DEFAULT_ADMIN);
        return "jdbc:sqlite:" + httpBaseUrl + "/dev/" + namespace;
    }

    public static synchronized void releaseNamespace(String namespace) {
        releaseNamespace(namespace, adminBaseUrl, DEFAULT_ADMIN);
    }

    public static synchronized void releaseRetainedConnection(String namespace) {
        releaseConnection(namespace);
    }

    static synchronized void releaseConnection(String namespace) {
        int active = NAMESPACES.getOrDefault(namespace, 0);
        if (active == 0) {
            return;
        }
        if (active > 1) {
            NAMESPACES.put(namespace, active - 1);
            return;
        }
        NAMESPACES.remove(namespace);
    }

    static synchronized void releaseNamespace(
            String namespace, String adminUrl, AdminClient admin) {
        int active = NAMESPACES.getOrDefault(namespace, 0);
        if (active == 0) {
            return;
        }
        if (active > 1) {
            NAMESPACES.put(namespace, active - 1);
            return;
        }
        NAMESPACES.remove(namespace);
        if (adminUrl == null) {
            return;
        }

        try {
            deleteNamespace(namespace, adminUrl, admin);
        } catch (RuntimeException e) {
            FAILED_NAMESPACES.put(namespace, e);
            throw e;
        }
    }

    static boolean isReadyStatus(int status) {
        return status == 405 || (status >= 200 && status < 300);
    }

    static synchronized void ensureNamespace(String namespace) {
        ensureNamespace(namespace, adminBaseUrl, DEFAULT_ADMIN);
    }

    static synchronized void ensureNamespace(
            String namespace, String adminUrl, AdminClient admin) {
        ensureNamespace(namespace, false, adminUrl, admin);
    }

    static synchronized void ensureNamespace(
            String namespace, boolean retained, String adminUrl, AdminClient admin) {
        RuntimeException failed = FAILED_NAMESPACES.get(namespace);
        if (failed != null) {
            throw new IllegalStateException(
                    "Namespace "
                            + namespace
                            + " cannot be reused because an earlier cleanup failed",
                    failed);
        }

        int active = NAMESPACES.getOrDefault(namespace, 0);
        if (active > 0) {
            NAMESPACES.put(namespace, active + 1);
            if (retained) {
                RETAINED_NAMESPACES.add(namespace);
            }
            return;
        }

        if (retained && RETAINED_NAMESPACES.contains(namespace)) {
            NAMESPACES.put(namespace, 1);
            return;
        }

        if (adminUrl == null) {
            throw new IllegalStateException("libSQL server is not running");
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        adminUrl + "/v1/namespaces/" + namespace + "/create"))
                        .header("Content-Type", "application/json")
                        .timeout(REQUEST_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();

        AdminResponse response;
        try {
            response = admin.send(request);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create namespace " + namespace, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while creating namespace " + namespace, e);
        }

        int status = response.status();
        if (status >= 200 && status < 300) {
            NAMESPACES.put(namespace, 1);
            if (retained) {
                RETAINED_NAMESPACES.add(namespace);
            }
            return;
        }

        IllegalStateException failure =
                new IllegalStateException(
                        "Failed to create namespace "
                                + namespace
                                + ": HTTP "
                                + status
                                + " "
                                + response.body());
        if (status == 409) {
            failure =
                    new IllegalStateException(
                            "Namespace "
                                    + namespace
                                    + " already exists and cannot be reused: HTTP "
                                    + status
                                    + " "
                                    + response.body());
            FAILED_NAMESPACES.put(namespace, failure);
        }
        throw failure;
    }

    private static void deleteNamespace(
            String namespace, String adminUrl, AdminClient admin) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(adminUrl + "/v1/namespaces/" + namespace))
                        .timeout(REQUEST_TIMEOUT)
                        .DELETE()
                        .build();

        RuntimeException failure = null;
        for (int attempt = 1; attempt <= DELETE_ATTEMPTS; attempt++) {
            try {
                AdminResponse response = admin.send(request);
                int status = response.status();
                if ((status >= 200 && status < 300) || status == 404) {
                    return;
                }
                failure =
                        new IllegalStateException(
                                "Failed to delete namespace "
                                        + namespace
                                        + ": HTTP "
                                        + status
                                        + " "
                                        + response.body());
                if (!isRetryable(status) || attempt == DELETE_ATTEMPTS) {
                    break;
                }
            } catch (IOException e) {
                failure =
                        new IllegalStateException(
                                "Failed to delete namespace " + namespace, e);
                if (attempt == DELETE_ATTEMPTS) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while deleting namespace " + namespace, e);
            }

            sleepBeforeRetry(namespace, failure);
        }
        throw failure;
    }

    @FunctionalInterface
    interface AdminClient {
        AdminResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    record AdminResponse(int status, String body) {}

    private static boolean isRetryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static void sleepBeforeRetry(String namespace, RuntimeException failure) {
        try {
            Thread.sleep(DELETE_RETRY_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IllegalStateException interrupted =
                    new IllegalStateException(
                            "Interrupted while deleting namespace " + namespace, e);
            interrupted.addSuppressed(failure);
            throw interrupted;
        }
    }
}
