package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.client.LibsqlClient;
import io.github.magnusp.libsql.client.LibsqlClientConfig;
import io.github.magnusp.libsql.client.LibsqlException;
import io.github.magnusp.libsql.hrana.codec.Batch;
import io.github.magnusp.libsql.hrana.codec.BatchCond;
import io.github.magnusp.libsql.hrana.codec.BatchResult;
import io.github.magnusp.libsql.hrana.codec.BatchStep;
import io.github.magnusp.libsql.hrana.codec.HranaError;
import io.github.magnusp.libsql.hrana.codec.PipelineReqBody;
import io.github.magnusp.libsql.hrana.codec.PipelineRespBody;
import io.github.magnusp.libsql.hrana.codec.Stmt;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.StreamRequest;
import io.github.magnusp.libsql.hrana.codec.StreamResponse;
import io.github.magnusp.libsql.hrana.codec.StreamResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlConnectionTest {

    private static final LibsqlClientConfig CONFIG = new LibsqlClientConfig("http://localhost:1", null);
    private static final String BATON = "baton-1";
    private static final String SERVER_ERROR = "transaction end failed on server";
    private static final String TRANSPORT_ERROR = "Connection reset by peer";
    private static final String BATCH_FAILURE = "UNIQUE constraint failed: sample.id";
    private static final String UNIQUE_VIOLATION = "UNIQUE constraint failed: sample.id";

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
    void metadataReportsTheJdbcUrlUsedToConnect() throws SQLException {
        LibsqlConnection connection = new LibsqlConnection(
                CONFIG,
                new ScriptedClient((request, index) -> successResponse(request)),
                "jdbc:sqlite:http://localhost:1");

        assertThat(connection.getMetaData().getURL())
                .isEqualTo("jdbc:sqlite:http://localhost:1");
    }

    @Test
    void disablingAutoCommitDefersTheBeginUntilTheNextStatement() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);

        connection.setAutoCommit(false);

        assertThat(client.requests).isEmpty();

        connection.executeStatement(new Stmt("SELECT 1"));

        assertThat(client.executedStatements()).containsExactly("BEGIN", "SELECT 1");
        assertThat(client.requests).hasSize(2);
        assertThat(client.requests.get(0).baton()).isNull();
        assertThat(client.requests.get(1).baton()).isEqualTo(BATON);
    }

    @ParameterizedTest
    @EnumSource(value = LibsqlConnection.TransactionMode.class, names = {"IMMEDIATE", "EXCLUSIVE"})
    void theDeferredBeginUsesTheConfiguredTransactionMode(LibsqlConnection.TransactionMode mode)
            throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setTransactionMode(mode);

        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("SELECT 1"));

        String expected = switch (mode) {
            case IMMEDIATE -> "BEGIN IMMEDIATE";
            case EXCLUSIVE -> "BEGIN EXCLUSIVE";
            case DEFERRED -> "BEGIN";
        };
        assertThat(client.executedStatements()).containsExactly(expected, "SELECT 1");
    }

    @Test
    void commitDoesNotReBeginAndTheNextStatementUsesTheConfiguredMode() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setTransactionMode("immediate");
        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("SELECT 1"));

        connection.commit();
        connection.executeStatement(new Stmt("SELECT 2"));

        assertThat(client.executedStatements())
                .containsExactly("BEGIN IMMEDIATE", "SELECT 1", "COMMIT", "BEGIN IMMEDIATE", "SELECT 2");
        assertThat(connection.getTransactionMode())
                .isEqualTo(LibsqlConnection.TransactionMode.IMMEDIATE);
    }

    @Test
    void commitClosesTheStreamAndTheNextStatementBeginsOnANewStream() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("SELECT 1"));

        connection.commit();

        assertThat(client.executedStatements()).containsExactly("BEGIN", "SELECT 1", "COMMIT");
        assertThat(client.requests).hasSize(4);
        assertThat(client.requests.get(2).baton()).isNotNull();
        assertThat(client.requests.get(3).requests()).containsExactly(StreamRequest.Close.INSTANCE);

        connection.executeStatement(new Stmt("SELECT 2"));

        assertThat(client.requests.get(4).baton()).isNull();
        assertThat(client.requests.get(4).requests())
                .as("BEGIN must be checked on its own before the statement can run")
                .hasSize(1);
        assertThat(client.requests.get(5).baton()).isNotNull();
    }

    @Test
    void rollbackClosesTheStreamAndTheNextStatementBeginsOnANewStream() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("SELECT 1"));

        connection.rollback();

        assertThat(client.executedStatements()).containsExactly("BEGIN", "SELECT 1", "ROLLBACK");
        assertThat(client.requests).hasSize(4);
        assertThat(client.requests.get(3).requests()).containsExactly(StreamRequest.Close.INSTANCE);

        connection.executeStatement(new Stmt("SELECT 2"));

        assertThat(client.requests.get(4).baton()).isNull();
    }

    @Test
    void commitWithNoWorkSendsNoRequestAndTheNextStatementCostsTwoRoundTrips() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);

        connection.commit();

        assertThat(client.requests).as("commit of a pending begin sends nothing").isEmpty();
        assertThat(connection.getAutoCommit()).isFalse();

        connection.executeStatement(new Stmt("SELECT 1"));

        assertThat(client.executedStatements()).containsExactly("BEGIN", "SELECT 1");
        assertThat(client.requests).hasSize(2);
    }

    @Test
    void rollbackWithNoWorkSendsNoRequestAndTheNextStatementStillWorks() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);

        connection.rollback();

        assertThat(client.requests).isEmpty();
        assertThat(connection.getAutoCommit()).isFalse();

        connection.executeStatement(new Stmt("SELECT 1"));

        assertThat(client.executedStatements()).containsExactly("BEGIN", "SELECT 1");
    }

    @Test
    void aFailedBeginLeavesTheBeginPendingAndNoStatementRuns() throws SQLException {
        AtomicInteger begins = new AtomicInteger();
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if ("BEGIN".equals(executedSql(request)) && begins.incrementAndGet() == 1) {
                return errorResponse(new HranaError(SERVER_ERROR, "SQLITE_ERROR"));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);

        assertThatThrownBy(() -> connection.executeStatement(new Stmt("SELECT 1")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(SERVER_ERROR);

        assertThat(client.requests).hasSize(2);
        assertThat(client.requests.get(0).requests())
                .as("BEGIN is not pipelined with the statement, so the statement cannot run if it fails")
                .hasSize(1);
        assertThat(client.executedStatements()).containsExactly("BEGIN");

        connection.executeStatement(new Stmt("SELECT 1"));

        assertThat(client.executedStatements()).containsExactly("BEGIN", "BEGIN", "SELECT 1");
        assertThat(client.requests.get(1).requests())
                .as("the failed begin releases its stream")
                .containsExactly(StreamRequest.Close.INSTANCE);
        assertThat(client.requests.get(2).baton()).isNull();
    }

    @Test
    void aFailedBeginPinsTheLazyBeginStateUntilTheRetrySucceeds() throws SQLException {
        AtomicInteger begins = new AtomicInteger();
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if ("BEGIN".equals(executedSql(request)) && begins.incrementAndGet() == 1) {
                return errorResponse(new HranaError(SERVER_ERROR, "SQLITE_ERROR"));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);

        assertThatThrownBy(() -> connection.executeStatement(new Stmt("SELECT 1")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(SERVER_ERROR);

        assertThat(connection.getAutoCommit())
                .as("the JDBC mode is unchanged by a failed begin")
                .isFalse();
        assertThat(client.requests.get(1).requests())
                .as("the failed begin closes its stream, so no phantom transaction stays open")
                .containsExactly(StreamRequest.Close.INSTANCE);

        connection.commit();
        connection.rollback();

        assertThat(client.requests)
                .as("commit and rollback with no work send nothing after a failed begin")
                .hasSize(2);
        assertThat(connection.getAutoCommit()).isFalse();

        connection.executeStatement(new Stmt("SELECT 1"));

        assertThat(client.executedStatements()).containsExactly("BEGIN", "BEGIN", "SELECT 1");
        assertThat(client.requests.get(2).baton())
                .as("the retry begins the transaction on a new stream")
                .isNull();
        assertThat(client.requests.get(3).baton())
                .as("the statement runs on the stream the retried begin opened")
                .isNotNull();
    }

    @Test
    void enablingAutoCommitAfterWorkCommitsAndClosesTheOpenTransaction() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("INSERT INTO sample VALUES (1)"));

        connection.setAutoCommit(true);

        assertThat(client.executedStatements())
                .containsExactly("BEGIN", "INSERT INTO sample VALUES (1)", "COMMIT");
        assertThat(client.requests.get(2).baton()).isNotNull();
        assertThat(client.requests).hasSize(4);
        assertThat(client.requests.get(3).requests()).containsExactly(StreamRequest.Close.INSTANCE);
        assertThat(connection.getAutoCommit()).isTrue();
    }

    @Test
    void enablingAutoCommitFromAPendingBeginSendsNoRequest() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);

        connection.setAutoCommit(true);

        assertThat(client.requests).isEmpty();
        assertThat(connection.getAutoCommit()).isTrue();
    }

    @Test
    void closeFromAPendingBeginSendsNoRequest() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);

        connection.close();

        assertThat(client.requests).isEmpty();
        assertThat(connection.isClosed()).isTrue();
    }

    @Test
    void closeAfterWorkClosesTheOpenStream() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> successResponse(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);
        connection.executeStatement(new Stmt("SELECT 1"));

        connection.close();

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
                "ROLLBACK",
                "BEGIN",
                "INSERT INTO sample VALUES (2)");
        assertThat(client.requests.get(3).baton())
                .as("rollback after a failed commit retries on the same stream")
                .isNotNull();
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

    @ParameterizedTest(name = "fail at step {0}, autoCommit={1}")
    @CsvSource({"0,true", "1,true", "2,true", "0,false", "1,false", "2,false"})
    void batchStopsAtFirstFailureAndThrowsWithTheCountsOfStepsThatRan(int failAt, boolean autoCommit)
            throws SQLException {
        List<String> executed = new ArrayList<>();
        ScriptedClient client = new ScriptedClient(
                (request, index) -> batchResponse(request, failAt, executed));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(autoCommit);
        List<String> sql = List.of(
                "INSERT INTO sample VALUES (1)",
                "INSERT INTO sample VALUES (2)",
                "INSERT INTO sample VALUES (3)");

        assertThatThrownBy(() -> connection.executeBatch(sql.stream().map(Stmt::new).toList()))
                .isInstanceOf(BatchUpdateException.class)
                .hasMessageContaining(BATCH_FAILURE)
                .satisfies(thrown -> assertThat(((BatchUpdateException) thrown).getUpdateCounts())
                        .containsExactly(countsOfStepsThatRan(failAt)));

        assertThat(executed).containsExactlyElementsOf(sql.subList(0, failAt));

        List<BatchStep> steps = batchSteps(client);
        assertThat(steps).hasSize(sql.size());
        assertThat(steps.get(0).condition()).isNull();
        for (int i = 1; i < steps.size(); i++) {
            assertThat(steps.get(i).condition())
                    .as("step %d runs only when step %d succeeded", i, i - 1)
                    .isEqualTo(new BatchCond.StepOk(i - 1));
        }
    }

    @Test
    void successfulBatchReturnsOneCountPerStep() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> batchResponse(request, -1, new ArrayList<>()));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);

        int[] counts = connection.executeBatch(List.of(
                new Stmt("INSERT INTO sample VALUES (1)"),
                new Stmt("INSERT INTO sample VALUES (2)"),
                new Stmt("INSERT INTO sample VALUES (3)")));

        assertThat(counts).containsExactly(1, 1, 1);
    }

    @Test
    void batchStepWithNeitherResultNorErrorIsAProtocolError() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> responseWithMissingBatchStep(request));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);

        assertThatThrownBy(() -> connection.executeBatch(List.of(
                new Stmt("INSERT INTO sample VALUES (1)"),
                new Stmt("INSERT INTO sample VALUES (2)"))))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(BatchUpdateException.class)
                .hasMessageContaining("neither a result nor an error");
    }

    @ParameterizedTest(name = "autoCommit={0}")
    @ValueSource(booleans = {true, false})
    void uniqueViolationMapsToTheSameExceptionTypeAndSqlStateInBothModes(boolean autoCommit)
            throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if ("INSERT INTO sample VALUES (1)".equals(executedSql(request))) {
                return errorResponse(new HranaError(UNIQUE_VIOLATION, "SQLITE_CONSTRAINT_UNIQUE"));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(autoCommit);

        assertThatThrownBy(() -> connection.executeStatement(new Stmt("INSERT INTO sample VALUES (1)")))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .hasMessageContaining("UNIQUE constraint failed")
                .satisfies(thrown -> {
                    SQLException mapped = (SQLException) thrown;
                    assertThat(mapped.getSQLState()).isEqualTo("23000");
                    assertThat(mapped.getErrorCode()).isEqualTo(19);
                });
    }

    @Test
    void failureToBeginTheTransactionIsMapped() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if ("BEGIN".equals(executedSql(request))) {
                return errorResponse(new HranaError("database is locked", "SQLITE_BUSY"));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);

        assertThatThrownBy(() -> connection.executeStatement(new Stmt("SELECT 1")))
                .isInstanceOf(SQLTransientConnectionException.class)
                .hasMessageContaining("database is locked")
                .satisfies(thrown -> assertThat(((SQLException) thrown).getSQLState()).isEqualTo("08001"));
    }

    @Test
    void aStatementErrorInsideTheTransactionIsMapped() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if ("INSERT INTO sample VALUES (2)".equals(executedSql(request))) {
                return errorResponse(new HranaError(UNIQUE_VIOLATION, "SQLITE_CONSTRAINT_UNIQUE"));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = connectionInTransaction(client);

        assertThatThrownBy(() -> connection.executeStatement(new Stmt("INSERT INTO sample VALUES (2)")))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .satisfies(thrown -> assertThat(((SQLException) thrown).getSQLState()).isEqualTo("23000"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"COMMIT", "ROLLBACK"})
    void aTransactionEndErrorIsMapped(String command) throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if (command.equals(executedSql(request))) {
                return errorResponse(new HranaError(UNIQUE_VIOLATION, "SQLITE_CONSTRAINT_UNIQUE"));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = connectionInTransaction(client);

        assertThatThrownBy(() -> endTransaction(connection, command))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .satisfies(thrown -> assertThat(((SQLException) thrown).getSQLState()).isEqualTo("23000"));
    }

    @Test
    void aBatchStepErrorKeepsTheMappedExceptionAsTheCause() throws SQLException {
        ScriptedClient client = new ScriptedClient(
                (request, index) -> batchResponse(request, 1, new ArrayList<>()));
        LibsqlConnection connection = connectionInTransaction(client);

        assertThatThrownBy(() -> connection.executeBatch(List.of(
                new Stmt("INSERT INTO sample VALUES (1)"),
                new Stmt("INSERT INTO sample VALUES (2)"),
                new Stmt("INSERT INTO sample VALUES (3)"))))
                .isInstanceOf(BatchUpdateException.class)
                .satisfies(thrown -> {
                    BatchUpdateException batch = (BatchUpdateException) thrown;
                    assertThat(batch.getSQLState()).isEqualTo("23000");
                    assertThat(batch.getErrorCode()).isEqualTo(19);
                    assertThat(batch.getCause())
                            .isInstanceOf(SQLIntegrityConstraintViolationException.class);
                });
    }

    @Test
    void aConstraintErrorWithoutACodeFallsBackToMessageCategorization() throws SQLException {
        ScriptedClient client = new ScriptedClient((request, index) -> {
            if ("INSERT INTO sample VALUES (2)".equals(executedSql(request))) {
                return errorResponse(new HranaError(UNIQUE_VIOLATION, null));
            }
            return successResponse(request);
        });
        LibsqlConnection connection = connectionInTransaction(client);

        assertThatThrownBy(() -> connection.executeStatement(new Stmt("INSERT INTO sample VALUES (2)")))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .satisfies(thrown -> assertThat(((SQLException) thrown).getSQLState()).isEqualTo("23000"));
    }

    private static int[] countsOfStepsThatRan(int failAt) {
        int[] counts = new int[failAt];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = 1;
        }
        return counts;
    }

    private static List<BatchStep> batchSteps(ScriptedClient client) {
        return client.requests.stream()
                .flatMap(request -> request.requests().stream())
                .filter(StreamRequest.BatchReq.class::isInstance)
                .map(StreamRequest.BatchReq.class::cast)
                .findFirst()
                .map(batchReq -> batchReq.batch().steps())
                .orElseThrow();
    }

    private static PipelineRespBody batchResponse(PipelineReqBody request, int failAt, List<String> executed) {
        List<StreamResult> results = new ArrayList<>();
        for (StreamRequest streamRequest : request.requests()) {
            if (streamRequest instanceof StreamRequest.BatchReq batchReq) {
                results.add(new StreamResult.Ok(new StreamResponse.BatchResp(
                        runBatch(batchReq.batch().steps(), failAt, executed))));
            } else if (streamRequest instanceof StreamRequest.Close) {
                results.add(new StreamResult.Ok(StreamResponse.Close.INSTANCE));
            } else {
                results.add(new StreamResult.Ok(
                        new StreamResponse.Execute(new StmtResult(List.of(), List.of(), 0, null))));
            }
        }
        return new PipelineRespBody(null, null, results);
    }

    private static BatchResult runBatch(List<BatchStep> steps, int failAt, List<String> executed) {
        Map<Integer, StmtResult> stepResults = new HashMap<>();
        Map<Integer, HranaError> stepErrors = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            if (!conditionHolds(steps.get(i).condition(), stepResults)) {
                continue;
            }
            if (i == failAt) {
                stepErrors.put(i, new HranaError(BATCH_FAILURE, "SQLITE_CONSTRAINT"));
            } else {
                executed.add(steps.get(i).stmt().sql());
                stepResults.put(i, new StmtResult(List.of(), List.of(), 1, null));
            }
        }
        return new BatchResult(stepResults, stepErrors);
    }

    private static boolean conditionHolds(BatchCond condition, Map<Integer, StmtResult> stepResults) {
        if (condition == null) {
            return true;
        }
        if (condition instanceof BatchCond.StepOk ok) {
            return stepResults.containsKey(ok.step());
        }
        throw new AssertionError("Unexpected batch condition: " + condition);
    }

    private static PipelineRespBody responseWithMissingBatchStep(PipelineReqBody request) {
        List<StreamResult> results = new ArrayList<>();
        for (StreamRequest streamRequest : request.requests()) {
            if (streamRequest instanceof StreamRequest.BatchReq) {
                Map<Integer, StmtResult> stepResults = new HashMap<>();
                stepResults.put(0, new StmtResult(List.of(), List.of(), 1, null));
                results.add(new StreamResult.Ok(new StreamResponse.BatchResp(
                        new BatchResult(stepResults, Map.of()))));
            } else {
                results.add(new StreamResult.Ok(StreamResponse.Close.INSTANCE));
            }
        }
        return new PipelineRespBody(null, null, results);
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

        /**
         * Models the HTTP transport's observable behaviour: a one-shot request opens and closes its
         * own stream, so it never touches the interactive baton.
         */
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
