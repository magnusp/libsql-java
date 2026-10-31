package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.client.LibsqlClientConfig;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class LibsqlUrlParserTest {

    @Test
    void testParseUrlWithNamespacePath() throws SQLException {
        String url = "jdbc:libsql:http://localhost:8080/dev/tenant1";
        LibsqlClientConfig config = LibsqlUrlParser.parse(url, new Properties());

        assertThat(config.url()).isEqualTo("http://localhost:8080/dev/tenant1");
    }

    @Test
    void testParseUrlWithNamespacePathAndTrailingSlash() throws SQLException {
        String url = "jdbc:libsql:http://localhost:8080/dev/tenant2/";
        LibsqlClientConfig config = LibsqlUrlParser.parse(url, new Properties());

        assertThat(config.url()).isEqualTo("http://localhost:8080/dev/tenant2/");
    }

    @Test
    void testParseUrlWithNamespacePathAndQueryParams() throws SQLException {
        String url = "jdbc:libsql:http://localhost:8080/dev/tenant3?authToken=secretToken&connectTimeout=5000";
        LibsqlClientConfig config = LibsqlUrlParser.parse(url, new Properties());

        assertThat(config.url()).isEqualTo("http://localhost:8080/dev/tenant3");
        assertThat(config.authToken()).isEqualTo("secretToken");
        assertThat(config.connectTimeout().toMillis()).isEqualTo(5000);
    }

    @Test
    void testIgnoresSocketTimeoutWithoutConnectTimeout() throws SQLException {
        String url = "jdbc:libsql:http://localhost:8080/dev/tenant?socketTimeout=5000";
        LibsqlClientConfig config = LibsqlUrlParser.parse(url, new Properties());

        assertThat(config.url()).isEqualTo("http://localhost:8080/dev/tenant");
        assertThat(config.connectTimeout()).isEqualTo(LibsqlClientConfig.DEFAULT_CONNECT_TIMEOUT);
    }

    @Test
    void testConnectTimeoutStillWinsAlongsideSocketTimeout() throws SQLException {
        String url = "jdbc:libsql:http://localhost:8080/dev/tenant?socketTimeout=5000&connectTimeout=2500";
        LibsqlClientConfig config = LibsqlUrlParser.parse(url, new Properties());

        assertThat(config.connectTimeout().toMillis()).isEqualTo(2500);
    }

    @Test
    void testRejectsANonNumericTimeoutAsAJDBCError() {
        assertThatThrownBy(() -> LibsqlUrlParser.parse(
                        "jdbc:libsql:http://localhost:8080/dev/tenant?connectTimeout=soon",
                        new Properties()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("connectTimeout")
                .hasCauseInstanceOf(NumberFormatException.class);

        Properties properties = new Properties();
        properties.setProperty("busyTimeout", "later");
        assertThatThrownBy(() -> LibsqlUrlParser.parse("jdbc:libsql:http://localhost:8080", properties))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("busyTimeout")
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void testParseWebSocketUrlKeepsTheSchemeAndPath() throws SQLException {
        LibsqlClientConfig config = LibsqlUrlParser.parse(
                "jdbc:libsql:ws://localhost:8080/dev/tenant?authToken=secretToken", new Properties());

        assertThat(config.url()).isEqualTo("ws://localhost:8080/dev/tenant");
        assertThat(config.authToken()).isEqualTo("secretToken");
    }

    @Test
    void testParseWebSocketUrlFromSqlitePrefix() throws SQLException {
        LibsqlClientConfig config = LibsqlUrlParser.parse(
                "jdbc:sqlite:wss://example.com/dev/tenant", new Properties());

        assertThat(config.url()).isEqualTo("wss://example.com/dev/tenant");
    }

    @Test
    void testTlsUpgradesWebSocketToSecureWebSocket() throws SQLException {
        LibsqlClientConfig config = LibsqlUrlParser.parse(
                "jdbc:libsql:ws://localhost:8080?tls=true", new Properties());

        assertThat(config.url()).isEqualTo("wss://localhost:8080");
    }

    @Test
    void testTlsKeepsAlreadySecureSchemes() throws SQLException {
        assertThat(LibsqlUrlParser.parse("jdbc:libsql:https://example.com?tls=true", new Properties()).url())
                .isEqualTo("https://example.com");
        assertThat(LibsqlUrlParser.parse("jdbc:libsql:wss://example.com?tls=true", new Properties()).url())
                .isEqualTo("wss://example.com");
    }

    @Test
    void testParseEncodesWebSocketEncoding() throws SQLException {
        assertThat(LibsqlUrlParser.parse(
                        "jdbc:libsql:ws://localhost:8080?encoding=protobuf", new Properties())
                .encoding())
                .isEqualTo(LibsqlClientConfig.Encoding.PROTOBUF);
        assertThat(LibsqlUrlParser.parse(
                        "jdbc:libsql:wss://example.com?encoding=PROTO", new Properties())
                .encoding())
                .isEqualTo(LibsqlClientConfig.Encoding.PROTOBUF);
        assertThat(LibsqlUrlParser.parse(
                        "jdbc:libsql:ws://localhost:8080?encoding=json", new Properties())
                .encoding())
                .isEqualTo(LibsqlClientConfig.Encoding.JSON);
        assertThat(LibsqlUrlParser.parse("jdbc:libsql:ws://localhost:8080", new Properties())
                .encoding())
                .isEqualTo(LibsqlClientConfig.Encoding.JSON);
    }

    @Test
    void testRejectsUnknownEncoding() {
        assertThatThrownBy(() -> LibsqlUrlParser.parse(
                        "jdbc:libsql:ws://localhost:8080?encoding=msgpack", new Properties()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("msgpack");
    }

    @Test
    void testRejectsProtobufOnHttpTransport() {
        assertThatThrownBy(() -> LibsqlUrlParser.parse(
                        "jdbc:libsql:http://localhost:8080?encoding=protobuf", new Properties()))
                .isInstanceOf(SQLException.class)
                .hasMessage("encoding=protobuf is only supported for ws/wss URLs");
        assertThatThrownBy(() -> LibsqlUrlParser.parse(
                        "jdbc:libsql:https://example.com?encoding=protobuf", new Properties()))
                .isInstanceOf(SQLException.class)
                .hasMessage("encoding=protobuf is only supported for ws/wss URLs");
    }

    @Test
    void testAcceptsWebSocketUrlForms() {
        assertThat(LibsqlUrlParser.acceptsUrl("jdbc:libsql:ws://localhost:8080")).isTrue();
        assertThat(LibsqlUrlParser.acceptsUrl("jdbc:libsql:wss://localhost:8080")).isTrue();
        assertThat(LibsqlUrlParser.acceptsUrl("jdbc:sqlite:ws://localhost:8080")).isTrue();
        assertThat(LibsqlUrlParser.acceptsUrl("jdbc:sqlite:wss://localhost:8080")).isTrue();
        assertThat(LibsqlUrlParser.acceptsUrl("jdbc:other:ws://localhost:8080")).isFalse();
    }

    @Test
    void testMetadataUrlRedactsAuthenticationQueryParameters() {
        assertThat(LibsqlUrlParser.metadataUrl(
                        "jdbc:libsql:http://localhost:8080/dev/tenant"
                                + "?authToken=secretToken&connectTimeout=5000"))
                .isEqualTo("jdbc:libsql:http://localhost:8080/dev/tenant?connectTimeout=5000");
        assertThat(LibsqlUrlParser.metadataUrl(
                        "jdbc:libsql:http://localhost:8080?jwt=secretToken&password=secret"))
                .isEqualTo("jdbc:libsql:http://localhost:8080");
    }

    @Test
    void testMetadataUrlRedactsUserInfo() {
        assertThat(LibsqlUrlParser.metadataUrl("jdbc:libsql:http://user:pass@localhost:8080/dev"))
                .isEqualTo("jdbc:libsql:http://localhost:8080/dev");
    }

    @Test
    void testMetadataUrlLeavesANonCredentialUrlUnchanged() {
        String url = "jdbc:sqlite:http://localhost:8080/dev/tenant?connectTimeout=5000";
        assertThat(LibsqlUrlParser.metadataUrl(url)).isEqualTo(url);
    }
}
