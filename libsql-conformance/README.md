# libSQL xerial Conformance Tests

This module runs a selected subset of the xerial `sqlite-jdbc` test sources against
libSQL over the Hrana protocol. It is a compatibility check for the JDBC driver, not a
claim that a `jdbc:sqlite:` URL is served by a local SQLite file.

## Conformance status

This section is the canonical answer to "have we reached conformance with
xerial?". The short answer is: yes for the selected subset below, verified
against the reference probe; no for full xerial parity, and full parity is
not the goal. The exclusions are deliberate compromises of running SQLite
semantics remotely over Hrana, plus one that turns on the server build. They
are grouped below. Per-test selection, rejection,
and test-copy divergence edits are recorded in
[`../upstream-xerial_sqlite-jdbc.manifest`](../upstream-xerial_sqlite-jdbc.manifest).
The driver-contract divergences that follow from the remote model are described
below.

### Selected subset and reference probe

The suite vendors the pinned xerial `sqlite-jdbc` 3.53.4.0 test tree and
selects the tests that exercise JDBC behavior through ordinary SQL, so the
Hrana transport can satisfy them unchanged. The manifest selects 13 test
classes plus the shared `Utils` support class: `ResultSetTest`,
`InsertQueryTest`, `FetchSizeTest`, `CachedRowSetTest`, `JSON1Test`,
`MetadataLeakTest`, `PreparedStatementThreadTest`,
`ResultSetWithoutResultsTest`, `RSMetaDataTest`, `DBMetaDataTest`,
`StatementTest`, `PrepStmtTest`, and `SavepointTest`. The remaining 26
classes in the pinned tree stay upstream for the reasons in the manifest.

The reference probe is the `xerial-control` Maven profile defined in
[`pom.xml`](pom.xml): it runs the same vendored manifest against the pinned
xerial 3.53.4.0 driver instead of the libSQL driver. Both drivers are on the test class path, so the control run removes
every visible driver and registers the xerial driver explicitly, and the
build rejects a hand-set `conformance.control.driver` so the probe can only
be selected through the profile. Run it with:

```bash
./mvnw -pl libsql-conformance -am -Pxerial-control verify
```

Run the libSQL conformance suite itself, against the pinned sqld image,
with:

```bash
./mvnw -pl libsql-conformance -am verify
```

Last green result: 271 tests, 7 skipped, with `SavepointTest` included. The
count excludes the three Appendix B quarantines below and includes the two
harness tests for the quarantine.

### Appendix B quarantines

JDBC 4.3 Appendix B is the oracle. Three selected xerial test methods assert
behavior that Appendix B replaces. They stay in the vendored tree and still
run in the `xerial-control` profile, but `XerialQuarantine` excludes them from
the libSQL run. The full type profile is in
[`../docs/jdbc-appendix-b-compliance.md`](../docs/jdbc-appendix-b-compliance.md).

| Test | Why the xerial expectation is quarantined |
|---|---|
| `org.sqlite.RSMetaDataTest#columnTypes` | xerial reports a declared `NCHAR` as `Types.CHAR`. Appendix B defines `NCHAR` as its own JDBC type, so the driver reports `Types.NCHAR`. |
| `org.sqlite.RSMetaDataTest#testGetColumnClassName` | xerial reports a declared `BLOB` as `java.lang.Object`. Table B-3 maps `BLOB` to `java.sql.Blob`. |
| `org.sqlite.PrepStmtTest#getObject` | xerial returns an `Integer` for `getObject` on a declared `BIT`. Table B-3 maps `BIT` to `Boolean`. |

The exclusion is method-level, so each method also loses checks that do not
conflict with Appendix B:

- `RSMetaDataTest#columnTypes` also checks other column types that do not conflict, including the INTEGER family, text, numeric, boolean, date and timestamp types, plus DECIMAL precision and scale.
- `RSMetaDataTest#testGetColumnClassName` also checks the INTEGER, VARCHAR, and DOUBLE class names.
- `PrepStmtTest#getObject` also checks other `getObject` rows (INTEGER, FLOAT, untyped, VARCHAR and BLOB columns) and the typed getters above them.

### Three-layer contract

When the layers disagree, the contract is:

1. Hrana 3 is the source of truth for transport, values, metadata,
   batching, transactions, and error reporting. The protocol is specified
   in [`../vendor/HRANA_3_SPEC.md`](../vendor/HRANA_3_SPEC.md); where
   xerial behavior would require a primitive Hrana does not expose, the
   Hrana behavior wins.
2. `java.sql` is the source of truth for the JDBC surface. Where JDBC
   makes a feature optional or defines the observable behavior, the
   driver follows `java.sql`, even when xerial chooses differently.
3. xerial is a behavioral reference and the conformance foundation, never
   a copy target. Its tests define the selected subset and the control
   probe; its driver internals, configuration surface, and native
   behaviors are not reproduced.

### Compromise categories

Full parity is impossible without changing what the driver is: a remote
Hrana client with no local file, no shared connection, and no native
library. Three of the four categories below follow from that model; the
fourth, build-flag dependence, turns on the server build. Each category has a
concrete example:

| Category | Why parity is impossible | Example |
|---|---|---|
| Native extension API | The test registers JVM callbacks or loads native code in-process; Hrana exposes no equivalent primitive, so this cannot be reversed without a new Hrana or native primitive. | `UDFTest` registers JVM user-defined functions through sqlite-jdbc's native extension API. |
| sqlite-jdbc configuration surface | The test exercises xerial's own configuration, `DataSource`, loading, or source conventions instead of portable JDBC over SQL; libSQL provides no counterpart. | `SQLiteConfigTest` tests the `SQLiteConfig` object, not the JDBC API. |
| Remote-model semantics | The test asserts local-file or in-process SQLite semantics (file locking and multi-connection waits, shared cache, native error wording and codes, or a deliberate driver-contract divergence) that a single remote sqld server over Hrana cannot reproduce; see "Hrana over HTTP" in `../vendor/HRANA_3_SPEC.md` for the baton and stream-expiry model behind this. | `TransactionTest` asserts SQLite file-locking and multi-connection wait behavior. |
| Build-flag dependence | The test passes only when the server binary is built with a compile-time option that the pinned sqld image does not guarantee; this is addressable by pinning the server build, not by driver work. | `MathFunctionsTest` requires the server binary to be compiled with `SQLITE_ENABLE_MATH_FUNCTIONS`. |

The first three categories are permanent: the class stays upstream unless
the underlying Hrana, sqld, or sqlite-jdbc surface changes. Build-flag
dependence is the exception: the class stays upstream only until the sqld
build is pinned to enable the flag.

### Driver-contract divergences

Hrana over HTTP has no connection. A stream is identified only by the `baton`
in the pipeline request: `baton: null` creates a new stream, and the server
closes streams after a short period of inactivity because there is no
connection that would let it detect an abandoned stream
([`../vendor/HRANA_3_SPEC.md`](../vendor/HRANA_3_SPEC.md), "Hrana over HTTP").
xerial `sqlite-jdbc` owns one SQLite connection per JDBC connection, so two
observable divergences follow from the remote model. Unlike the rejected
categories above, these occur in selected tests. They are driver behavior
rather than edits to a vendored test, so they are described here and are not
recorded as `# divergence:` lines.

#### Auto-commit holds one connection-scoped stream

An auto-commit connection lazily opens one interactive Hrana stream and
pipelines every statement on it, the same machinery explicit transactions
use. `last_insert_rowid()`, `PRAGMA` settings, and other session state
therefore behave as they do on a SQLite connection: they survive between
statements on the same connection and do not cross connections.
`Statement.getGeneratedKeys()` still reads `StmtResult.last_insert_rowid`
without an extra round trip.

sqld expires idle streams (around 10 seconds). When it rejects a baton, the
driver drops the stream and retries the statement once on a fresh one.
Auto-commit has no transaction to lose, but session state set before the
expiry is reset. A pool larger than sqld's stream limit can hit that limit,
just as idle non-auto-commit connections already can.

#### Transactions begin lazily

`setAutoCommit(false)` sends nothing. The next statement begins the
transaction on the connection's stream with `BEGIN`, `BEGIN IMMEDIATE`, or
`BEGIN EXCLUSIVE` according to
`LibsqlConnection.setTransactionMode(TransactionMode)` (default `DEFERRED`),
sent as its own pipeline so a failed begin cannot be followed by the
statement running outside a transaction. A connection that used auto-commit
first keeps that stream, so its session state carries into the transaction.
`commit()` and `rollback()` close the stream and do not begin the next
transaction.

The divergence is timing only: an `IMMEDIATE` or `EXCLUSIVE` transaction takes
its write lock when the first statement begins it, not at
`setAutoCommit(false)`. Select that mode when the lock must be held from
transaction start; with the default `DEFERRED`, two readers that both upgrade
can still see `SQLITE_BUSY`, as in SQLite. A transaction left open and idle
longer than the server timeout can still fail on a stale baton, so keep
transactions short and retry the work in a fresh one.

### Savepoint support

Savepoints were the last addressable JDBC-surface gap. The driver now
implements `Connection.setSavepoint()`, `setSavepoint(String)`,
`rollback(Savepoint)`, and `releaseSavepoint(Savepoint)` as SQLite
`SAVEPOINT`, `RELEASE`, and `ROLLBACK TO` statements over Hrana, and
`DatabaseMetaData.supportsSavepoints()` returns `true`. `SavepointTest` is
selected, so no JDBC-surface gap remains. No new server primitive was
needed.

## URL mapping

The conformance driver maps `jdbc:sqlite:` URLs to sqld namespaces so the vendored
tests can run unchanged:

| URL | Namespace |
|---|---|
| `jdbc:sqlite:` | A new in-memory namespace per connection |
| `jdbc:sqlite::memory:` | A new in-memory namespace per connection |
| `jdbc:sqlite:<path>` | One namespace derived from `<path>` |

A file path is resolved against the process working directory and normalized before
it is hashed. A relative path and the equivalent absolute path therefore address the
same namespace; for example, `data/example.db` and `/work/data/example.db` match when
the process working directory is `/work`. Normalization resolves `.` and `..` but does
not resolve symbolic links.

The query string is part of the namespace identity, so two URLs that differ only in
their query string address different namespaces. The option values themselves are not
applied to the conformance server; a URL pragma such as `?journal_mode=WAL` selects a
separate namespace rather than changing server behavior. No local SQLite file is
created; the data lives in the sqld namespace.

## Persistence

A path namespace is retained until the suite shuts down, so closing every connection
and reopening the same path observes the data written earlier. The retained namespaces
are deleted during suite shutdown and the shared sqld container is stopped. In-memory
namespaces are not retained.

The conformance copy of an upstream test diverges from xerial where Hrana connection
lifecycle makes the original behavior impossible.
