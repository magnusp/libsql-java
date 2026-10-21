package org.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetFactory;
import javax.sql.rowset.RowSetProvider;
import org.junit.jupiter.api.Test;

public class CachedRowSetTest {

    @Test
    public void gh_224() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table person (id INTEGER, name VARCHAR(50))");
                statement.execute("insert into person values(1, 'leo')");
            }
            RowSetFactory factory = RowSetProvider.newFactory();
            try (CachedRowSet crs = factory.createCachedRowSet()) {
                crs.setCommand("select * from person");
                crs.execute(connection);

                assertThat(crs.next()).isTrue();
                assertThat(crs.getInt("id")).isEqualTo(1);
                assertThat(crs.getString("name")).isEqualTo("leo");
                assertThat(crs.next()).isFalse();
            }
        }
    }
}
