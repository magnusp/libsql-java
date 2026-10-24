package com.github.magnusp.libsql.itest;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the JDBC driver over Hrana 3 Protobuf on a WebSocket ({@code hrana3-protobuf}
 * subprotocol). The observable JDBC behavior must match the JSON transport.
 */
public class WebSocketProtobufTransportIT extends SqldContainerBase {

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:libsql:" + getWebSocketUrl() + "?encoding=protobuf");
    }

    @Test
    void selectReturnsTypedValuesIncluding64BitIntegerAndBlob() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT 9007199254740993 AS big, -9223372036854775808 AS min,"
                                + " 1.5 AS f, 'hello' AS t, x'DEADBEEF' AS b, NULL AS n")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong("big")).isEqualTo(9007199254740993L);
            assertThat(resultSet.getLong("min")).isEqualTo(Long.MIN_VALUE);
            assertThat(resultSet.getDouble("f")).isEqualTo(1.5);
            assertThat(resultSet.getString("t")).isEqualTo("hello");
            assertThat(resultSet.getBytes("b")).containsExactly(0xDE, 0xAD, 0xBE, 0xEF);
            assertThat(resultSet.getObject("n")).isNull();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void transactionCommitsAndRollsBackOnOneStream() throws SQLException {
        try (Connection connection = connect()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE ws_proto_tx (id INTEGER PRIMARY KEY, name TEXT)");
            }

            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO ws_proto_tx VALUES (1, 'committed')");
            }
            connection.commit();

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO ws_proto_tx VALUES (2, 'rolled back')");
            }
            connection.rollback();
            connection.setAutoCommit(true);

            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "SELECT id, name FROM ws_proto_tx ORDER BY id")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
                assertThat(resultSet.getString(2)).isEqualTo("committed");
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    @Test
    void failingStatementMapsToSqlExceptionAndLeavesTheConnectionUsable() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM ws_proto_missing_table"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("no such table");

            try (ResultSet resultSet = statement.executeQuery("SELECT 40 + 2")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(42);
            }
        }
    }
}
