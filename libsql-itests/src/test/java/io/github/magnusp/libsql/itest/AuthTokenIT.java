package io.github.magnusp.libsql.itest;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
public class AuthTokenIT {

    private static final int PORT = 8080;
    private static final String PUBLIC_KEY_IN_CONTAINER = "/etc/sqld/libsql-jwt-public.pem";

    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final String READ_ONLY_TOKEN = mintToken("ro");
    private static final String READ_WRITE_TOKEN = mintToken("rw");

    @Container
    private static final GenericContainer<?> SQLD = new GenericContainer<>(SqldContainerBase.LIBSQL_IMAGE)
            .withExposedPorts(PORT)
            .withEnv("SQLD_NODE", "primary")
            .withEnv("SQLD_AUTH_JWT_KEY_FILE", PUBLIC_KEY_IN_CONTAINER)
            .withCopyFileToContainer(MountableFile.forHostPath(writePublicKey()), PUBLIC_KEY_IN_CONTAINER)
            // Under auth the pipeline endpoint answers 401, so readiness comes from /health.
            .waitingFor(Wait.forHttp("/health")
                    .forPort(PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    @Test
    void aRequestWithoutATokenIsRefused() throws Exception {
        try (Connection connection = DriverManager.getConnection(url(null));
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("SELECT 1"))
                    .isInstanceOfSatisfying(SQLInvalidAuthorizationSpecException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo("28000");
                    });
        }
    }

    @Test
    void aReadOnlyTokenCanRead() throws Exception {
        createTable();
        try (Connection connection = DriverManager.getConnection(url(READ_ONLY_TOKEN));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM auth_rows")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void aReadOnlyTokenCannotWriteAndTheConnectionStillReads() throws Exception {
        createTable();
        try (Connection connection = DriverManager.getConnection(url(READ_ONLY_TOKEN));
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO auth_rows (id) VALUES (9001)"))
                    .isInstanceOfSatisfying(SQLInvalidAuthorizationSpecException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo("28000");
                        assertThat(e.getMessage()).contains("Authorization forbidden");
                    });

            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM auth_rows")) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    @Test
    void aReadWriteTokenCanWrite() throws Exception {
        createTable();
        try (Connection connection = DriverManager.getConnection(url(READ_WRITE_TOKEN));
             Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("INSERT OR REPLACE INTO auth_rows (id) VALUES (9002)"))
                    .isEqualTo(1);
        }
    }

    private static void createTable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url(READ_WRITE_TOKEN));
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS auth_rows (id INTEGER PRIMARY KEY)");
        }
    }

    private static String url(String token) {
        String base = "jdbc:libsql:http://" + SQLD.getHost() + ":" + SQLD.getMappedPort(PORT);
        return token == null ? base : base + "?authToken=" + token;
    }

    private static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 is required to mint a sqld token", e);
        }
    }

    private static Path writePublicKey() {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(KEY_PAIR.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        try {
            Path file = Files.createTempFile("libsql-jwt-", ".pem");
            file.toFile().deleteOnExit();
            // The copy keeps the source mode, and sqld runs as an unprivileged container user.
            file.toFile().setReadable(true, false);
            return Files.writeString(file, pem, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String mintToken(String role) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"EdDSA\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                ("{\"a\":\"" + role + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(KEY_PAIR.getPrivate());
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + encoder.encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not mint a " + role + " token", e);
        }
    }
}
