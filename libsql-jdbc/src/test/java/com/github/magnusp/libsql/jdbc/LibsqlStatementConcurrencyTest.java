package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Row;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LibsqlStatementConcurrencyTest {

    private static final StmtResult ROWS = new StmtResult(
            List.of(new Col("value", null)),
            List.of(new Row(List.of(Value.of(1L)))),
            0,
            null);

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
