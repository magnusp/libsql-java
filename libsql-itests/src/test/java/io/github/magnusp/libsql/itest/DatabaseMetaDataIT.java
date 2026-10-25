package io.github.magnusp.libsql.itest;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class DatabaseMetaDataIT extends SqldContainerBase {

    private String getDriverJdbcUrl() {
        return "jdbc:libsql:" + getHttpUrl();
    }

    @Test
    void keyAndTypeMetadataRoundTripThroughHrana() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getDriverJdbcUrl())) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE meta_parent (id INTEGER, label TEXT, "
                        + "CONSTRAINT pk_meta_parent PRIMARY KEY (id))");
                s.execute("CREATE TABLE meta_child (id INTEGER, parent_id INTEGER, "
                        + "CONSTRAINT fk_meta_child_parent FOREIGN KEY (parent_id) "
                        + "REFERENCES meta_parent (id) ON DELETE CASCADE)");
            }
            DatabaseMetaData metadata = conn.getMetaData();

            try (ResultSet rs = metadata.getPrimaryKeys(null, null, "meta_parent")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualTo("id");
                assertThat(rs.getInt("KEY_SEQ")).isEqualTo(1);
                assertThat(rs.getString("PK_NAME")).isEqualTo("pk_meta_parent");
                assertThat(rs.next()).isFalse();
            }

            try (ResultSet rs = metadata.getImportedKeys(null, null, "meta_child")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("PKTABLE_NAME")).isEqualTo("meta_parent");
                assertThat(rs.getString("PKCOLUMN_NAME")).isEqualTo("id");
                assertThat(rs.getString("FKTABLE_NAME")).isEqualTo("meta_child");
                assertThat(rs.getString("FKCOLUMN_NAME")).isEqualTo("parent_id");
                assertThat(rs.getInt("KEY_SEQ")).isEqualTo(1);
                assertThat(rs.getInt("DELETE_RULE")).isEqualTo(DatabaseMetaData.importedKeyCascade);
                assertThat(rs.getString("FK_NAME")).isEqualTo("fk_meta_child_parent");
                assertThat(rs.getString("PK_NAME")).isEqualTo("pk_meta_parent");
                assertThat(rs.next()).isFalse();
            }

            try (ResultSet rs = metadata.getExportedKeys(null, null, "meta_parent")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("PKTABLE_NAME")).isEqualTo("meta_parent");
                assertThat(rs.getString("FKTABLE_NAME")).isEqualTo("meta_child");
                assertThat(rs.getString("FK_NAME")).isEqualTo("fk_meta_child_parent");
                assertThat(rs.getString("PK_NAME")).isEqualTo("pk_meta_parent");
                assertThat(rs.next()).isFalse();
            }

            try (ResultSet rs = metadata.getCrossReference(null, null, "meta_parent", null, null, "meta_child")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("PKTABLE_NAME")).isEqualTo("meta_parent");
                assertThat(rs.getString("FKTABLE_NAME")).isEqualTo("meta_child");
                assertThat(rs.next()).isFalse();
            }

            try (ResultSet rs = metadata.getTypeInfo()) {
                List<String> typeNames = new ArrayList<>();
                while (rs.next()) {
                    typeNames.add(rs.getString("TYPE_NAME"));
                }
                assertThat(typeNames).containsExactly("NULL", "INTEGER", "REAL", "TEXT", "BLOB");
            }
        }
    }

    @Test
    void keyMetadataWithoutForeignKeysIsEmptyAndWellFormed() throws SQLException {
        try (Connection conn = DriverManager.getConnection(getDriverJdbcUrl())) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE meta_plain (id INTEGER PRIMARY KEY, value TEXT)");
            }
            DatabaseMetaData metadata = conn.getMetaData();

            try (ResultSet rs = metadata.getImportedKeys(null, null, "meta_plain")) {
                assertThat(rs.getMetaData().getColumnCount()).isEqualTo(14);
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = metadata.getExportedKeys(null, null, "meta_plain")) {
                assertThat(rs.getMetaData().getColumnCount()).isEqualTo(14);
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = metadata.getCrossReference(null, null, "meta_plain", null, null, "meta_plain")) {
                assertThat(rs.getMetaData().getColumnCount()).isEqualTo(14);
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = metadata.getPrimaryKeys(null, null, "meta_plain")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("PK_NAME")).isNull();
                assertThat(rs.next()).isFalse();
            }
        }
    }
}
