package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.client.LibsqlClientConfig;
import com.github.magnusp.libsql.client.LibsqlException;
import com.github.magnusp.libsql.client.LibsqlHttpClient;
import com.github.magnusp.libsql.hrana.codec.HranaError;
import com.github.magnusp.libsql.hrana.codec.PipelineReqBody;
import com.github.magnusp.libsql.hrana.codec.PipelineRespBody;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.StreamRequest;
import com.github.magnusp.libsql.hrana.codec.StreamResponse;
import com.github.magnusp.libsql.hrana.codec.StreamResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlConnectionTest {

    private static final LibsqlClientConfig CONFIG = new LibsqlClientConfig("http://localhost:1", null);
    private static final String BATON = "baton-1";
    private static final String SERVER_ERROR = "transaction end failed on server";
    private static final String TRANSPORT_ERROR = "Connection reset by peer";

    @Test
    void getMetaDataIsCachedPerConnection() throws SQLException {
        LibsqlConnection connection = new LibsqlConnection(
                CONFIG, new ScriptedClient((request, index) -> successResponse(request)));
        LibsqlConnection other = new LibsqlConnection(
                CONFIG, new ScriptedClient((request, index) -> successResponse(request)));

        assertThat(connection.getMetaData()).isSameAs(connection.getMetaData());
        assertThat(other.getMetaData()).isNotSameAs(connection.getMetaData());
    }

    @Test
    void disablingAutoCommitBeginsTransactionImmediately() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);

        connection.setAutoCommit(false);

        assertThat(client.executedStatements()).containsExactly("BEGIN");
        assertThat(client.requests.get(0).baton()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = LibsqlConnection.TransactionMode.class, names = {"IMMEDIATE", "EXCLUSIVE"})
    void disablingAutoCommitUsesConfiguredTransactionMode(LibsqlConnection.TransactionMode mode)
            throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setTransactionMode(mode);

        connection.setAutoCommit(false);

        String expected = switch (mode) {
            case IMMEDIATE -> "BEGIN IMMEDIATE";
            case EXCLUSIVE -> "BEGIN EXCLUSIVE";
            case DEFERRED -> "BEGIN";
        };
        assertThat(client.executedStatements()).containsExactly(expected);
    }

    @Test
    void commitReBeginsInConfiguredTransactionMode() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setTransactionMode("immediate");
        connection.setAutoCommit(false);

        connection.commit();

        assertThat(client.executedStatements())
                .containsExactly("BEGIN IMMEDIATE", "COMMIT", "BEGIN IMMEDIATE");
        assertThat(connection.getTransactionMode())
                .isEqualTo(LibsqlConnection.TransactionMode.IMMEDIATE);
    }

    @Test
    void commitBeginsNextTransactionOnTheSameStream() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);

        connection.commit();

        assertThat(client.executedStatements()).containsExactly("BEGIN", "COMMIT", "BEGIN");
        assertThat(client.requests).hasSize(2);
        assertThat(client.requests.get(1).baton()).isEqualTo(BATON);
    }

    @Test
    void rollbackBeginsNextTransactionOnTheSameStream() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);

        connection.rollback();

        assertThat(client.executedStatements()).containsExactly("BEGIN", "ROLLBACK", "BEGIN");
        assertThat(client.requests).hasSize(2);
        assertThat(client.requests.get(1).baton()).isEqualTo(BATON);
    }

    @Test
    void enablingAutoCommitCommitsAndClosesTheOpenTransaction() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);

        connection.setAutoCommit(true);

        assertThat(client.executedStatements()).containsExactly("BEGIN", "COMMIT");
        assertThat(client.requests.get(1).baton()).isEqualTo(BATON);
        assertThat(client.requests).hasSize(3);
        assertThat(client.requests.get(2).requests()).containsExactly(StreamRequest.Close.INSTANCE);
    }

    @Test
    void failedCommitCanBeRolledBackAndTheConnectionStaysUsable() throws SQLException {
        AtomicInteger commits = new AtomicInteger();
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if ("COMMIT".equals(executedSql(request)) && commits.incrementAndGet() == 1) {
                return errorResponse(new HranaError(SERVER_ERROR, "SQLITE_CONSTRAINT_FOREIGNKEY"));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = connectionInTransaction(client);

        assertThatThrownBy(connection::commit)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(SERVER_ERROR);

        connection.rollback();
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (2)"));

        assertThat(client.executedStatements()).containsExactly(
                "BEGIN",
                "INSERT INTO sample VALUES (1)",
                "COMMIT",
                "BEGIN",
                "ROLLBACK",
                "BEGIN",
                "INSERT INTO sample VALUES (2)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMIT", "ROLLBACK"})
    void serverErrorIsPropagatedAndTransactionStateIsKept(String command) throws SQLException {
        AtomicInteger attempts = new AtomicInteger();
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if (command.equals(executedSql(request)) && attempts.incrementAndGet() == 1) {
                return errorResponse(new HranaError(SERVER_ERROR, "SQLITE_ERROR"));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = connectionInTransaction(client);

        assertThatThrownBy(() -> endTransaction(connection, command))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(SERVER_ERROR);

        endTransaction(connection, command);

        assertCommandRetriedOnSameStream(client, command);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMIT", "ROLLBACK"})
    void transportErrorIsPropagatedAndTransactionStateIsKept(String command) throws SQLException {
        AtomicInteger attempts = new AtomicInteger();
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if (command.equals(executedSql(request)) && attempts.incrementAndGet() == 1) {
                throw new LibsqlException("Failed to execute pipeline request: " + TRANSPORT_ERROR, null, 0);
            }
            return successResponse(request);
        });
        LibsqlConnection connection = connectionInTransaction(client);

        assertThatThrownBy(() -> endTransaction(connection, command))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(TRANSPORT_ERROR);

        endTransaction(connection, command);

        assertCommandRetriedOnSameStream(client, command);
    }

    private static void assertCommandRetriedOnSameStream(ScriptedClient client, String command) {
        List<PipelineReqBody> commandRequests = client.requests.stream()
                .filter(request -> command.equals(executedSql(request)))
                .toList();
        assertThat(commandRequests).hasSize(2);
        assertThat(commandRequests).allSatisfy(
                request -> assertThat(request.baton()).isNotNull());
    }

    private static LibsqlConnection connectionInTransaction(ScriptedClient client) throws SQLException {
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (1)"));
        return connection;
    }

    private static void endTransaction(LibsqlConnection connection, String command) throws SQLException {
        if ("COMMIT".equals(command)) {
            connection.commit();
        } else {
            connection.rollback();
        }
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

    private static final class ScriptedClient extends LibsqlHttpClient {

        @FunctionalInterface
        private interface Handler {
            PipelineRespBody handle(PipelineReqBody request, int index);
        }

        private final Handler handler;
        private final List<PipelineReqBody> requests = new ArrayList<>();
        private String issuedBaton = null;
        private int batonCount = 0;

        private ScriptedClient(Handler handler) {
            super(CONFIG);
            this.handler = handler;
        }

        @Override
        public PipelineRespBody sendPipeline(PipelineReqBody request, URI targetUri) {
            requests.add(request);
            if (request.baton() != null && !request.baton().equals(issuedBaton)) {
                throw new LibsqlException("stale baton " + request.baton(), "STALE_BATON", 400);
            }
            PipelineRespBody response = handler.handle(request, requests.size() - 1);
            issuedBaton = "baton-" + (++batonCount);
            return new PipelineRespBody(issuedBaton, response.baseUrl(), response.results());
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
