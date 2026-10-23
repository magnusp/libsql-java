# libSQL xerial Conformance Tests

This module runs a selected subset of the xerial `sqlite-jdbc` test sources against
libSQL over the Hrana protocol. It is a compatibility check for the JDBC driver, not a
claim that a `jdbc:sqlite:` URL is served by a local SQLite file.

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

The query string is ignored when deriving the namespace, so URL pragmas such as
`?journal_mode=WAL` have no effect on the conformance server. No local SQLite file is
created; the data lives in the sqld namespace.

## Persistence

A path namespace is retained until the suite shuts down, so closing every connection
and reopening the same path observes the data written earlier. The retained namespaces
are deleted during suite shutdown and the shared sqld container is stopped. In-memory
namespaces are not retained.

The conformance copy of an upstream test diverges from xerial where Hrana connection
lifecycle makes the original behavior impossible; each divergence is recorded in
`../upstream-xerial_sqlite-jdbc.manifest`.

## Divergences from a local SQLite connection

Hrana over HTTP has no connection. A stream is identified only by the `baton`
in the pipeline request: `baton: null` creates a new stream, and the server
closes streams after a short period of inactivity because there is no
connection that would let it detect an abandoned stream
(`../vendor/HRANA_3_SPEC.md`, "Hrana over HTTP"). Two observable divergences
from xerial `sqlite-jdbc`, which owns one SQLite connection per JDBC
connection, follow from that model. Per-test divergences in the vendored
suite are recorded separately in `../upstream-xerial_sqlite-jdbc.manifest`.

### Auto-commit holds no stream

An auto-commit statement runs as a one-shot pipeline that executes the
statement and closes the stream in the same round trip. The driver keeps no
session stream open between statements, so an idle connection consumes no
server stream slot and holds no baton for the idle expiry to invalidate.
Keeping one open only to preserve session state would reintroduce
stale-baton failures on idle connections.

Because `StmtResult.last_insert_rowid` describes the insert on one stream
and nothing else, a later `SELECT last_insert_rowid()` runs on a fresh
stream and returns `0`, even when the same `Statement` inserted a row
immediately before. xerial returns that insert's rowid instead. Use
`Statement.getGeneratedKeys()` after the insert, which the driver builds
from `StmtResult.last_insert_rowid` with no extra round trip, or run the
insert and the query inside one explicit transaction so both run on the
same stream. `LibsqlGeneratedKeysTest` pins the generated-keys path.

### Transactions begin lazily

`setAutoCommit(false)` sends nothing. The next statement begins the
transaction on a new stream with `BEGIN`, `BEGIN IMMEDIATE`, or
`BEGIN EXCLUSIVE` according to
`LibsqlConnection.setTransactionMode(TransactionMode)` (default `DEFERRED`),
sent as its own pipeline so a failed begin cannot be followed by the
statement running outside a transaction. `commit()` and `rollback()` close
the stream and do not begin the next transaction, so an idle non-auto-commit
connection likewise holds no stream.

The divergence is timing only: an `IMMEDIATE` or `EXCLUSIVE` transaction
takes its write lock when the first statement begins it, not at
`setAutoCommit(false)`. Select that mode when the lock must be held from
transaction start; with the default `DEFERRED`, two readers that both
upgrade can still see `SQLITE_BUSY`, as in SQLite. A transaction left open
and idle longer than the server timeout can still fail on a stale baton,
so keep transactions short and retry the work in a fresh one.
