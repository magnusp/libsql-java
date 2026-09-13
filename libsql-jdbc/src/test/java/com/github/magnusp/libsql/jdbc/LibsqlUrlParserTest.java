package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.client.LibsqlClientConfig;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

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
}
