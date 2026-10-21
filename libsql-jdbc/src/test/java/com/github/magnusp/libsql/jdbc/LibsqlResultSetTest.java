package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LibsqlResultSetTest {

    private static final StmtResult INTEGER_ROWS = new StmtResult(
            List.of(new Col("id", "INTEGER")),
            List.of(new Row(List.of(Value.of(42L))), new Row(List.of(Value.of(5_000_000_000L)))),
            0,
            null);

    @Test
    void getObjectReturnsIntegerInRangeAndLongOutside() throws SQLException {
        LibsqlResultSet resultSet = new LibsqlResultSet(new LibsqlStatement(null), INTEGER_ROWS);

        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getObject(1)).isInstanceOf(Integer.class).isEqualTo(42);
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getObject(1)).isInstanceOf(Long.class).isEqualTo(5_000_000_000L);
    }

    @Test
    void cachesResultSetMetadata() throws SQLException {
        LibsqlResultSet resultSet = new LibsqlResultSet(new LibsqlStatement(null), INTEGER_ROWS);

        assertThat(resultSet.getMetaData()).isSameAs(resultSet.getMetaData());
    }

    @Test
    void closeDoesNotCloseStatementByDefault() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);
        LibsqlResultSet resultSet = new LibsqlResultSet(statement, INTEGER_ROWS);

        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isFalse();
    }

    @Test
    void closeClosesStatementWhenRequested() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);
        LibsqlResultSet resultSet = new LibsqlResultSet(statement, INTEGER_ROWS, null, true);

        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isTrue();
    }

    @Test
    void closeClosesStatementWithCloseOnCompletion() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);
        statement.closeOnCompletion();
        LibsqlResultSet resultSet = new LibsqlResultSet(statement, INTEGER_ROWS);

        resultSet.close();
        resultSet.close();

        assertThat(resultSet.isClosed()).isTrue();
        assertThat(statement.isClosed()).isTrue();
    }

    @Test
    void statementCloseClosesCurrentResultSet() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);
        LibsqlResultSet resultSet = new LibsqlResultSet(statement, INTEGER_ROWS);
        statement.currentResultSet = resultSet;

        statement.close();

        assertThat(resultSet.isClosed()).isTrue();
    }
}
