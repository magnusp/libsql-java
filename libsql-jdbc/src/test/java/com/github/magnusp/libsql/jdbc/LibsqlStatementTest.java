package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlStatementTest {

    private static final StmtResult THREE_ROWS = new StmtResult(
            List.of(new Col("value", null)),
            List.of(
                    new Row(List.of(Value.of(1L))),
                    new Row(List.of(Value.of(2L))),
                    new Row(List.of(Value.of(3L)))),
            0,
            null);

    @Test
    void setMaxRowsTruncatesResultRowsAndGetMaxRowsReadsItBack() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        statement.setMaxRows(2);

        assertThat(statement.getMaxRows()).isEqualTo(2);
        try (ResultSet resultSet = statement.executeQuery("select value from t")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(1L);
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(2L);
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void setMaxRowsZeroRemovesTheLimit() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));
        statement.setMaxRows(1);

        statement.setMaxRows(0);

        assertThat(statement.getMaxRows()).isZero();
        int rows = 0;
        try (ResultSet resultSet = statement.executeQuery("select value from t")) {
            while (resultSet.next()) {
                rows++;
            }
        }
        assertThat(rows).isEqualTo(3);
    }

    @Test
    void setMaxRowsRejectsANegativeLimit() {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        assertThatThrownBy(() -> statement.setMaxRows(-1))
                .isInstanceOf(SQLException.class)
                .hasMessage("max row count must be >= 0");
    }

    @Test
    void setQueryTimeoutAcceptsNoLimitAndRejectsAPositiveTimeout() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        statement.setQueryTimeout(0);

        assertThat(statement.getQueryTimeout()).isZero();
        assertThatThrownBy(() -> statement.setQueryTimeout(1))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("Query timeouts are not supported");
        assertThatThrownBy(() -> statement.setQueryTimeout(-1))
                .isInstanceOf(SQLException.class)
                .hasMessage("query timeout must be >= 0");
    }

    @Test
    void setMaxFieldSizeAcceptsNoLimitAndRejectsAPositiveLimit() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        statement.setMaxFieldSize(0);

        assertThat(statement.getMaxFieldSize()).isZero();
        assertThatThrownBy(() -> statement.setMaxFieldSize(8))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("Column size limits are not supported");
        assertThatThrownBy(() -> statement.setMaxFieldSize(-1))
                .isInstanceOf(SQLException.class)
                .hasMessage("max field size must be >= 0");
    }

    @Test
    void escapeProcessingStartsEnabledAndRejectsEscapeSequences() {
        RecordingConnection connection = new RecordingConnection(THREE_ROWS);
        LibsqlStatement statement = new LibsqlStatement(connection);

        assertThatThrownBy(() -> statement.executeQuery("select {fn abs(-1)}"))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("JDBC escape sequences are not supported");
        assertThat(connection.executedSql).isEmpty();
    }

    @Test
    void setEscapeProcessingDisabledSendsEscapeLookingSqlVerbatim() throws SQLException {
        RecordingConnection connection = new RecordingConnection(THREE_ROWS);
        LibsqlStatement statement = new LibsqlStatement(connection);

        statement.setEscapeProcessing(false);
        statement.executeQuery("select '{fn abs(-1)}' as text");

        assertThat(connection.executedSql)
                .containsExactly("select '{fn abs(-1)}' as text");
    }

    @Test
    void escapeProcessingIgnoresBracesInsideStringLiteralsAndComments() throws SQLException {
        RecordingConnection connection = new RecordingConnection(THREE_ROWS);
        LibsqlStatement statement = new LibsqlStatement(connection);

        statement.executeQuery("select '{\"a\": 1}' as doc -- {fn abs(-1)}");

        assertThat(connection.executedSql).hasSize(1);
    }

    @Test
    void setCursorNameIsNotSupported() {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(THREE_ROWS));

        assertThatThrownBy(() -> statement.setCursorName("c1"))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessage("Cursor names are not supported");
    }

    private static final class RecordingConnection extends LibsqlConnection {
        private final StmtResult result;
        private final List<String> executedSql = new ArrayList<>();

        private RecordingConnection(StmtResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            executedSql.add(stmt.sql());
            return result;
        }
    }
}
