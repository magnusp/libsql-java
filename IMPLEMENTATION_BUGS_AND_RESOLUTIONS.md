# Turso/libSQL JDBC Driver Implementation: Bugs & Resolutions Reference

This document compiles the critical bugs, performance bottlenecks, and concurrency deadlocks encountered during the design, implementation, and benchmarking of the pure-Java libSQL/Turso JDBC driver (`turso-java`), along with their root causes, diagnostic methodologies, and resolutions.

---

## Table of Contents
1. [Bug 1: One-Shot Query Hrana Stream Leak (The "128-Write 10-Second Stall")](#1-bug-1-one-shot-query-hrana-stream-leak-the-128-write-10-second-stall)
2. [Bug 2: Transaction Lifecycle over Hrana (Lazy Begin and WAL Locks)](#2-bug-2-transaction-lifecycle-over-hrana-lazy-begin-and-wal-locks)
3. [Bug 3: Transient Connection Starvation & Unhandled HTTP 429](#3-bug-3-transient-connection-starvation--unhandled-http-429)
4. [Bug 4: Per-Connection `HttpClient` Instantiation (Missing HTTP Keep-Alive Pool)](#4-bug-4-per-connection-httpclient-instantiation-missing-http-keep-alive-pool)
5. [Bug 5: Jackson Databind Reflection & Excessive Object Allocation Sprawl](#5-bug-5-jackson-databind-reflection--excessive-object-allocation-sprawl)
6. [Bug 6: O(N) Linear Column Lookups in ResultSet Iteration](#6-bug-6-on-linear-column-lookups-in-resultset-iteration)
7. [Bug 7: PreparedStatement Parameter Boxed-Object Overhead](#7-bug-7-preparedstatement-parameter-boxed-object-overhead)
8. [Divergence: `SELECT last_insert_rowid()` in Auto-Commit Reads a Fresh Stream](#8-divergence-select-last_insert_rowid-in-auto-commit-reads-a-fresh-stream)

---

## 1. Bug 1: One-Shot Query Hrana Stream Leak (The "128-Write 10-Second Stall")

### Symptoms
During single-writer autocommit performance tests and batch write benchmarks:
- Writes #1 through #127 completed quickly (~2 ms per query, ~500 ops/sec).
- **Write #128 stalled completely for exactly ~10 seconds** before proceeding.
- Overall benchmark throughput collapsed to **~11.4 operations/second** (`125 operations / 11 seconds`).

### Investigation & Root Cause
1. **Initial (False) Hypothesis**: WAL checkpointing (`wal_autocheckpoint`) was suspected of pausing SQLite writes. To isolate this, `/bin/sqld` was disassembled, and the internal SQLite checkpoint hook (`sqlite3WalDefaultHook`) was patched with a no-op instruction (`xor %eax, %eax; ret;`) to disable checkpoints entirely. The ~10-second pause **still occurred** at write #128.
2. **Server Trace Logging**: Inspecting `sqld` logs with `RUST_LOG=debug` revealed the unblocking trigger:
   ```text
   DEBUG libsql_server::hrana::http::stream: Stream 11615124933555682105 was expired
   DEBUG timings: name="connection-duration" elapsed=10.528329356s
   DEBUG close{id=103}: libsql_server::connection::connection_manager: closed in 822ns id=103
   ```
3. **The Protocol Mechanism**:
   - In Hrana HTTP v2/v3, sending a request without a `baton` tells the server to allocate a **new stateful stream** in memory and return a fresh `baton` waiting for follow-up queries.
   - For one-shot queries (`autoCommit=true`), the driver simply sent `[execute]` without maintaining or closing the resulting baton.
   - `sqld` enforces a default stream pool capacity of `--max-concurrent-streams=128` and an idle stream timeout of **10 seconds**.
   - Consequently, the first 127 queries filled the server's stream pool with idle, orphaned streams. Write #128 exhausted the pool and blocked until stream #1 reached its 10-second idle timeout and was reaped.

### Resolution
Pipelines for one-shot / autocommit queries were updated to append an explicit `CloseStreamReq` in the same HTTP roundtrip:
```java
// LibsqlHttpClient.java
public StmtResult executeOneShot(Stmt stmt, Duration customTimeout) throws SQLException {
    PipelineRespBody resp = sendPipeline(
        null,
        List.of(new ExecuteReq(stmt), new CloseStreamReq()),
        customTimeout
    );
    // ... parse StmtResult ...
}
```
Because the stream is closed immediately on the server upon query completion, no idle slots are occupied, completely eliminating the 10-second stall.

---

## 2. Bug 2: Transaction Lifecycle over Hrana (Lazy Begin and WAL Locks)

### Symptoms
- When multiple concurrent worker threads (or HikariCP pool connections) performed transactions, threads that read first and then attempted writes were aborted with:
  ```text
  SQLITE_BUSY: database is locked
  ```
- A non-auto-commit connection that sat idle held a server stream. Once sqld expired it, the next statement failed on a stale baton, and `commit()`, `rollback()`, and `setAutoCommit(true)` sent the same dead baton again.
- A failing statement, `BEGIN`, `COMMIT`, `ROLLBACK`, or batch step inside a transaction produced a plain `SQLException`, losing the SQLState and vendor code that auto-commit preserved.

### Root Cause
1. In SQLite WAL mode, multiple connections can hold concurrent `SHARED` locks to read simultaneously.
2. If a transaction starts with standard `BEGIN` (which defaults to `BEGIN DEFERRED`), SQLite waits until the first `INSERT`/`UPDATE` to upgrade from `SHARED` to `RESERVED`/`EXCLUSIVE`.
3. If two concurrent transactions read first and both attempt to write later, both hold `SHARED` locks and try to upgrade. SQLite cannot grant either without deadlocking, returning immediate `SQLITE_BUSY` errors.
4. Earlier driver versions began the transaction in `setAutoCommit(false)` and began the next one in `commit()`/`rollback()`, so an idle connection always held a stream. Hrana over HTTP has no connection with which the server can notice an abandoned stream, so it expires idle streams (`vendor/HRANA_3_SPEC.md`, "Hrana over HTTP"), and the driver did not detect a dead baton.
5. The in-transaction path read `StreamResult.Error` and threw it directly, while the auto-commit path went through `LibsqlErrorMapper`.

### Implemented Contract
`LibsqlConnection` derives the transaction state from the baton: `beginPending()` is true when auto-commit is off and `baton == null`.

- **Lazy begin.** `setAutoCommit(false)` only sets the flag and sends nothing. The next `executeStatement` or `executeBatchResults` calls `beginTransaction()`, which sends the begin statement on a new stream (`baton == null`) as its own pipeline, then runs the statement on the baton it returns. The begin is never pipelined with the statement, so a failed `BEGIN` cannot be followed by that statement running outside a transaction.
- **Configured begin mode.** `transactionMode` defaults to `TransactionMode.DEFERRED` (`BEGIN`). `setTransactionMode(IMMEDIATE)` sends `BEGIN IMMEDIATE`, and `EXCLUSIVE` sends `BEGIN EXCLUSIVE`. The mode is read when the transaction begins, so changing it between transactions affects the next begin. It is set through `LibsqlConnection.setTransactionMode` (reachable with `Connection.unwrap`) and matches xerial's `SQLiteConfig.setTransactionMode`; it is not a JDBC URL property.
- **Commit and rollback close the stream.** `commit()` and `rollback()` send `COMMIT`/`ROLLBACK` on the current baton and then close that stream with a `close` request. Neither begins the next transaction. With no work done (`beginPending()`), both send nothing and leave auto-commit off, as in xerial.
- **The next statement begins a new stream.** After commit or rollback the baton is null, so the next statement sends `BEGIN` with a null baton and then the statement on the new baton. No transaction is re-begun eagerly.
- **Idle streams.** Because of the two points above, an idle non-auto-commit connection holds no Hrana stream: nothing is open after `setAutoCommit(false)`, and commit or rollback closes what was open. Idle pooled connections therefore do not consume sqld's 128-stream pool and hold no baton for the 10-second idle expiry to kill. The driver does not detect an expired baton, so a transaction left open and idle for longer than the server timeout can still fail with a stale baton; keep transactions short and retry the work in a fresh one.
- **Mapped in-transaction errors.** Every in-transaction `HranaError` goes through `LibsqlErrorMapper` at the four sites: `executeStatement`, `executeBatchResults`, `beginTransaction`, and `endTransaction`. A constraint violation is `SQLIntegrityConstraintViolationException` with SQLState `23000` and vendor code `19`, the same as in auto-commit. Busy or locked is `SQLTransientConnectionException` (`08001`, `5`), syntax is `SQLSyntaxErrorException` (`42000`, `1`), auth is `SQLInvalidAuthorizationSpecException` (`28000`), and the fallback is `SQLException` (`HY000`). Because Hrana leaves `Error.code` unstable, a missing or unrecognized code falls back to the message text for the constraint category. A failed batch step throws `BatchUpdateException` with the counts of the steps that ran, the mapped SQLState and vendor code, and the mapped exception as its cause.
- **Failure state.** A failed `BEGIN` closes the stream it opened, leaves the baton null, and keeps auto-commit off; `commit()` and `rollback()` then send nothing, and the next statement retries the begin on a new stream. A failed `COMMIT` or `ROLLBACK` keeps the baton, so the caller can retry the command or roll back on the same stream.

### Consequence for WAL Lock Upgrades
The default `BEGIN` is deferred, matching SQLite and xerial: no write lock is taken until the first write, so two readers that both upgrade can still see `SQLITE_BUSY`. The driver no longer converts that into orderly queue waiting by default. A caller that needs the write lock at transaction start selects `IMMEDIATE` or `EXCLUSIVE` with `setTransactionMode`, which acquires the lock when the lazily begun transaction starts. This is the timing divergence from xerial: an `IMMEDIATE` or `EXCLUSIVE` transaction does not take its lock at `setAutoCommit(false)`, because the server would expire the idle stream.

---

## 3. Bug 3: Transient Connection Starvation & Unhandled HTTP 429

### Symptoms
Under high concurrency or rapid connection churn from connection pools (HikariCP), queries failed with:
```text
java.sql.SQLException: libSQL HTTP error 429: {"error":"Timed out while opening database connection"}
```
or returned Hrana error envelopes with error code `"busy"` / `"database_locked"`.

### Root Cause
`sqld` serializes write transactions and limits internal connection slots. Under load bursts, `sqld`'s connection opening or lock acquisition times out, responding with HTTP 429 or `SQLITE_BUSY`. Without client-side retry handling, these transient delays crashed application threads.

### Resolution
Implemented automated retry with exponential backoff and jitter in `LibsqlHttpClient.java`:
1. Check if the HTTP status is `429` or if the response envelope contains a busy error code (`"busy"`, `"database_locked"`, `"SQLITE_BUSY"`, or message `"timed out while opening database connection"`).
2. Only retry if the request is idempotent or at transaction initiation (`baton == null`), avoiding corrupted intermediate state.
3. Apply jittered exponential backoff up to a configurable `busyTimeout` (default: 5000 ms):
   ```java
   long elapsed = System.currentTimeMillis() - startMs;
   if (baton == null && elapsed < busyTimeoutMs) {
       backoff(backoffMs, busyTimeoutMs - elapsed);
       backoffMs = Math.min(backoffMs * 2, 200L);
       continue;
   }
   ```

---

## 4. Bug 4: Per-Connection `HttpClient` Instantiation (Missing HTTP Keep-Alive Pool)

### Symptoms
- Single-writer throughput benchmarked at only **~12 ops/sec**, despite local `curl` / raw script benchmarks reaching **>500 ops/sec**.
- High CPU usage in socket creation and TLS/TCP handshakes.

### Root Cause
In `LibsqlDriver.connect(...)`, each JDBC `Connection` constructed its own `HranaHttpClient`, which in turn invoked `HttpClient.newHttpClient()`.
- `java.net.http.HttpClient` maintains its own internal TCP connection pool and HTTP/1.1 keep-alive cache.
- Creating a new client per JDBC connection meant TCP connections were never reused across connections, forcing every query or connection open to perform full TCP SYN/ACK handshakes and teardowns.

### Resolution
Introduced an internal client cache in `LibsqlDriver.java`:
```java
private static final ConcurrentHashMap<String, java.net.http.HttpClient> HTTP_CLIENT_CACHE = new ConcurrentHashMap<>();

private java.net.http.HttpClient getOrCreateHttpClient(Duration connectTimeout) {
    String key = "default:" + (connectTimeout != null ? connectTimeout.toMillis() : 10000);
    return HTTP_CLIENT_CACHE.computeIfAbsent(key, k -> java.net.http.HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(connectTimeout != null ? connectTimeout : Duration.ofSeconds(10))
            .build());
}
```
All JDBC connections share the pooled HTTP client, allowing HTTP/1.1 persistent keep-alive connections to stay warm.

---

## 5. Bug 5: Jackson Databind Reflection & Excessive Object Allocation Sprawl

### Symptoms
- Profiler traces showed significant GC pause times, high young-gen allocation rates, and memory churn in Jackson reflection caches (`ClassKey`, `BeanDeserializer`, polymorphic subtype resolution) during high-throughput queries.
- Serializing millions of cells allocated excessive boxed wrappers.

### Root Cause
1. Jackson's default `ObjectMapper` relies on reflection, annotations, and polymorphic type wrappers (`@JsonTypeInfo`), which introduce substantial overhead when serializing thousands of Hrana JSON payloads per second.
2. Frequent recreation of `ObjectMapper` or databind trees consumed memory and triggered frequent garbage collections.

### Resolution
1. **Replaced Databind with Streaming JSON Codec**: Created `DirectHranaJsonCodec.java` using low-level Jackson streaming (`JsonParser` / `JsonGenerator`) with zero reflection and zero intermediate JSON tree nodes (`JsonNode`).
2. **Flyweight Singletons**:
   - `Value.NullValue` replaced repeated allocation of null tokens.
   - Small integer flyweights and cached boolean constants were reused.
3. This reduced GC pressure and boosted pipelined batch insert throughput to **>26,000 rows/sec**.

---

## 6. Bug 6: O(N) Linear Column Lookups in ResultSet Iteration

### Symptoms
When reading wide tables (e.g. 50+ columns) via `rs.getString("column_name")` over many rows, query iteration slowed down noticeably.

### Root Cause
`findColumnIndex(columnLabel)` performed a linear scan (`for (int i = 0; i < cols.size(); i++)`) over the column list for every column access on every row, resulting in $O(\text{columns} \times \text{rows})$ execution time.

### Resolution
Added a lazily populated, case-insensitive index map to `LibsqlResultSet.java`:
```java
private java.util.Map<String, Integer> columnNameToIndex;

private int findColumnIndex(String columnLabel) throws SQLException {
    if (columnNameToIndex == null) {
        columnNameToIndex = new java.util.HashMap<>(cols.size() * 2);
        for (int i = 0; i < cols.size(); i++) {
            columnNameToIndex.put(cols.get(i).name().toLowerCase(java.util.Locale.ROOT), i + 1);
        }
    }
    Integer idx = columnNameToIndex.get(columnLabel.toLowerCase(java.util.Locale.ROOT));
    if (idx != null) return idx;
    throw new SQLException("Column not found: " + columnLabel);
}
```
Column name lookups dropped from $O(N)$ to $O(1)$.

---

## 7. Bug 7: PreparedStatement Parameter Boxed-Object Overhead

### Symptoms
`PreparedStatement.setObject(...)` and `setInt(...)` previously used `TreeMap<Integer, Value>` or `HashMap<Integer, Value>` to store parameter bindings by 1-based index, causing `Integer` autoboxing and node allocations on every parameter assignment.

### Root Cause
JDBC parameter indices are 1-based sequential integers ($1, 2, \dots, K$). Map structures incur heap overhead, hashing, or tree balancing for consecutive integer keys.

### Resolution
Replaced `Map<Integer, Value>` with a direct zero-based `ArrayList<Value>` in `LibsqlPreparedStatement.java`:
```java
private final List<Value> parameters = new ArrayList<>();

private void setParam(int parameterIndex, Value value) throws SQLException {
    checkClosed();
    int idx = parameterIndex - 1;
    while (parameters.size() <= idx) {
        parameters.add(Value.ofNull());
    }
    parameters.set(idx, value);
}
```
This eliminated `Integer` boxing and map entry allocations on repeated query execution.

---

## 8. Divergence: `SELECT last_insert_rowid()` in Auto-Commit Reads a Fresh Stream

### Behavior

In auto-commit, a direct `select last_insert_rowid()` returns `0` even when the same `Statement` inserted a row immediately before. xerial's `sqlite-jdbc` owns one SQLite connection and returns that insert's rowid instead. Generated keys are unaffected.

### Root cause

Hrana over HTTP has no connection. A stream is identified only by the `baton` in `PipelineReqBody`, and `baton: null` asks the server to create a new stream. That new stream is not the connection that ran the earlier insert. `StmtResult.last_insert_rowid` is a field on one statement result on one stream, so it describes the insert on that stream and nothing else.

The driver's auto-commit path is a one-shot pipeline. It opens a stream with a null baton, executes the statement, and closes the stream in the same round trip. A later `select last_insert_rowid()` is another one-shot pipeline, so it reaches a fresh stream with no prior insert and returns SQLite's default of `0`.

The driver holds no session stream open in auto-commit. Hrana over HTTP closes streams after a short period of inactivity, because the protocol has no connection that would let the server detect an abandoned stream (`vendor/HRANA_3_SPEC.md`, "Hrana over HTTP"), and the lazy transaction begin from #99 removed the last eager stream of that kind. Keeping one open just to serve session-state functions would bring back stale-baton failures on idle connections.

### Supported ways to read the rowid

- Call `Statement.getGeneratedKeys()` after the insert. The driver builds the key from `StmtResult.last_insert_rowid` for `INSERT`, `REPLACE`, and `INSERT` nested under a `WITH` clause, so it needs no extra round trip and is the portable JDBC route.
- Run the insert and `select last_insert_rowid()` inside one explicit transaction. With auto-commit off, the first statement begins the transaction lazily (see Bug 2), the connection then holds the baton, and both statements run on the same stream, so the query sees the insert.

```java
// Generated keys: no extra round trip, works in auto-commit.
statement.executeUpdate("insert into k (v) values ('a')");
ResultSet keys = statement.getGeneratedKeys();
keys.next();
long rowid = keys.getLong(1);

// Direct query: same stream because the explicit transaction holds the baton.
connection.setAutoCommit(false);
statement.executeUpdate("insert into k (v) values ('a')");
ResultSet rowidQuery = statement.executeQuery("select last_insert_rowid()");
rowidQuery.next();
long direct = rowidQuery.getLong(1);
connection.commit();
```

### Resolution

The divergence is documented rather than fixed with a hidden session stream. Generated keys stay sourced from the protocol field, and `LibsqlGeneratedKeysTest#autoCommitInsertReturnsLastInsertRowidFromStmtResult` pins that path to `StmtResult.last_insert_rowid`. Tracked in #83.
