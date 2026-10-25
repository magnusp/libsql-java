package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JDBC 4.3 Appendix B compliance profile for the unrepresentable types.
 *
 * <p>This is the policy claim, stated once: {@code ARRAY}, {@code STRUCT}, {@code REF}, {@code
 * JAVA_OBJECT}, and {@code DISTINCT} are N/A and throw {@link SQLFeatureNotSupportedException}.
 * Every other declared type the driver resolves is supported. An expression column has no declared
 * type and keeps the permissive storage fallback.
 */
class Jdbc4AppendixBProfileTest {

    private static final Set<String> NA_TYPES =
            Set.of("ARRAY", "STRUCT", "REF", "JAVA_OBJECT", "DISTINCT");

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "TINYINT",
            "SMALLINT",
            "INTEGER",
            "BIGINT",
            "REAL",
            "FLOAT",
            "DOUBLE",
            "NUMERIC",
            "DECIMAL",
            "BIT",
            "BOOLEAN",
            "CHAR",
            "VARCHAR",
            "LONGVARCHAR",
            "NCHAR",
            "NVARCHAR",
            "LONGNVARCHAR",
            "BINARY",
            "VARBINARY",
            "LONGVARBINARY",
            "DATE",
            "TIME",
            "TIMESTAMP",
            "TIME_WITH_TIMEZONE",
            "TIMESTAMP_WITH_TIMEZONE",
            "CLOB",
            "NCLOB",
            "SQLXML",
            "ROWID",
            "BLOB",
            "DATALINK");

    @Test
    void profileListsExactlyTheUnsupportedTypes() {
        assertThat(JdbcTypeMapping.unsupportedTypeNames())
                .as("N/A JDBC types")
                .containsExactlyInAnyOrderElementsOf(NA_TYPES);
    }

    @Test
    void profileListsExactlyTheSupportedTypes() {
        assertThat(JdbcTypeMapping.supportedTypeNames())
                .as("supported JDBC types")
                .containsExactlyInAnyOrderElementsOf(SUPPORTED_TYPES);
    }

    @Test
    void everyUnsupportedDeclaredTypeThrowsFeatureNotSupported() throws SQLException {
        for (String type : NA_TYPES) {
            LibsqlResultSet resultSet = oneRow(type, Value.of("42"));
            assertThat(resultSet.next()).isTrue();
            assertThatThrownBy(() -> resultSet.getObject(1))
                    .as("getObject on declared %s", type)
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> resultSet.getArray(1))
                    .as("getArray on declared %s", type)
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> resultSet.getRef(1))
                    .as("getRef on declared %s", type)
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
        }
    }

    @Test
    void expressionFallbackStaysPermissive() throws Exception {
        LibsqlResultSet resultSet = oneRow(null, Value.of("not-a-number"));
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getInt(1))
                .as("an expression column keeps SQLite's zero coercion")
                .isZero();
    }

    private static LibsqlResultSet oneRow(String declaredType, Value value) {
        return new LibsqlResultSet(
                new LibsqlStatement(null),
                new StmtResult(
                        List.of(new Col("c", declaredType)),
                        List.of(new Row(List.of(value))),
                        0,
                        null));
    }
}
