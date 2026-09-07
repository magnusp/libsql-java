# Testcontainers Usage Guide: libSQL (`sqld`) & Toxiproxy

This document explains how Testcontainers is configured and utilized in this project to execute integration tests, ACID compliance validations, and network toxicity scenarios against real instances of the **libSQL server (`sqld`)**.

---

## 1. Overview & Image Selection

Testing a pure-Java JDBC driver for Turso requires verifying real SQLite behavior, lock contention, HTTP pipeline batons, and network edge cases over the wire. Rather than mocking HTTP endpoints, we run real `sqld` instances using Testcontainers.

### Container Image
* **Image**: `ghcr.io/tursodatabase/libsql-server:latest`
* **Exposed Port**: `8080` (HTTP and Hrana pipeline endpoint)

The image can also be overridden via the system property `-Dlibsql.image=<custom_image>`.

---

## 2. Base Container Setup (`SqldContainerBase.java`)

All standard integration tests inherit from [`SqldContainerBase`](file:///home/magnus/src/local/turso-java/src/test/java/org/libsql/jdbc/base/SqldContainerBase.java) to share a managed, singleton container instance across test executions.

### Implementation
```java
package org.libsql.jdbc.base;

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

    public static String getJdbcUrl() {
        return "jdbc:libsql:" + getHttpUrl();
    }
}
```

---

## 3. Key Configuration Parameters

### A. Environment Variables
* **`SQLD_NODE=primary`**: Runs the container as the standalone primary write-capable node (no external replication cluster required).
* **`SQLD_DISABLE_INTELLIGENT_THROTTLING=true`**: Disables internal rate-limiting heuristics, preventing spurious test failures during rapid bursts of concurrent connections or statements.
* **`SQLD_CONNECTION_CREATION_TIMEOUT_SEC=30`**: Extends the SQLite file connection creation timeout to avoid transient lock timeouts during heavy parallel integration test runs.

### B. Healthcheck & Wait Strategy
Because `/v2/pipeline` requires an HTTP `POST` request with a JSON body, standard `GET /` health checks can be misleading:
* A `GET` request to `/v2/pipeline` returns **HTTP 405 Method Not Allowed**, which confirms the HTTP server and routing engine are fully initialized and listening.
* `Wait.forHttp("/v2/pipeline").forPort(PORT).forStatusCode(405)` provides a fast and reliable readiness probe.

---

## 4. Advanced: Network Toxicity Simulation with Toxiproxy

To verify driver resiliency against real-world network anomalies (e.g. latency spikes, connection cuts, and slow timeouts), [`NetworkToxicityIntegrationIT`](file:///home/magnus/src/local/turso-java/src/test/java/org/libsql/jdbc/integration/NetworkToxicityIntegrationIT.java) pairs `sqld` with a **Shopify Toxiproxy** container within a dedicated Docker bridge network.

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
public class NetworkToxicityIntegrationIT {

    private static final Network NETWORK = Network.newNetwork();

    private static final GenericContainer<?> LIBSQL = new GenericContainer<>("ghcr.io/tursodatabase/libsql-server:latest")
            .withNetwork(NETWORK)
            .withNetworkAliases("libsql-srv")
            .withExposedPorts(8080)
            .withEnv("SQLD_NODE", "primary");

    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:latest")
            .withNetwork(NETWORK);

    private static ToxiproxyContainer.ContainerProxy proxy;

    @BeforeAll
    static void setUpAll() {
        LIBSQL.start();
        TOXIPROXY.start();
        proxy = TOXIPROXY.getProxy(LIBSQL, 8080);
    }
}
```

### Example Toxics Tested
1. **Network Latency & Jitter**:
   ```java
   proxy.toxics().latency("latency-toxic", ToxicDirection.DOWNSTREAM, 300).setJitter(50);
   ```
2. **Abrupt Connection Drop (TCP RST / Cut)**:
   ```java
   proxy.setConnectionCut(true);
   // Verifies that driver throws SQLException rather than hanging indefinitely
   ```
3. **Bandwidth Throttling / Slowloris**:
   ```java
   proxy.toxics().bandwidth("bandwidth-toxic", ToxicDirection.DOWNSTREAM, 10); // 10 KB/s
   ```

---

## 5. Running the Tests

To run the integration tests using Maven:

```bash
# Run unit tests only
./mvnw test

# Run full integration test suite against Testcontainers
./mvnw verify

# Override the sqld container image
./mvnw verify -Dlibsql.image=ghcr.io/tursodatabase/libsql-server:v0.24.33
```
