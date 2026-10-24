package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.client.LibsqlClient;
import com.github.magnusp.libsql.client.LibsqlClientConfig;
import com.github.magnusp.libsql.client.LibsqlException;
import com.github.magnusp.libsql.hrana.codec.Batch;
import com.github.magnusp.libsql.hrana.codec.BatchResult;
import com.github.magnusp.libsql.hrana.codec.HranaError;
import com.github.magnusp.libsql.hrana.codec.PipelineReqBody;
import com.github.magnusp.libsql.hrana.codec.PipelineRespBody;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.StreamRequest;
import com.github.magnusp.libsql.hrana.codec.StreamResponse;
import com.github.magnusp.libsql.hrana.codec.StreamResult;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlSavepointTest {

    private static final LibsqlClientConfig CONFIG = new LibsqlClientConfig("http://localhost:1", null);

    @Test
    void databaseMetaDataReportsSavepointSupport() throws SQLException {
        LibsqlConnection connection = newConnection(successClient());

        assertThat(connection.getMetaData().supportsSavepoints()).isTrue();
    }

    @Test
    void unnamedSavepointInsideAnExplicitTransactionIsNestedInTheLazyBegin() throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);
        connection.setAutoCommit(false);

        Savepoint savepoint = connection.setSavepoint();

        assertThat(savepoint.getSavepointId()).isEqualTo(1);
        assertThatThrownBy(savepoint::getSavepointName).isInstanceOf(SQLException.class);
        assertThat(client.executedStatements())
                .containsExactly("BEGIN", "SAVEPOINT \"SQLITE_SAVEPOINT_1\"");
        assertThat(client.requests.get(1).baton()).isNotNull();
    }

    @Test
    void namedSavepointUsesTheGivenNameAndConsumesAnId() throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);
        connection.setAutoCommit(false);

        Savepoint named = connection.setSavepoint("num7");
        Savepoint unnamed = connection.setSavepoint();

        assertThat(named.getSavepointName()).isEqualTo("num7");
        assertThatThrownBy(named::getSavepointId).isInstanceOf(SQLException.class);
        assertThat(unnamed.getSavepointId()).isEqualTo(2);
        assertThat(client.executedStatements())
                .containsExactly("BEGIN", "SAVEPOINT \"num7\"", "SAVEPOINT \"SQLITE_SAVEPOINT_2\"");
    }

    @Test
    void savepointNamesAreQuotedAsIdentifiers() throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);
        connection.setAutoCommit(false);

        connection.setSavepoint("weird \" name");

        assertThat(client.executedStatements())
                .containsExactly("BEGIN", "SAVEPOINT \"weird \"\" name\"");
    }

    @Test
    void setSavepointRejectsNullAndBlankNamesWithoutSendingAnything() {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);

        assertThatThrownBy(() -> connection.setSavepoint(null)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> connection.setSavepoint("  ")).isInstanceOf(SQLException.class);
        assertThat(client.requests).isEmpty();
    }

    @Test
    void savepointInAutoCommitStartsATransactionWithoutABegin() throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);

        connection.setSavepoint();
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (1)"));
        connection.commit();

        assertThat(connection.getAutoCommit()).isFalse();
        assertThat(client.executedStatements())
                .containsExactly(
                        "SAVEPOINT \"SQLITE_SAVEPOINT_1\"",
                        "INSERT INTO sample VALUES (1)",
                        "COMMIT");
        assertThat(client.requests).hasSize(4);
        assertThat(client.requests.get(3).requests()).containsExactly(StreamRequest.Close.INSTANCE);
    }

    @Test
    void rollbackToSavepointKeepsTheSavepointAndTheStream() throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);
        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (1)"));
        Savepoint savepoint = connection.setSavepoint("sp1");
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (2)"));

        connection.rollback(savepoint);
        connection.rollback(savepoint);

        assertThat(client.executedStatements())
                .containsExactly(
                        "BEGIN",
                        "INSERT INTO sample VALUES (1)",
                        "SAVEPOINT \"sp1\"",
                        "INSERT INTO sample VALUES (2)",
                        "ROLLBACK TO SAVEPOINT \"sp1\"",
                        "ROLLBACK TO SAVEPOINT \"sp1\"");
        assertThat(client.requests.stream()
                        .flatMap(request -> request.requests().stream())
                        .filter(StreamRequest.Close.class::isInstance))
                .isEmpty();
    }

    @Test
    void releasingANestedSavepointKeepsTheTransactionStream() throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);
        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (1)"));
        Savepoint outer = connection.setSavepoint("outer_sp");
        Savepoint inner = connection.setSavepoint("inner_sp");

        connection.releaseSavepoint(inner);
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (2)"));

        assertThat(client.executedStatements())
                .containsExactly(
                        "BEGIN",
                        "INSERT INTO sample VALUES (1)",
                        "SAVEPOINT \"outer_sp\"",
                        "SAVEPOINT \"inner_sp\"",
                        "RELEASE SAVEPOINT \"inner_sp\"",
                        "INSERT INTO sample VALUES (2)");
        assertThat(client.requests.stream()
                        .flatMap(request -> request.requests().stream())
                        .filter(StreamRequest.Close.class::isInstance))
                .isEmpty();
        assertThat(outer.getSavepointName()).isEqualTo("outer_sp");
    }

    @Test
    void releasingTheSavepointThatStartedTheTransactionCommitsAndClosesTheStream()
            throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);
        Savepoint outer = connection.setSavepoint("outer_sp");
        Savepoint inner = connection.setSavepoint("inner_sp");

        connection.releaseSavepoint(inner);
        connection.releaseSavepoint(outer);
        connection.executeStatement(new Stmt("SELECT 1"));

        assertThat(connection.getAutoCommit()).isFalse();
        assertThat(client.executedStatements())
                .containsExactly(
                        "SAVEPOINT \"outer_sp\"",
                        "SAVEPOINT \"inner_sp\"",
                        "RELEASE SAVEPOINT \"inner_sp\"",
                        "RELEASE SAVEPOINT \"outer_sp\"",
                        "BEGIN",
                        "SELECT 1");
        assertThat(client.requests.stream()
                        .flatMap(request -> request.requests().stream())
                        .filter(StreamRequest.Close.class::isInstance))
                .hasSize(1);
    }

    @Test
    void releasingTheOutermostSavepointInsideAnExplicitBeginKeepsTheTransaction()
            throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);
        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (1)"));
        Savepoint savepoint = connection.setSavepoint("sp1");

        connection.releaseSavepoint(savepoint);
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (2)"));

        assertThat(client.executedStatements())
                .containsExactly(
                        "BEGIN",
                        "INSERT INTO sample VALUES (1)",
                        "SAVEPOINT \"sp1\"",
                        "RELEASE SAVEPOINT \"sp1\"",
                        "INSERT INTO sample VALUES (2)");
    }

    @Test
    void releasingTheLaterOfTwoSavepointsWithTheSameNameKeepsTheEarlierOneActive()
            throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);
        Savepoint first = connection.setSavepoint("dup");
        Savepoint second = connection.setSavepoint("dup");

        connection.releaseSavepoint(second);
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (1)"));
        connection.rollback(first);
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (2)"));

        assertThat(client.executedStatements())
                .containsExactly(
                        "SAVEPOINT \"dup\"",
                        "SAVEPOINT \"dup\"",
                        "RELEASE SAVEPOINT \"dup\"",
                        "INSERT INTO sample VALUES (1)",
                        "ROLLBACK TO SAVEPOINT \"dup\"",
                        "INSERT INTO sample VALUES (2)");
        assertThat(client.requests.stream()
                        .flatMap(request -> request.requests().stream())
                        .filter(StreamRequest.Close.class::isInstance))
                .isEmpty();
    }

    @Test
    void releasingTheOnlySavepointOfAnAutoCommitTransactionLeavesCommitWithNothingToSend()
            throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);
        Savepoint savepoint = connection.setSavepoint("only");

        connection.releaseSavepoint(savepoint);
        connection.commit();

        assertThat(connection.getAutoCommit()).isFalse();
        assertThat(client.executedStatements())
                .containsExactly("SAVEPOINT \"only\"", "RELEASE SAVEPOINT \"only\"");
        assertThat(client.requests.stream()
                        .flatMap(request -> request.requests().stream())
                        .filter(StreamRequest.Close.class::isInstance))
                .hasSize(1);
    }

    @Test
    void rollbackAndReleaseThrowInAutoCommitModeWithoutSendingAnything() throws SQLException {
        ScriptedClient client = successClient();
        LibsqlConnection connection = newConnection(client);
        connection.setAutoCommit(false);
        Savepoint savepoint = connection.setSavepoint("sp1");
        connection.setAutoCommit(true);
        int requestsBefore = client.requests.size();

        assertThatThrownBy(() -> connection.rollback(savepoint))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("autoCommit");
        assertThatThrownBy(() -> connection.releaseSavepoint(savepoint))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("autoCommit");
        assertThat(client.requests).hasSize(requestsBefore);
    }

    @Test
    void rollbackAndReleaseRejectANullSavepoint() throws SQLException {
        LibsqlConnection connection = newConnection(successClient());
        connection.setAutoCommit(false);

        assertThatThrownBy(() -> connection.rollback(null)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> connection.releaseSavepoint(null)).isInstanceOf(SQLException.class);
    }

    @Test
    void savepointMethodsThrowOnAClosedConnection() throws SQLException {
        LibsqlConnection connection = newConnection(successClient());
        connection.setAutoCommit(false);
        Savepoint savepoint = connection.setSavepoint("sp1");
        connection.close();

        assertThatThrownBy(connection::setSavepoint).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> connection.setSavepoint("sp2")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> connection.rollback(savepoint)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> connection.releaseSavepoint(savepoint))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void aServerErrorOnRollbackToSavepointIsMappedAndTheTransactionStaysOpen() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> {
            String sql = executedSql(request);
            if (sql != null && sql.startsWith("ROLLBACK TO SAVEPOINT")) {
                return errorResponse(new HranaError("no such savepoint: sp1", "SQLITE_ERROR"));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = newConnection(client);
        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (1)"));
        Savepoint savepoint = connection.setSavepoint("sp1");

        assertThatThrownBy(() -> connection.rollback(savepoint))
                .isInstanceOf(SQLSyntaxErrorException.class)
                .hasMessageContaining("no such savepoint")
                .satisfies(thrown -> assertThat(((SQLException) thrown).getSQLState()).isEqualTo("42000"));

        connection.executeStatement(new Stmt("SELECT 1"));

        assertThat(client.executedStatements())
                .containsExactly(
                        "BEGIN",
                        "INSERT INTO sample VALUES (1)",
                        "SAVEPOINT \"sp1\"",
                        "ROLLBACK TO SAVEPOINT \"sp1\"",
                        "SELECT 1");
    }

    @Test
    void aFailedImplicitSavepointLeavesAutoCommitOnAndClosesTheFreshStream() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> {
            String sql = executedSql(request);
            if (sql != null && sql.startsWith("SAVEPOINT")) {
                return errorResponse(new HranaError("savepoint failed", "SQLITE_ERROR"));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = newConnection(client);

        assertThatThrownBy(connection::setSavepoint)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("savepoint failed");
        assertThat(connection.getAutoCommit()).isTrue();
        assertThat(client.executedStatements()).containsExactly("SAVEPOINT \"SQLITE_SAVEPOINT_1\"");
        assertThat(client.requests).hasSize(2);
        assertThat(client.requests.get(1).requests()).containsExactly(StreamRequest.Close.INSTANCE);
    }

    private static LibsqlConnection newConnection(ScriptedClient client) {
        return new LibsqlConnection(CONFIG, client);
    }

    private static ScriptedClient successClient() {
        return new ScriptedClient((request, index) -> successResponse(request));
    }

    private static PipelineRespBody successResponse(PipelineReqBody request) {
        List<StreamResult> results = new ArrayList<>();
        for (StreamRequest streamRequest : request.requests()) {
            if (streamRequest instanceof StreamRequest.Close) {
                results.add(new StreamResult.Ok(StreamResponse.Close.INSTANCE));
            } else {
                results.add(new StreamResult.Ok(
                        new StreamResponse.Execute(new StmtResult(List.of(), List.of(), 0, null))));
            }
        }
        return new PipelineRespBody(null, null, results);
    }

    private static PipelineRespBody errorResponse(HranaError error) {
        return new PipelineRespBody(null, null, List.of(new StreamResult.Error(error)));
    }

    private static String executedSql(PipelineReqBody request) {
        if (request.requests().isEmpty()
                || !(request.requests().get(0) instanceof StreamRequest.Execute execute)) {
            return null;
        }
        return execute.stmt().sql();
    }

    private static final class ScriptedClient implements LibsqlClient {

        @FunctionalInterface
        private interface Handler {
            PipelineRespBody handle(PipelineReqBody request, int index);
        }

        private final Handler handler;
        private final List<PipelineReqBody> requests = new ArrayList<>();
        private String issuedBaton = null;
        private int batonCount = 0;

        private ScriptedClient(Handler handler) {
            this.handler = handler;
        }

        @Override
        public StmtResult executeOneShot(Stmt stmt) {
            PipelineReqBody request = new PipelineReqBody(null, List.of(
                    new StreamRequest.Execute(stmt),
                    StreamRequest.Close.INSTANCE));
            requests.add(request);
            StreamResult first = handler.handle(request, requests.size() - 1).results().get(0);
            return switch (first) {
                case StreamResult.Ok ok -> {
                    if (ok.response() instanceof StreamResponse.Execute exec) {
                        yield exec.result();
                    }
                    throw new LibsqlException("Unexpected stream response: " + ok.response(), null, 200);
                }
                case StreamResult.Error err -> throw new LibsqlException(err.error());
            };
        }

        @Override
        public BatchResult executeBatchOneShot(Batch batch) {
            PipelineReqBody request = new PipelineReqBody(null, List.of(
                    new StreamRequest.BatchReq(batch),
                    StreamRequest.Close.INSTANCE));
            requests.add(request);
            return batchResult(handler.handle(request, requests.size() - 1).results().get(0));
        }

        @Override
        public StmtResult executeOnStream(Stmt stmt) {
            StreamResult result = roundTrip(new PipelineReqBody(
                    issuedBaton, List.of(new StreamRequest.Execute(stmt))));
            return switch (result) {
                case StreamResult.Ok ok -> {
                    if (ok.response() instanceof StreamResponse.Execute exec) {
                        yield exec.result();
                    }
                    throw new LibsqlException("Unexpected stream response: " + ok.response(), null, 200);
                }
                case StreamResult.Error err -> throw new LibsqlException(err.error());
            };
        }

        @Override
        public BatchResult executeBatchOnStream(Batch batch) {
            StreamResult result = roundTrip(new PipelineReqBody(
                    issuedBaton, List.of(new StreamRequest.BatchReq(batch))));
            return batchResult(result);
        }

        @Override
        public boolean hasOpenStream() {
            return issuedBaton != null;
        }

        @Override
        public void closeStream() {
            if (issuedBaton == null) {
                return;
            }
            PipelineReqBody request = new PipelineReqBody(
                    issuedBaton, List.of(StreamRequest.Close.INSTANCE));
            requests.add(request);
            handler.handle(request, requests.size() - 1);
            issuedBaton = null;
        }

        @Override
        public void close() {
            // No connection-level state to release.
        }

        private StreamResult roundTrip(PipelineReqBody request) {
            requests.add(request);
            if (request.baton() != null && !request.baton().equals(issuedBaton)) {
                throw new LibsqlException("stale baton " + request.baton(), "STALE_BATON", 400);
            }
            PipelineRespBody response = handler.handle(request, requests.size() - 1);
            issuedBaton = "baton-" + (++batonCount);
            return response.results().get(0);
        }

        private static BatchResult batchResult(StreamResult result) {
            return switch (result) {
                case StreamResult.Ok ok -> {
                    if (ok.response() instanceof StreamResponse.BatchResp batchResp) {
                        yield batchResp.result();
                    }
                    throw new LibsqlException("Unexpected stream response: " + ok.response(), null, 200);
                }
                case StreamResult.Error err -> throw new LibsqlException(err.error());
            };
        }

        private List<String> executedStatements() {
            return requests.stream()
                    .flatMap(request -> request.requests().stream())
                    .filter(StreamRequest.Execute.class::isInstance)
                    .map(StreamRequest.Execute.class::cast)
                    .map(execute -> execute.stmt().sql())
                    .toList();
        }
    }
}
