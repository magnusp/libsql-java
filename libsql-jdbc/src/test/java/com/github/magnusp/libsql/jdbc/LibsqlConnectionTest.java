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
import org.junit.jupiter.params.ParameterizedTest;
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

    @ParameterizedTest
    @ValueSource(strings = {"COMMIT", "ROLLBACK"})
    void serverErrorIsPropagatedAndTransactionStateIsKept(String command) throws SQLException {
        AtomicInteger attempts = new AtomicInteger();
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if (command.equals(executedSql(request)) && attempts.incrementAndGet() == 1) {
                return new PipelineRespBody(request.baton(), null,
                        List.of(new StreamResult.Error(new HranaError(SERVER_ERROR, "SQLITE_ERROR"))));
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
                request -> assertThat(request.baton()).isEqualTo(BATON));
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

    private static String executedSql(PipelineReqBody request) {
        if (request.requests().isEmpty()
                || !(request.requests().get(0) instanceof StreamRequest.Execute execute)) {
            return null;
        }
        return execute.stmt().sql();
    }

    private static PipelineRespBody successResponse(PipelineReqBody request) {
        StreamResponse response = request.requests().get(0) instanceof StreamRequest.Close
                ? StreamResponse.Close.INSTANCE
                : new StreamResponse.Execute(new StmtResult(List.of(), List.of(), 0, null));
        return new PipelineRespBody(BATON, null, List.of(new StreamResult.Ok(response)));
    }

    private static final class ScriptedClient extends LibsqlHttpClient {

        @FunctionalInterface
        private interface Handler {
            PipelineRespBody handle(PipelineReqBody request, int index);
        }

        private final Handler handler;
        private final List<PipelineReqBody> requests = new ArrayList<>();

        private ScriptedClient(Handler handler) {
            super(CONFIG);
            this.handler = handler;
        }

        @Override
        public PipelineRespBody sendPipeline(PipelineReqBody request, URI targetUri) {
            requests.add(request);
            return handler.handle(request, requests.size() - 1);
        }
    }
}
