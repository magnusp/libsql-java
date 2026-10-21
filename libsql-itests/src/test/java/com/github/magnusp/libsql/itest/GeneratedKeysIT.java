package com.github.magnusp.libsql.itest;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

public class GeneratedKeysIT extends SqldContainerBase {

    private String getDriverJdbcUrl() {
        return "jdbc:libsql:" + getHttpUrl();
    }

    @Test
    void statementAndPreparedStatementInsertsReturnTheirGeneratedKeys() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getDriverJdbcUrl())) {
            try (Statement s = conn.createStatement()) {
                s.execute("create table generated_keys_statement (id integer primary key autoincrement, v text)");
            }

            try (Statement s = conn.createStatement()) {
                s.executeUpdate("insert into generated_keys_statement (v) values ('a')");
                try (ResultSet keys = s.getGeneratedKeys()) {
                    assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
                    assertThat(keys.next()).isTrue();
                    assertThat(keys.getLong(1)).isEqualTo(1L);
                    assertThat(keys.next()).isFalse();
                }

                s.executeUpdate("update generated_keys_statement set v = 'b'");
                try (ResultSet keys = s.getGeneratedKeys()) {
                    assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
                    assertThat(keys.next()).isFalse();
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("insert into generated_keys_statement (v) values (?)")) {
                ps.setString(1, "c");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
                    assertThat(keys.next()).isTrue();
                    assertThat(keys.getLong(1)).isEqualTo(2L);
                    assertThat(keys.next()).isFalse();
                }
            }

            assertThat(conn.getMetaData().supportsGetGeneratedKeys()).isTrue();
        }
    }

    @Test
    void generatedKeysComeFromTheInsertingSession() throws SQLException {
        try (Connection first = DriverManager.getConnection(getDriverJdbcUrl());
                Connection second = DriverManager.getConnection(getDriverJdbcUrl())) {
            try (Statement s = first.createStatement()) {
                s.execute("create table generated_keys_session (id integer primary key autoincrement, v text)");
            }

            try (Statement firstStatement = first.createStatement();
                    Statement secondStatement = second.createStatement()) {
                firstStatement.executeUpdate("insert into generated_keys_session (v) values ('a')");
                secondStatement.executeUpdate("insert into generated_keys_session (v) values ('b')");

                try (ResultSet keys = secondStatement.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    assertThat(keys.getLong(1)).isEqualTo(2L);
                }
                try (ResultSet keys = firstStatement.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    assertThat(keys.getLong(1)).isEqualTo(1L);
                }
            }
        }
    }

    @Test
    void insertInsideTransactionReturnsGeneratedKey() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getDriverJdbcUrl())) {
            try (Statement s = conn.createStatement()) {
                s.execute("create table generated_keys_tx (id integer primary key autoincrement, v text)");
            }

            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("insert into generated_keys_tx (v) values (?)")) {
                ps.setString(1, "a");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    assertThat(keys.getLong(1)).isEqualTo(1L);
                }
            }
            conn.commit();

            try (Statement s = conn.createStatement();
                    ResultSet rs = s.executeQuery("select count(*) from generated_keys_tx")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1L);
            }
        }
    }
}
