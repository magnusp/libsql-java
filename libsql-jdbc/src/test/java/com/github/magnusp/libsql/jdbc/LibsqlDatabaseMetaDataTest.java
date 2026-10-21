package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LibsqlDatabaseMetaDataTest {

    @Test
    void getTablesClosesResultSetWhenStatementCloses() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new RecordingConnection(emptyResult()));

        ResultSet resultSet = metadata.getTables(null, null, null, null);
        LibsqlStatement statement = (LibsqlStatement) resultSet.getStatement();
        statement.close();

        assertThat(resultSet.isClosed()).isTrue();
    }

    @Test
    void getTablesResultSetClosesItsStatement() throws SQLException {
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(new RecordingConnection(emptyResult()));

        ResultSet resultSet = metadata.getTables(null, null, null, null);
        LibsqlStatement statement = (LibsqlStatement) resultSet.getStatement();
        resultSet.close();

        assertThat(statement.isClosed()).isTrue();
    }

    @Test
    void escapesSingleQuoteInTableNamePattern() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());
        LibsqlDatabaseMetaData metadata = new LibsqlDatabaseMetaData(connection);

        ResultSet resultSet = metadata.getTables(null, null, "O'Brien", null);
        resultSet.close();

        assertThat(connection.lastSql).contains("TABLE_NAME LIKE 'O''Brien'");
        assertThat(connection.lastSql).doesNotContain("TABLE_NAME LIKE 'O'Brien'");
    }

    @Test
    void getTablesWithNullTypesReturnsOnlyTablesViewsAndSystemAndTemporaryRows() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, null, null).close();

        assertThat(connection.lastSql)
                .contains("'sqlite_schema' AS NAME")
                .contains("'SYSTEM TABLE' AS TYPE")
                .contains("'GLOBAL TEMPORARY' AS TYPE")
                .contains("FROM sqlite_temp_master")
                .contains("UPPER(TYPE) IN ('TABLE', 'VIEW')")
                .contains("TABLE_NAME LIKE '%'")
                .doesNotContain("TABLE_TYPE IN")
                .doesNotContain("INDEX")
                .doesNotContain("TRIGGER");
    }

    @Test
    void getTablesWithEmptyTypesBehavesLikeNullTypes() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, null, new String[0]).close();

        assertThat(connection.lastSql)
                .contains("'SYSTEM TABLE' AS TYPE")
                .contains("'GLOBAL TEMPORARY' AS TYPE")
                .contains("UPPER(TYPE) IN ('TABLE', 'VIEW')")
                .doesNotContain("TABLE_TYPE IN");
    }

    @Test
    void getTablesWithTableTypeFiltersToTableRows() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, null, new String[] {"TABLE"}).close();

        assertThat(connection.lastSql)
                .contains("UPPER(TYPE) IN ('TABLE', 'VIEW')")
                .contains("AND TABLE_TYPE IN ('TABLE')");
    }

    @Test
    void getTablesWithViewTypeFiltersToViewRows() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, null, new String[] {"VIEW"}).close();

        assertThat(connection.lastSql).contains("AND TABLE_TYPE IN ('VIEW')");
    }

    @Test
    void getTablesWithMultipleTypesUppercasesThem() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, null, new String[] {"table", "View"}).close();

        assertThat(connection.lastSql).contains("AND TABLE_TYPE IN ('TABLE','VIEW')");
    }

    @Test
    void getTablesWithEmptyTableNamePatternMatchesEverything() throws SQLException {
        RecordingConnection connection = new RecordingConnection(emptyResult());

        new LibsqlDatabaseMetaData(connection).getTables(null, null, "", null).close();

        assertThat(connection.lastSql).contains("TABLE_NAME LIKE '%'");
    }

    private static StmtResult emptyResult() {
        return new StmtResult(List.of(new Col("TABLE_NAME", "TEXT")), List.of(), 0, null);
    }

    private static final class RecordingConnection extends LibsqlConnection {
        private final StmtResult result;
        private String lastSql;

        private RecordingConnection(StmtResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) throws SQLException {
            lastSql = stmt.sql();
            return result;
        }
    }
}
