Here is an agent-executable, phase-by-phase implementation and verification plan designed for automated or assisted development. It starts with the core protocol codec, grounds early progress in containerized integration tests, and progresses toward fault injection and network poisoning.

agent friendly plan going from the hrana v3 spec to implement a jdbc driver for sqld/sqlite. It should include early integration tests, then move on to network and connectivity tests (network poisoning)

---

### Architecture Overview & Module Boundaries

Organize the repository to decouple the Hrana transport layer from the JDBC API facades:

```text
my-libsql-driver/
├── libsql-hrana-codec/       # Pure protocol data structures & JSON/Protobuf codec
├── libsql-client/            # Network transport, Baton/Stream management, pipeline queue
├── libsql-jdbc/              # Standard java.sql.* implementations
└── libsql-itests/            # Testcontainers, upstream compatibility, and Toxiproxy suites

```

---

### Phase 1: Protocol Framing & Client Transport (Hrana v3)

Implement the protocol core first, without touching the JDBC interfaces.

* **1.1. Schema & Serialization Model**
* Model Hrana v3 primitives (`Value`, `ValueType`, `Batch`, `BatchCond`, `Stmt`, `StmtResult`, `Col`, `Row`).
* Implement serialization for the JSON and/or Protobuf wire formats.
* Ensure full 64-bit integer handling (`int64` via Java `long`) and base64/raw BLOB encodings.


* **1.2. HTTP Pipeline & Baton State Machine**
* Implement an HTTP/1.1 or HTTP/2 client over `java.net.http.HttpClient`.
* Create the pipeline request/response loop targeting `/v3/pipeline`.
* Track and update the interactive session token (`baton`).
* Implement the explicit stream close handshake (`{"type": "close"}`).


* **1.3. WebSocket Transport (Optional/Alternative)**
* Implement the framing handshake: `hello`, `open_stream`, `close_stream`, and response correlations via request IDs.



---

### Phase 2: Early Integration Testing (Minimal Viable Engine)

Before writing JDBC wrappers, verify the transport against a live `sqld` container using Testcontainers.

* **2.1. Testcontainer Infrastructure**
* Spin up `ghcr.io/tursodatabase/sqld:latest` exposing default ports (`8080` HTTP, `5432` pgwire).
* Enable token authentication in test configurations to verify bearer auth handling.


* **2.2. Protocol Assertion Suite**
* **Sanity Test:** Execute `SELECT 1` on `/v3/pipeline` without a baton (non-interactive request).
* **Interactive Stream Lifecycle:**
1. Send `BEGIN` on `/v3/pipeline`; assert a `baton` is returned.
2. Send `CREATE TABLE t (id INT, val TEXT);` with the returned baton.
3. Send `INSERT INTO t VALUES (1, 'hello');` with the updated baton.
4. Send `COMMIT` and close the stream.
5. Execute a fresh non-interactive `SELECT * FROM t`; assert persistence.


* **Server-Side Expiration Handling:** Hold a baton past `sqld`'s server timeout; assert the client surfaces a structured protocol expiration exception rather than a generic JSON parse error.



---

### Phase 3: JDBC Contract Implementation

Map the tested Hrana client onto `java.sql.*` interfaces.

* **3.1. Connection Management (`LibsqlConnection`)**
* URL format: `jdbc:libsql://host:port?authToken=...&tls=true`.
* State tracking: `autoCommit` (default `true`), `readOnly`, `transactionIsolation` (`TRANSACTION_SERIALIZABLE` or `READ_COMMITTED`).
* Transaction mapping:
* `setAutoCommit(false)` $\rightarrow$ allocate interactive stream/baton, issue `BEGIN`.
* `commit()` $\rightarrow$ issue `COMMIT`, retain stream or close based on mode.
* `rollback()` $\rightarrow$ issue `ROLLBACK`.
* `setSavepoint(name)` $\rightarrow$ issue `SAVEPOINT <name>`.


* Implement `isValid(timeout)` using a lightweight pipeline ping.


* **3.2. Statement & PreparedStatement (`LibsqlPreparedStatement`)**
* Implement parameter binding (`setLong`, `setDouble`, `setString`, `setBytes`, `setNull`).
* Translate SQLite dynamic types into `ResultSet.getObject(col, targetClass)`.
* Batching: Aggregate calls in `addBatch()` and translate them into a single Hrana `batch` request in `executeBatch()`.


* **3.3. Error Mapping**
* Map Hrana error payloads and SQLite extended error codes (e.g., `SQLITE_CONSTRAINT_PRIMARYKEY`) to standard JDBC `SQLState` values and specific `SQLException` subclasses (`SQLIntegrityConstraintViolationException`, `SQLTransientException`).


* **3.4. Minimal `DatabaseMetaData**`
* Implement `getTables()`, `getColumns()`, `getPrimaryKeys()`, and `getIndexInfo()` querying SQLite system catalogs (`sqlite_master`, `pragma_table_info`).



---

### Phase 4: Upstream & Ecosystem Compatibility

Verify against real JDBC workloads.

* **4.1. Synced `xerial/sqlite-jdbc` Suite**
* Run the test synchronization pipeline to test standard statement execution, metadata, and type edge cases.
* Exclude tests expecting JNI internals or custom C extensions.


* **4.2. Ecosystem Smoke Tests**
* **HikariCP:** Run pool acquisition churn tests with high thread concurrency. Verify zero baton/connection leaks on checkout/return.
* **jOOQ / Flyway:** Execute baseline DDL migrations and batch inserts.
* **Hibernate:** Validate that entity mapping and schema inspection (`hbm2ddl.auto=validate`) pass without metadata exceptions.



---

### Phase 5: Network Poisoning & Fault Tolerance Testing

Validate that the driver behaves deterministically when network transport fails or exhibits high latency, using **Toxiproxy** via Testcontainers.

* **5.1. Toxic Proxy Topology**
```text
[ Driver / Test Suite ] ---> [ Toxiproxy Container ] ---> [ sqld Container ]

```


* **5.2. Core Network Poisoning Scenarios**

| Scenario | Toxic Configuration | Injected Fault | Expected Driver Behavior |
| --- | --- | --- | --- |
| **Silent Connection Drop** | `reset_peer` | TCP socket abruptly severed mid-query | Driver throws `SQLTransientConnectionException` or `SQLRecoverableException`; does not hang indefinitely. |
| **High Latency / Frozen Socket** | `latency` (e.g., 5000ms) | Artificial delay exceeding `DriverManager.setLoginTimeout` or `Statement.setQueryTimeout` | Driver honors socket timeout / `queryTimeout`, cancels request context, and closes poisoned stream. |
| **Bandwidth Throttling & Slicing** | `slicer` + `bandwidth` | Chunks HTTP/WS frames into tiny 1-byte packets | Verifies buffering logic; parser should not throw partial-read frame errors. |
| **Half-Open TCP State** | `timeout` (toxic drop without FIN/RST) | Server stops responding during active transaction | Connection pool validation (`isValid()`) marks socket dead and purges it without pool-wide deadlock. |
| **HTTP Intermittent 502/503** | Mock server / reverse proxy fault | Injected gateway errors between driver and `sqld` | For autocommit read-only queries, verify whether idempotent retries trigger; for open transactions, verify immediate safe abort without replaying mutations. |

* **5.3. Baton Desynchronization Test**
* Introduce latency on pipeline responses. Send an asynchronous query cancellation or statement timeout while a request is in flight.
* Assert that the driver rejects subsequent queries on that dirty connection rather than sending commands with a stale, out-of-sequence baton.



---

### Execution Checklist for Implementation

* [ ] **Phase 1:** Model `Value`, `Stmt`, `Batch`, and HTTP pipeline JSON framing in `libsql-hrana-codec`.
* [ ] **Phase 2:** Launch `sqld` container; verify non-interactive query, interactive transaction cycle, and stream close via raw client.
* [ ] **Phase 3.1:** Implement `Driver`, `LibsqlConnection`, and baton state machine for autocommit/transactions.
* [ ] **Phase 3.2:** Implement `LibsqlStatement`, `LibsqlPreparedStatement`, parameter encoding, and `ResultSet`.
* [ ] **Phase 3.3:** Map Hrana error codes to standard JDBC `SQLState`s.
* [ ] **Phase 3.4:** Implement table and column metadata queries using SQLite PRAGMAs.
* [ ] **Phase 4:** Run synced Xerial test suite, followed by HikariCP pool churn tests.
* [ ] **Phase 5:** Wire up Toxiproxy container; assert proper failure behavior on `reset_peer`, latency timeouts, and severed streams.
