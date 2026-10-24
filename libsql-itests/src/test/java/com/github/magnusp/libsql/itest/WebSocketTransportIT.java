package com.github.magnusp.libsql.itest;

import com.github.magnusp.libsql.client.LibsqlClientConfig;
import com.github.magnusp.libsql.jdbc.LibsqlUrlParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the JDBC driver over Hrana 3 JSON on a WebSocket ({@code hrana3} subprotocol), which is
 * the same server and the same protocol structures as the HTTP transport but a different envelope.
 * Every case runs over plain {@code ws://} and over {@code wss://} through a TLS terminator.
 */
public class WebSocketTransportIT extends SqldContainerBase {

    static Stream<String> schemes() {
        return Stream.of("ws", "wss");
    }

    private static String url(String scheme) {
        return "wss".equals(scheme) ? getSecureWebSocketUrl() : getWebSocketUrl();
    }

    private static Connection connect(String scheme) throws SQLException {
        return DriverManager.getConnection("jdbc:libsql:" + url(scheme));
    }

    @ParameterizedTest
    @MethodSource("schemes")
    void selectReturnsTypedValues(String scheme) throws SQLException {
        try (Connection connection = connect(scheme);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT 1 AS i, 1.5 AS f, 'hello' AS t, x'010203' AS b, NULL AS n")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("i")).isEqualTo(1);
            assertThat(resultSet.getDouble("f")).isEqualTo(1.5);
            assertThat(resultSet.getString("t")).isEqualTo("hello");
            assertThat(resultSet.getBytes("b")).containsExactly(1, 2, 3);
            assertThat(resultSet.getObject("n")).isNull();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("schemes")
    void transactionCommitsAndRollsBackOnOneStream(String scheme) throws SQLException {
        try (Connection connection = connect(scheme)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE ws_tx_" + scheme + " (id INTEGER PRIMARY KEY, name TEXT)");
            }

            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO ws_tx_" + scheme + " VALUES (1, 'committed')");
            }
            connection.commit();

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO ws_tx_" + scheme + " VALUES (2, 'rolled back')");
            }
            connection.rollback();
            connection.setAutoCommit(true);

            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "SELECT id, name FROM ws_tx_" + scheme + " ORDER BY id")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
                assertThat(resultSet.getString(2)).isEqualTo("committed");
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("schemes")
    void failingStatementMapsToSqlExceptionAndLeavesTheConnectionUsable(String scheme) throws SQLException {
        try (Connection connection = connect(scheme); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM ws_missing_table"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("no such table");

            try (ResultSet resultSet = statement.executeQuery("SELECT 40 + 2")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(42);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("schemes")
    void metadataAndBatchWorkOverWebSocket(String scheme) throws SQLException {
        try (Connection connection = connect(scheme)) {
            DatabaseMetaData metaData = connection.getMetaData();
            assertThat(metaData.getDatabaseProductName()).isEqualTo("SQLite (libSQL)");
            assertThat(metaData.getURL()).isEqualTo(url(scheme));

            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE ws_batch_" + scheme + " (id INTEGER PRIMARY KEY, name TEXT)");
                statement.addBatch("INSERT INTO ws_batch_" + scheme + " VALUES (1, 'a')");
                statement.addBatch("INSERT INTO ws_batch_" + scheme + " VALUES (2, 'b')");
                assertThat(statement.executeBatch()).containsExactly(1, 1);
            }

            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "SELECT COUNT(*) FROM ws_batch_" + scheme)) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void urlParserAcceptsWebSocketSchemes() throws SQLException {
        assertThat(LibsqlUrlParser.acceptsUrl("jdbc:libsql:ws://localhost:8080")).isTrue();
        assertThat(LibsqlUrlParser.acceptsUrl("jdbc:libsql:wss://example.com/dev")).isTrue();
        assertThat(LibsqlUrlParser.acceptsUrl("jdbc:sqlite:ws://localhost:8080")).isTrue();

        LibsqlClientConfig config = LibsqlUrlParser.parse(
                "jdbc:libsql:ws://localhost:8080/dev/tenant?tls=true", new Properties());
        assertThat(config.url()).isEqualTo("wss://localhost:8080/dev/tenant");
    }
}
