package com.github.magnusp.libsql.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XerialFileUrlPersistenceTest {

    @TempDir Path tempDir;

    @Test
    void fileUrlKeepsDataAfterEveryConnectionClosesAndTheSamePathReopens() throws SQLException {
        Path database = tempDir.resolve("persist.db").toAbsolutePath().normalize();
        String url = "jdbc:sqlite:" + database;

        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE persisted (id INTEGER PRIMARY KEY, value TEXT)");
            statement.executeUpdate("INSERT INTO persisted (value) VALUES ('kept')");
        }

        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SELECT value FROM persisted WHERE id = 1")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("kept");
        }
    }

    @Test
    void relativeAndAbsolutePathsAddressTheSameFile() throws SQLException {
        Path database = tempDir.resolve("mapped.db").toAbsolutePath().normalize();
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        assumeTrue(
                workingDirectory.getRoot().equals(database.getRoot()),
                "The working directory and test directory must share a filesystem root");
        Path relative = workingDirectory.relativize(database);
        assumeTrue(!relative.isAbsolute(), "The test database must be addressable relatively");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE mapped (id INTEGER PRIMARY KEY, value TEXT)");
            statement.executeUpdate("INSERT INTO mapped (value) VALUES ('same file')");
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + relative);
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SELECT value FROM mapped WHERE id = 1")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("same file");
        }
    }
}
