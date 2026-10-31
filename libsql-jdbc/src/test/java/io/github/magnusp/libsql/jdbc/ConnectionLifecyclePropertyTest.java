package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.client.LibsqlClient;
import io.github.magnusp.libsql.client.LibsqlClientConfig;
import io.github.magnusp.libsql.hrana.codec.Batch;
import io.github.magnusp.libsql.hrana.codec.BatchResult;
import io.github.magnusp.libsql.hrana.codec.Stmt;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.ImperativeCommand;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Model-based property for the connection transaction lifecycle.
 *
 * <p>jetCheck's {@link ImperativeCommand} support generates and shrinks command sequences while
 * replaying them against a fresh connection. The model below tracks auto-commit, the optional open
 * transaction, and the savepoint stack. The fake client never rejects a statement, so a failure is
 * a driver state-machine bug rather than a server error.
 */
class ConnectionLifecyclePropertyTest {

    private static final int ITERATIONS = 1_000;
    private static final LibsqlClientConfig CONFIG =
            new LibsqlClientConfig("http://localhost:1", null);

    @Test
    void generatedLifecyclesMatchTheTransactionModel() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .checkScenarios(ConnectionScenario::new);
    }

    private enum ActionKind {
        SET_AUTO_COMMIT_TRUE,
        SET_AUTO_COMMIT_FALSE,
        COMMIT,
        ROLLBACK,
        SET_SAVEPOINT,
        RELEASE_LATEST,
        ROLLBACK_LATEST,
        EXECUTE,
        CLOSE
    }

    private record Action(ActionKind kind, String savepointName) {}

    private record State(
            boolean closed,
            boolean autoCommit,
            boolean transactionActive,
            boolean transactionStartedBySavepoint,
            List<String> savepoints) {

        static State initial() {
            return new State(false, true, false, false, List.of());
        }

        boolean allows(Action action) {
            if (action.kind() == ActionKind.CLOSE) {
                return true;
            }
            if (closed) {
                return false;
            }
            return switch (action.kind()) {
                case COMMIT, ROLLBACK -> !autoCommit;
                case RELEASE_LATEST, ROLLBACK_LATEST -> !autoCommit && !savepoints.isEmpty();
                default -> true;
            };
        }

        State after(Action action) {
            return switch (action.kind()) {
                case SET_AUTO_COMMIT_TRUE -> autoCommit
                        ? this
                        : new State(false, true, false, false, List.of());
                case SET_AUTO_COMMIT_FALSE ->
                        new State(false, false, transactionActive, transactionStartedBySavepoint, savepoints);
                case COMMIT, ROLLBACK -> new State(false, false, false, false, List.of());
                case SET_SAVEPOINT -> autoCommit
                        ? new State(false, false, true, true, List.of(action.savepointName()))
                        : new State(
                                false,
                                false,
                                true,
                                transactionStartedBySavepoint,
                                append(savepoints, action.savepointName()));
                case RELEASE_LATEST -> {
                    int index = savepoints.lastIndexOf(last(savepoints));
                    boolean started = index == 0 && transactionStartedBySavepoint;
                    yield new State(
                            false,
                            false,
                            started ? false : transactionActive,
                            started ? false : transactionStartedBySavepoint,
                            List.copyOf(savepoints.subList(0, index)));
                }
                case ROLLBACK_LATEST -> {
                    int index = savepoints.lastIndexOf(last(savepoints));
                    yield new State(
                            false,
                            false,
                            transactionActive,
                            transactionStartedBySavepoint,
                            List.copyOf(savepoints.subList(0, index + 1)));
                }
                case EXECUTE -> autoCommit
                        ? this
                        : new State(false, false, true, transactionStartedBySavepoint, savepoints);
                case CLOSE -> new State(true, autoCommit, false, false, List.of());
            };
        }

        private static List<String> append(List<String> values, String value) {
            List<String> copy = new ArrayList<>(values);
            copy.add(value);
            return List.copyOf(copy);
        }

        private static String last(List<String> values) {
            return values.get(values.size() - 1);
        }
    }

    private static final class ConnectionScenario implements ImperativeCommand {

        @Override
        public void performCommand(ImperativeCommand.Environment env) {
            Fixture fixture = new Fixture();
            try {
                env.executeCommands(IntDistribution.uniform(0, 50), commands(fixture));
                fixture.assertInvariants();
            } finally {
                try {
                    fixture.connection.close();
                } catch (SQLException e) {
                    throw new AssertionError("could not close the connection", e);
                }
            }
        }
    }

    private record Command(Fixture fixture, ActionKind kind, String savepointName)
            implements ImperativeCommand {

        @Override
        public void performCommand(ImperativeCommand.Environment env) {
            env.logMessage(kind + (savepointName == null ? "" : "(" + savepointName + ")"));
            fixture.apply(new Action(kind, savepointName));
        }
    }

    private static final class Fixture {

        private final RecordingClient client = new RecordingClient();
        private final LibsqlConnection connection = new LibsqlConnection(CONFIG, client);
        private final List<Savepoint> savepoints = new ArrayList<>();
        private State state = State.initial();

        void apply(Action action) {
            if ((action.kind() == ActionKind.RELEASE_LATEST || action.kind() == ActionKind.ROLLBACK_LATEST)
                    && state.savepoints().isEmpty()) {
                return;
            }

            boolean expectedToSucceed = state.allows(action);
            try {
                execute(action);
                assertThat(expectedToSucceed)
                        .as("%s should have failed in %s", action.kind(), state)
                        .isTrue();
                state = state.after(action);
            } catch (SQLException expected) {
                assertThat(expectedToSucceed)
                        .as("unexpected SQLException from %s in %s", action.kind(), state)
                        .isFalse();
            } catch (RuntimeException unexpected) {
                throw new AssertionError(
                        "unexpected " + unexpected + " from " + action.kind() + " in " + state, unexpected);
            }

            assertInvariants();
        }

        private void execute(Action action) throws SQLException {
            switch (action.kind()) {
                case SET_AUTO_COMMIT_TRUE -> connection.setAutoCommit(true);
                case SET_AUTO_COMMIT_FALSE -> connection.setAutoCommit(false);
                case COMMIT -> connection.commit();
                case ROLLBACK -> connection.rollback();
                case SET_SAVEPOINT -> savepoints.add(connection.setSavepoint(action.savepointName()));
                case RELEASE_LATEST -> {
                    connection.releaseSavepoint(lastSavepoint());
                    savepoints.remove(savepoints.size() - 1);
                }
                case ROLLBACK_LATEST -> connection.rollback(lastSavepoint());
                case EXECUTE -> connection.executeStatement(new Stmt("SELECT 1"));
                case CLOSE -> connection.close();
            }
        }

        private Savepoint lastSavepoint() {
            return savepoints.get(savepoints.size() - 1);
        }

        void assertInvariants() {
            try {
                assertThat(connection.isClosed()).isEqualTo(state.closed());
                if (state.closed()) {
                    assertThatThrownBy(connection::getAutoCommit).isInstanceOf(SQLException.class);
                } else {
                    assertThat(connection.getAutoCommit()).isEqualTo(state.autoCommit());
                }
            } catch (SQLException e) {
                throw new AssertionError("could not read connection state", e);
            }
            assertThat(state.autoCommit() && (!state.savepoints().isEmpty() || state.transactionActive()))
                    .as("auto-commit implies no transaction state, was %s", state)
                    .isFalse();
            assertThat(state.transactionStartedBySavepoint()
                            && !(state.transactionActive()
                                    && !state.autoCommit()
                                    && !state.savepoints().isEmpty()))
                    .as("savepoint-owned transaction must be active, was %s", state)
                    .isFalse();
        }
    }

    private static Generator<Command> commands(Fixture fixture) {
        return Generator.sampledFrom(
                new Command(fixture, ActionKind.SET_AUTO_COMMIT_TRUE, null),
                new Command(fixture, ActionKind.SET_AUTO_COMMIT_FALSE, null),
                new Command(fixture, ActionKind.COMMIT, null),
                new Command(fixture, ActionKind.ROLLBACK, null),
                new Command(fixture, ActionKind.SET_SAVEPOINT, "a"),
                new Command(fixture, ActionKind.SET_SAVEPOINT, "b"),
                new Command(fixture, ActionKind.SET_SAVEPOINT, "dup"),
                new Command(fixture, ActionKind.RELEASE_LATEST, null),
                new Command(fixture, ActionKind.ROLLBACK_LATEST, null),
                new Command(fixture, ActionKind.EXECUTE, null),
                new Command(fixture, ActionKind.CLOSE, null));
    }

    private static final class RecordingClient implements LibsqlClient {

        private boolean openStream;

        @Override
        public StmtResult executeOneShot(Stmt stmt) {
            return emptyResult();
        }

        @Override
        public StmtResult executeOnStream(Stmt stmt) {
            openStream = true;
            return emptyResult();
        }

        @Override
        public BatchResult executeBatchOneShot(Batch batch) {
            return emptyBatch(batch);
        }

        @Override
        public BatchResult executeBatchOnStream(Batch batch) {
            openStream = true;
            return emptyBatch(batch);
        }

        @Override
        public boolean hasOpenStream() {
            return openStream;
        }

        @Override
        public void closeStream() {
            openStream = false;
        }

        @Override
        public void close() {
            openStream = false;
        }

        private static StmtResult emptyResult() {
            return new StmtResult(List.of(), List.of(), 0, null);
        }

        private static BatchResult emptyBatch(Batch batch) {
            Map<Integer, StmtResult> results = new LinkedHashMap<>();
            for (int i = 0; i < batch.steps().size(); i++) {
                if (batch.steps().get(i).stmt() != null) {
                    results.put(i, emptyResult());
                }
            }
            return new BatchResult(results, Map.of());
        }
    }
}
