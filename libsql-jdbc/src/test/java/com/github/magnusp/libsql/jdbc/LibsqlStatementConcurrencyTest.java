package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.client.LibsqlClient;
import com.github.magnusp.libsql.client.LibsqlClientConfig;
import com.github.magnusp.libsql.client.LibsqlException;
import com.github.magnusp.libsql.hrana.codec.Batch;
import com.github.magnusp.libsql.hrana.codec.BatchResult;
import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.PipelineReqBody;
import com.github.magnusp.libsql.hrana.codec.PipelineRespBody;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.StreamRequest;
import com.github.magnusp.libsql.hrana.codec.StreamResponse;
import com.github.magnusp.libsql.hrana.codec.StreamResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlStatementConcurrencyTest {

    private static final StmtResult ROWS = new StmtResult(
            List.of(new Col("value", null)),
            List.of(new Row(List.of(Value.of(1L)))),
            0,
            null);
    private static final LibsqlClientConfig CONFIG =
            new LibsqlClientConfig("http://localhost:1", null);

    @Test
    void plainExecuteQueryDoesNotAttachAnOpenResultSetWhenCloseWinsTheRace() throws Exception {
        Pause pause = new Pause();
        PausingStatement statement =
                new PausingStatement(new RecordingConnection(ROWS), pause);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResultSet> execute =
                    executor.submit(() -> statement.executeQuery("select value from t"));

            assertThat(pause.awaitReached()).isTrue();
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            joinAndCheck(closeInThread(statement, closeFailure), closeFailure);
            pause.resume();

            ResultSet resultSet = execute.get(5, TimeUnit.SECONDS);
            assertThat(statement.isClosed()).isTrue();
            assertThat(resultSet.isClosed()).isTrue();
        } finally {
            pause.resume();
            executor.shutdownNow();
        }
    }

    @Test
    void preparedExecuteQueryDoesNotAttachAnOpenResultSetWhenCloseWinsTheRace() throws Exception {
        Pause pause = new Pause();
        PausingPreparedStatement statement = new PausingPreparedStatement(
                new RecordingConnection(ROWS), "select value from t", pause);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResultSet> execute = executor.submit(() -> statement.executeQuery());

            assertThat(pause.awaitReached()).isTrue();
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            joinAndCheck(closeInThread(statement, closeFailure), closeFailure);
            pause.resume();

            ResultSet resultSet = execute.get(5, TimeUnit.SECONDS);
            assertThat(statement.isClosed()).isTrue();
            assertThat(resultSet.isClosed()).isTrue();
        } finally {
            pause.resume();
            executor.shutdownNow();
        }
    }

    @Test
    void executeQueryOnAClosedStatementReturnsAClosedResultSet() throws SQLException {
        LibsqlStatement statement = new LibsqlStatement(new RecordingConnection(ROWS));
        statement.close();

        ResultSet resultSet = statement.executeQuery("select value from t");

        assertThat(statement.isClosed()).isTrue();
        assertThat(resultSet.isClosed()).isTrue();
    }

    /**
     * Two threads share one non-auto-commit connection. Hrana consumes a baton once and allows one
     * request per stream at a time, so the two lazily started transactions must not both send
     * {@code BEGIN} from the same empty stream state.
     */
    @Test
    void twoThreadsOnOneNonAutoCommitConnectionDoNotReuseABaton() throws Exception {
        ProtocolClient client = new ProtocolClient(new CyclicBarrier(2));
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);
        LibsqlStatement first = new LibsqlStatement(connection);
        LibsqlStatement second = new LibsqlStatement(connection);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstWork = executor.submit(() -> executeRepeatedly(first, 20));
            Future<?> secondWork = executor.submit(() -> executeRepeatedly(second, 20));

            assertThat(failureOf(firstWork, secondWork)).isNull();
            assertThat(client.violations()).as("protocol violations").isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A commit from another thread must not land between the lazy transaction begin and the
     * statement sent on the new baton.
     */
    @Test
    void commitDoesNotInterleaveWithTheLazyTransactionBegin() throws Exception {
        ProtocolClient client = new ProtocolClient(null);
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);
        LibsqlStatement statement = new LibsqlStatement(connection);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> work = executor.submit(() -> statement.execute("SELECT 1"));
            assertThat(client.awaitStatementInFlight()).isTrue();

            AtomicReference<Throwable> commitFailure = new AtomicReference<>();
            joinAndCheck(commitInThread(connection, commitFailure), commitFailure);

            Throwable workFailure = failureOf(work);
            assertThat(client.violations()).as("protocol violations").isEmpty();
            assertThat(workFailure).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A close from another thread must not race the statement's round trip, and it must leave no
     * server-side stream open.
     */
    @Test
    void closeDoesNotInterleaveWithExecuteAndLeavesNoStreamOpen() throws Exception {
        ProtocolClient client = new ProtocolClient(null);
        LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        connection.setAutoCommit(false);
        LibsqlStatement statement = new LibsqlStatement(connection);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> work = executor.submit(() -> statement.execute("SELECT 1"));
            assertThat(client.awaitStatementInFlight()).isTrue();

            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            joinAndCheck(closeInThread(connection, closeFailure), closeFailure);

            Throwable workFailure = failureOf(work);
            assertThat(client.violations()).as("protocol violations").isEmpty();
            assertThat(workFailure).isNull();
            assertThat(client.hasOpenStream()).isFalse();
            assertThat(connection.isClosed()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeBatchThatLosesTheRaceToCloseFailsWithStatementClosed() throws Exception {
        Pause pause = new Pause();
        LibsqlStatement statement = new LibsqlStatement(new PausingBatchConnection(pause));
        statement.addBatch("insert into t(value) values (1)");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<int[]> batch = executor.submit(statement::executeBatch);

            assertThat(pause.awaitReached()).isTrue();
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            joinAndCheck(closeInThread(statement, closeFailure), closeFailure);
            assertThat(statement.isClosed()).isTrue();
            pause.resume();

            assertThatThrownBy(() -> batch.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Statement is closed");
        } finally {
            pause.resume();
            executor.shutdownNow();
        }
    }

    private static Void executeRepeatedly(LibsqlStatement statement, int count) throws SQLException {
        for (int i = 0; i < count; i++) {
            statement.execute("SELECT " + i);
        }
        return null;
    }

    private static Thread commitInThread(
            LibsqlConnection connection, AtomicReference<Throwable> failure) {
        return runInThread(() -> connection.commit(), failure);
    }

    private static Thread closeInThread(
            LibsqlConnection connection, AtomicReference<Throwable> failure) {
        return runInThread(() -> connection.close(), failure);
    }

    private static Thread runInThread(ThrowingRunnable action, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        return thread;
    }

    private static Throwable failureOf(Future<?>... futures) throws Exception {
        for (Future<?> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                return e.getCause();
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Thread closeInThread(
            LibsqlStatement statement, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                statement.close();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        return thread;
    }

    private static void joinAndCheck(Thread thread, AtomicReference<Throwable> failure)
            throws InterruptedException {
        thread.join(5_000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    private static final class Pause {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        private void await() throws SQLException {
            reached.countDown();
            try {
                if (!resume.await(5, TimeUnit.SECONDS)) {
                    throw new SQLException("Timed out waiting to resume execute");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
        }

        private boolean awaitReached() throws InterruptedException {
            return reached.await(5, TimeUnit.SECONDS);
        }

        private void resume() {
            resume.countDown();
        }
    }

    private static final class PausingStatement extends LibsqlStatement {
        private final Pause pause;

        private PausingStatement(LibsqlConnection connection, Pause pause) {
            super(connection);
            this.pause = pause;
        }

        @Override
        protected void beforeResultSetAssignment() throws SQLException {
            pause.await();
        }
    }

    private static final class PausingPreparedStatement extends LibsqlPreparedStatement {
        private final Pause pause;

        private PausingPreparedStatement(
                LibsqlConnection connection, String sql, Pause pause) {
            super(connection, sql);
            this.pause = pause;
        }

        @Override
        protected void beforeResultSetAssignment() throws SQLException {
            pause.await();
        }
    }

    /**
     * Holds the batch inside the connection round trip so a close can land before the statement
     * reaches its own cleanup.
     */
    private static final class PausingBatchConnection extends LibsqlConnection {
        private final Pause pause;

        private PausingBatchConnection(Pause pause) {
            super(null, null);
            this.pause = pause;
        }

        @Override
        public List<StmtResult> executeBatchResults(List<Stmt> statements) throws SQLException {
            pause.await();
            return statements.stream()
                    .map(statement -> new StmtResult(List.of(), List.of(), 1, null))
                    .toList();
        }
    }

    /**
     * Models the baton rules the connection must uphold: one open stream per connection, one
     * request on a stream at a time, and a baton that is consumed by its request. Every rule break
     * is recorded instead of thrown, so a failing case reports why the interleaving was invalid.
     */
    private static final class ProtocolClient implements LibsqlClient {

        private final Object lock = new Object();
        private final List<String> violations = new ArrayList<>();
        private final Set<String> inFlightBatons = new HashSet<>();
        private final CountDownLatch statementInFlight = new CountDownLatch(1);
        private final AtomicBoolean statementWindowUsed = new AtomicBoolean();
        private final CyclicBarrier beginRace;
        private int inFlightRequests;
        private String liveBaton;
        private int batonCount;
        private boolean streamOpen;

        private ProtocolClient(CyclicBarrier beginRace) {
            this.beginRace = beginRace;
        }

        @Override
        public StmtResult executeOneShot(Stmt stmt) {
            PipelineReqBody request = new PipelineReqBody(null, List.of(
                    new StreamRequest.Execute(stmt),
                    StreamRequest.Close.INSTANCE));
            PipelineRespBody response = roundTrip(request, false, true);
            StreamResult first = response.results().get(0);
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
        public StmtResult executeOnStream(Stmt stmt) {
            PipelineReqBody request = new PipelineReqBody(
                    currentBaton(), List.of(new StreamRequest.Execute(stmt)));
            StreamResult first = roundTrip(request, true, false).results().get(0);
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
            return batchResult(roundTrip(request, false, true).results().get(0));
        }

        @Override
        public BatchResult executeBatchOnStream(Batch batch) {
            PipelineReqBody request = new PipelineReqBody(
                    currentBaton(), List.of(new StreamRequest.BatchReq(batch)));
            return batchResult(roundTrip(request, true, false).results().get(0));
        }

        @Override
        public boolean hasOpenStream() {
            synchronized (lock) {
                return streamOpen;
            }
        }

        @Override
        public void closeStream() {
            PipelineReqBody request;
            synchronized (lock) {
                if (!streamOpen) {
                    return;
                }
                request = new PipelineReqBody(liveBaton, List.of(StreamRequest.Close.INSTANCE));
            }
            roundTrip(request, false, true);
        }

        @Override
        public void close() {
            // No connection-level state to release.
        }

        private String currentBaton() {
            synchronized (lock) {
                return streamOpen ? liveBaton : null;
            }
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

        private PipelineRespBody roundTrip(
                PipelineReqBody request, boolean opensStream, boolean closesStream) {
            String baton = request.baton();
            StreamRequest first = request.requests().get(0);
            String sql = first instanceof StreamRequest.Execute execute
                    ? execute.stmt().sql()
                    : null;

            synchronized (lock) {
                if (inFlightRequests > 0) {
                    violations.add("concurrent round trip while " + inFlightRequests
                            + " was in flight: " + sql);
                }
                if (baton != null && inFlightBatons.contains(baton)) {
                    violations.add("baton reused while in flight: " + baton);
                } else if (baton != null && (!streamOpen || !baton.equals(liveBaton))) {
                    violations.add("request on a stream that is not open: " + baton);
                } else if (baton == null && streamOpen) {
                    violations.add("new stream while another is open");
                }
                inFlightRequests++;
                if (baton != null) {
                    inFlightBatons.add(baton);
                }
            }
            try {
                if (isBegin(sql)) {
                    awaitBeginRace();
                } else if (sql != null && !isTransactionEnd(sql)) {
                    if (statementInFlight.getCount() > 0) {
                        statementInFlight.countDown();
                        if (statementWindowUsed.compareAndSet(false, true)) {
                            pause();
                        }
                    }
                }
                synchronized (lock) {
                    if (closesStream) {
                        liveBaton = null;
                        streamOpen = false;
                    } else if (opensStream || streamOpen) {
                        liveBaton = "baton-" + (++batonCount);
                        streamOpen = true;
                    }
                    return new PipelineRespBody(
                            closesStream ? null : liveBaton, null, responsesFor(request));
                }
            } finally {
                synchronized (lock) {
                    inFlightRequests--;
                    if (baton != null) {
                        inFlightBatons.remove(baton);
                    }
                }
            }
        }

        private void awaitBeginRace() {
            if (beginRace == null) {
                return;
            }
            try {
                beginRace.await(200, TimeUnit.MILLISECONDS);
            } catch (Exception expected) {
                // The fixed connection holds its lock, so the second begin never arrives.
            }
        }

        private static void pause() {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static boolean isBegin(String sql) {
            return sql != null && sql.startsWith("BEGIN");
        }

        private static boolean isTransactionEnd(String sql) {
            return "COMMIT".equals(sql) || "ROLLBACK".equals(sql);
        }

        private static List<StreamResult> responsesFor(PipelineReqBody request) {
            List<StreamResult> results = new ArrayList<>();
            for (StreamRequest streamRequest : request.requests()) {
                if (streamRequest instanceof StreamRequest.Close) {
                    results.add(new StreamResult.Ok(StreamResponse.Close.INSTANCE));
                } else {
                    results.add(new StreamResult.Ok(new StreamResponse.Execute(new StmtResult(
                            List.of(), List.of(), 0, null))));
                }
            }
            return results;
        }

        private boolean awaitStatementInFlight() throws InterruptedException {
            return statementInFlight.await(5, TimeUnit.SECONDS);
        }

        private List<String> violations() {
            synchronized (lock) {
                return List.copyOf(violations);
            }
        }
    }

    private static final class RecordingConnection extends LibsqlConnection {
        private final StmtResult result;

        private RecordingConnection(StmtResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public StmtResult executeStatement(Stmt stmt) {
            return result;
        }
    }
}
