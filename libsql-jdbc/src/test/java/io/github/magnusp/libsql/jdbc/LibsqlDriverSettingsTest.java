package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import org.junit.jupiter.api.Test;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlDriverSettingsTest {

    private static final StmtResult EMPTY =
            new StmtResult(List.of(new Col("value", null)), List.of(), 0, null);

    @Test
    void typeMapReadAndWriteAreRejected() throws SQLException {
        LibsqlConnection connection = new LibsqlConnection(null, null);

        assertThatThrownBy(connection::getTypeMap)
                .isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(() -> connection.setTypeMap(Map.of("x", String.class)))
                .isInstanceOf(SQLFeatureNotSupportedException.class);
    }

    @Test
    void connectionHoldabilityIsCloseCursorsAtCommit() throws SQLException {
        LibsqlConnection connection = new LibsqlConnection(null, null);

        assertThat(connection.getHoldability()).isEqualTo(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        connection.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertThat(connection.getHoldability()).isEqualTo(ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Test
    void connectionRejectsHoldCursorsOverCommit() throws SQLException {
        LibsqlConnection connection = new LibsqlConnection(null, null);

        assertThatThrownBy(() -> connection.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CLOSE_CURSORS_AT_COMMIT");
    }

    @Test
    void statementFactoriesRejectHoldCursorsOverCommit() throws SQLException {
        LibsqlConnection connection = new LibsqlConnection(null, null);

        assertThatThrownBy(() -> connection.createStatement(
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY,
                        ResultSet.HOLD_CURSORS_OVER_COMMIT))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CLOSE_CURSORS_AT_COMMIT");
        assertThatThrownBy(() -> connection.prepareStatement(
                        "select 1",
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY,
                        ResultSet.HOLD_CURSORS_OVER_COMMIT))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CLOSE_CURSORS_AT_COMMIT");
    }

    @Test
    void metadataHoldabilityAgreesWithConnection() throws SQLException {
        LibsqlConnection connection = new LibsqlConnection(null, null);
        DatabaseMetaData metadata = connection.getMetaData();

        assertThat(metadata.getResultSetHoldability())
                .isEqualTo(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertThat(metadata.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT))
                .isTrue();
        assertThat(metadata.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT))
                .isFalse();
    }

    @Test
    void statementAndResultSetReportCloseCursorsAtCommit() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);

        assertThat(statement.getResultSetHoldability())
                .isEqualTo(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertThat(new LibsqlResultSet(statement, EMPTY).getHoldability())
                .isEqualTo(ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Test
    void statementFetchSizeRoundTripsAndRejectsNegativeValues() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(null);

        assertThat(statement.getFetchSize()).isZero();
        statement.setFetchSize(10);
        assertThat(statement.getFetchSize()).isEqualTo(10);
        assertThatThrownBy(() -> statement.setFetchSize(-1))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void resultSetFetchSizeRoundTripsAndRejectsNegativeValues() throws SQLException {
        LibsqlResultSet resultSet = new LibsqlResultSet(new LibsqlStatement(null), EMPTY);

        assertThat(resultSet.getFetchSize()).isZero();
        resultSet.setFetchSize(10);
        assertThat(resultSet.getFetchSize()).isEqualTo(10);
        assertThatThrownBy(() -> resultSet.setFetchSize(-1))
                .isInstanceOf(SQLException.class);
    }
}
