package com.github.magnusp.libsql.itest;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

@Testcontainers
public abstract class SqldContainerBase {

    static final String LIBSQL_IMAGE = requireLibsqlImage();
    private static final String NGINX_IMAGE = "nginx:1.27-alpine";
    private static final int PORT = 8080;
    private static final int TLS_PORT = 8443;

    private static final Network NETWORK = Network.newNetwork();

    private static String requireLibsqlImage() {
        String image = System.getProperty("libsql.image");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(
                    "libsql.image is not set. Run the integration tests with Maven so the"
                            + " pinned sqld image from the root pom is used.");
        }
        return image;
    }

    @Container
    public static final GenericContainer<?> LIBSQL_CONTAINER = new GenericContainer<>(LIBSQL_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("sqld")
            .withExposedPorts(PORT)
            .withEnv("SQLD_NODE", "primary")
            .withEnv("SQLD_DISABLE_INTELLIGENT_THROTTLING", "true")
            .withEnv("SQLD_CONNECTION_CREATION_TIMEOUT_SEC", "30")
            .waitingFor(Wait.forHttp("/v2/pipeline")
                    .forPort(PORT)
                    .forStatusCode(405)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    /**
     * A TLS terminator in front of sqld, so the {@code wss://} tests exercise a real handshake.
     * Started on demand: only the WebSocket tests need it.
     */
    private static final GenericContainer<?> TLS_PROXY = new GenericContainer<>(DockerImageName.parse(NGINX_IMAGE))
            .withNetwork(NETWORK)
            .withExposedPorts(TLS_PORT)
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/nginx.conf"), "/etc/nginx/nginx.conf")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("tls/server.crt.pem"), "/etc/nginx/tls/server.crt.pem")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("tls/server.key.pem"), "/etc/nginx/tls/server.key.pem")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));

    static String getWebSocketUrl() {
        return "ws://" + LIBSQL_CONTAINER.getHost() + ":" + LIBSQL_CONTAINER.getMappedPort(PORT);
    }

    static synchronized String getSecureWebSocketUrl() {
        if (!LIBSQL_CONTAINER.isRunning()) {
            LIBSQL_CONTAINER.start();
        }
        if (!TLS_PROXY.isRunning()) {
            TLS_PROXY.start();
        }
        return "wss://" + TLS_PROXY.getHost() + ":" + TLS_PROXY.getMappedPort(TLS_PORT);
    }

    static String getHttpUrl() {
        return "http://" + LIBSQL_CONTAINER.getHost() + ":" + LIBSQL_CONTAINER.getMappedPort(PORT);
    }
}
