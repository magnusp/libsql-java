package com.github.magnusp.libsql.itest;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import eu.rekawek.toxiproxy.model.toxic.Latency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
public class NetworkToxicityIT {

    private static final Network NETWORK = Network.newNetwork();

    private static final GenericContainer<?> LIBSQL = new GenericContainer<>("ghcr.io/tursodatabase/libsql-server:latest")
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

    @AfterEach
    void resetToxiproxy() throws IOException {
        proxy.enable();
        for (var t : proxy.toxics().getAll()) {
            t.remove();
        }
    }

    private String getProxiedJdbcUrl(int timeoutMs) {
        return "jdbc:libsql:http://" + TOXIPROXY.getHost() + ":" + mappedProxyPort + "?connectTimeout=" + timeoutMs;
    }

    @Test
    void testNormalTrafficThroughProxy() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getProxiedJdbcUrl(5000));
             Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS toxi_test (id INT PRIMARY KEY, msg TEXT)");
            st.executeUpdate("INSERT INTO toxi_test VALUES (1, 'clean_network')");

            try (ResultSet rs = st.executeQuery("SELECT msg FROM toxi_test WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("clean_network");
            }
        }
    }

    @Test
    void testDisconnectConnectionCut() throws IOException {
        proxy.disable();

        assertThatThrownBy(() -> {
            try (Connection conn = DriverManager.getConnection(getProxiedJdbcUrl(1000));
                 Statement st = conn.createStatement()) {
                st.executeQuery("SELECT 1");
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    void testPacketDropAndHighLatencyWithRecovery() throws IOException, SQLException {
        Latency latency = proxy.toxics().latency("latency_toxic", ToxicDirection.DOWNSTREAM, 1500);

        assertThatThrownBy(() -> {
            try (Connection conn = DriverManager.getConnection(getProxiedJdbcUrl(500));
                 Statement st = conn.createStatement()) {
                st.executeQuery("SELECT 1");
            }
        }).isInstanceOf(SQLException.class);

        latency.remove();

        try (Connection conn = DriverManager.getConnection(getProxiedJdbcUrl(5000));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 42")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(42);
        }
    }

    @Test
    void testSlicingPacketFragmentation() throws IOException, SQLException {
        proxy.toxics().slicer("slice_toxic", ToxicDirection.DOWNSTREAM, 16, 20);

        try (Connection conn = DriverManager.getConnection(getProxiedJdbcUrl(5000));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 'fragmented_packet_data_stream'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("fragmented_packet_data_stream");
        }
    }
}
