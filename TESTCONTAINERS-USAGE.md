# Testcontainers Usage Guide: libSQL (`sqld`) & Toxiproxy

This document explains how Testcontainers is configured and utilized in this project to execute integration tests, ACID compliance validations, and network toxicity scenarios against real instances of the **libSQL server (`sqld`)**.

---

## 1. Overview & Image Selection

Testing a pure-Java JDBC driver for libSQL requires verifying real SQLite behavior, lock contention, HTTP pipeline batons, and network edge cases over the wire. Rather than mocking HTTP endpoints, we run real `sqld` instances using Testcontainers.

The integration tests live in `libsql-itests/src/test/java/com/github/magnusp/libsql/itest/`:

* `SqldContainerBase` — shared singleton `sqld` container that the JDBC and client tests extend.
* `LibsqlJdbcIT`, `LibsqlHttpClientIT`, `HikariCpIntegrationIT`, `TransactionLifecycleIT`, `GeneratedKeysIT`, and `DatabaseMetaDataIT` — extend `SqldContainerBase`.
* `NetworkToxicityIT` — standalone Toxiproxy scenario with its own `sqld` container on a dedicated network.
* `NamespacePathIT` — standalone `sqld` container started with namespaces enabled and an admin port.

### Container Image
* **Image**: `ghcr.io/tursodatabase/libsql-server:v0.24.33`
* **Exposed Port**: `8080` (HTTP and Hrana pipeline endpoint)

The image is pinned to a released `sqld` version so that `latest` cannot silently change
the behavior under test. The pin is defined once in the root `pom.xml` as the
`libsql.image` property, and Maven passes it to both the integration and conformance
test JVMs. To test a different version, override the property:

```bash
./mvnw verify -Dlibsql.image=ghcr.io/tursodatabase/libsql-server:v0.24.33
```

---

## 2. Base Container Setup (`SqldContainerBase.java`)

All standard integration tests inherit from [`SqldContainerBase`](libsql-itests/src/test/java/com/github/magnusp/libsql/itest/SqldContainerBase.java) in the `com.github.magnusp.libsql.itest` package to share a managed, singleton container instance across test executions.

### Implementation
```java
package com.github.magnusp.libsql.itest;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

@Testcontainers
public abstract class SqldContainerBase {

    static final String LIBSQL_IMAGE = requireLibsqlImage();
    private static final int PORT = 8080;

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
```

`SqldContainerBase` exposes only `getHttpUrl()`. Each JDBC test class builds its own
driver URL from it, for example `LibsqlJdbcIT`, `GeneratedKeysIT`,
`TransactionLifecycleIT`, and `DatabaseMetaDataIT` each define:

```java
private String getDriverJdbcUrl() {
    return "jdbc:libsql:" + getHttpUrl();
}
```

`HikariCpIntegrationIT` passes the same URL to HikariCP with
`config.setJdbcUrl("jdbc:libsql:" + getHttpUrl())`, and `LibsqlHttpClientIT` passes
`getHttpUrl()` directly to `new LibsqlClientConfig(getHttpUrl(), null)`.

---

## 3. Key Configuration Parameters

### A. Environment Variables
* **`SQLD_NODE=primary`**: Runs the container as the standalone primary write-capable node (no external replication cluster required).
* **`SQLD_DISABLE_INTELLIGENT_THROTTLING=true`**: Disables internal rate-limiting heuristics, preventing spurious test failures during rapid bursts of concurrent connections or statements.
* **`SQLD_CONNECTION_CREATION_TIMEOUT_SEC=30`**: Extends the SQLite file connection creation timeout to avoid transient lock timeouts during heavy parallel integration test runs.

### B. Healthcheck & Wait Strategy
The test containers probe `/v2/pipeline` for readiness, matching the code in
`SqldContainerBase`, `NetworkToxicityIT`, `NamespacePathIT`, and
`XerialTestServer`. The driver itself talks Hrana 3 to `sqld` at `/v3/pipeline`
(see `LibsqlHttpClient`, which posts pipeline requests to
`base + "/v3/pipeline"`). Both endpoints require an HTTP `POST` request with a
JSON body, so standard `GET /` health checks can be misleading:
* A `GET` request to `/v2/pipeline` returns **HTTP 405 Method Not Allowed**, which confirms the HTTP server and routing engine are fully initialized and listening.
* `Wait.forHttp("/v2/pipeline").forPort(PORT).forStatusCode(405)` with a 60-second startup timeout provides a fast and reliable readiness probe.

---

## 4. Advanced: Network Toxicity Simulation with Toxiproxy

To verify driver resiliency against real-world network anomalies (e.g. latency spikes, connection cuts, and packet fragmentation), [`NetworkToxicityIT`](libsql-itests/src/test/java/com/github/magnusp/libsql/itest/NetworkToxicityIT.java) pairs `sqld` with a **Shopify Toxiproxy** container within a dedicated Docker bridge network. Unlike the tests above, `NetworkToxicityIT` does not extend `SqldContainerBase`; it declares its own `sqld` container on that network and reuses `SqldContainerBase.LIBSQL_IMAGE` for the image name.

### Architecture

```
+----------------------------------------------------------------+
| Docker Network (bridge)                                        |
|                                                                |
|  [ JDBC Driver ]  ──>  [ Toxiproxy Container ]  ──>  [ sqld ]  |
|                          (Injects latency,             :8080   |
|                           packet drops, cuts)                  |
+----------------------------------------------------------------+
```

### Setup Code
```java
@Testcontainers
public class NetworkToxicityIT {

    private static final Network NETWORK = Network.newNetwork();

    private static final GenericContainer<?> LIBSQL =
            new GenericContainer<>(SqldContainerBase.LIBSQL_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("libsql-srv")
            .withExposedPorts(8080)
            .withEnv("SQLD_NODE", "primary")
            .withEnv("SQLD_DISABLE_INTELLIGENT_THROTTLING", "true")
            .withEnv("SQLD_CONNECTION_CREATION_TIMEOUT_SEC", "30")
            .waitingFor(Wait.forHttp("/v2/pipeline")
                    .forPort(8080)
                    .forStatusCode(405)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:latest"))
            .withNetwork(NETWORK);

    private static ToxiproxyClient toxiproxyClient;
    private static Proxy proxy;
    private static int mappedProxyPort;

    @BeforeAll
    static void setUpAll() throws IOException {
        LIBSQL.start();
        TOXIPROXY.start();
        toxiproxyClient = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
        proxy = toxiproxyClient.createProxy("libsql-proxy", "0.0.0.0:8666", "libsql-srv:8080");
        mappedProxyPort = TOXIPROXY.getMappedPort(8666);
    }
}
```

Tests connect through the proxy with
`"jdbc:libsql:http://" + TOXIPROXY.getHost() + ":" + mappedProxyPort + "?connectTimeout=" + timeoutMs`.
After each test, `resetToxiproxy()` re-enables the proxy and removes all toxics.

`NamespacePathIT` follows the same standalone pattern for a different scenario: it
starts its own container with ports `8080` and `9090` exposed, runs
`sqld --http-listen-addr 0.0.0.0:8080 --enable-namespaces --admin-listen-addr 0.0.0.0:9090`,
creates namespaces through the admin API, and then verifies isolation through JDBC
URLs such as `"jdbc:libsql:" + httpBaseUrl + "/dev/ns_alpha"`.

### Example Toxics Tested
1. **Connection Cut**:
   ```java
   proxy.disable();
   // Verifies that the driver throws SQLException rather than hanging indefinitely
   ```
2. **High Latency, Then Recovery**:
   ```java
   Latency latency = proxy.toxics().latency("latency_toxic", ToxicDirection.DOWNSTREAM, 1500);
   // A query with a 500 ms connect timeout fails with SQLException;
   // after latency.remove(), the same proxy serves SELECT 42 normally
   ```
3. **Packet Fragmentation (Slicer)**:
   ```java
   proxy.toxics().slicer("slice_toxic", ToxicDirection.DOWNSTREAM, 16, 20);
   ```

The class also verifies clean traffic through the proxy
(`testNormalTrafficThroughProxy`) and that a failed rollback under a cut connection
throws `SQLException` and keeps the transaction available for retry once the proxy
is re-enabled (`testFailedRollbackThrowsAndKeepsTransactionForRetry`).

---

## 5. Running the Tests

To run the integration tests using Maven:

```bash
# Run unit tests only
mise exec -- ./mvnw test

# Run full integration test suite against Testcontainers
mise exec -- ./mvnw clean verify

# Override the sqld container image
./mvnw verify -Dlibsql.image=ghcr.io/tursodatabase/libsql-server:v0.24.33
```

To run a single integration test, use the Failsafe selector:

```bash
DOCKER_CONFIG="$PWD/libsql-conformance/src/test/docker-config" \
  ./mvnw -pl libsql-itests -am -Dit.test=HikariCpIntegrationIT verify
```

### Docker Config Under the `nono` Sandbox

This environment may run inside a `nono` filesystem sandbox. The sandbox can block
reads of `~/.docker/config.json` even when `docker info` works. Testcontainers then
fails with:

```text
ServiceConfigurationError: ... EnvironmentAndSystemPropertyClientProviderStrategy could not be instantiated
Caused by: java.io.FileNotFoundException: /home/<user>/.docker/config.json (Permission denied)
```

Check the exact path with:

```bash
nono why --self --path ~/.docker/config.json --op read
```

Do not change file permissions or the `nono` profile from inside the sandbox.

The repository ships one shared readable Docker config at
`libsql-conformance/src/test/docker-config/config.json`. It is an empty auth config,
so it works only for public images such as the `sqld` and Toxiproxy images that the
integration tests use.

`libsql-itests` has no copy step. Point `DOCKER_CONFIG` at the shared config
directory in the shell, and the Failsafe fork inherits it:

```bash
DOCKER_CONFIG="$PWD/libsql-conformance/src/test/docker-config" \
  ./mvnw -pl libsql-itests -am verify
```

Use the absolute path shown (here expanded through `$PWD`). A relative
`DOCKER_CONFIG` is resolved against the fork's working directory, not the shell's.
