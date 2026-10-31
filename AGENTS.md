# Contributor & Agent Guidelines

Welcome to `libsql-java`. This repository implements a pure-Java JDBC driver, client library, and protocol codec for libSQL / sqld over the Hrana protocol.

This guide provides developers and automated AI agents with instructions on architecture, tooling, build conventions, and verification workflows.

---

## 1. Environment & Tooling Prerequisites

- **Java Version**: Java LTS.
- **Environment Management**: Tooling is managed via [mise](https://mise.jdx.dev/) (`mise.toml`).
- **Build System**: Maven with the included wrapper (`./mvnw`).
- **Container Runtime**: Docker or Podman (with socket exposed) for Testcontainers.

### Setup Commands
```bash
# Ensure correct Java LTS environment
mise install

# Verify Java & Maven
mise exec -- java -version
mise exec -- ./mvnw -version
```

---

## 2. Architecture & Module Structure

```text
libsql-java/
├── libsql-hrana-codec/         # Core protocol data structures & DirectHranaJsonCodec
├── libsql-client/              # Low-level HTTP transport, baton state management, pipeline queue
├── libsql-jdbc/                # Standard JDBC 4.3 driver (java.sql.* implementation)
├── libsql-itests/              # Testcontainers (sqld) and Toxiproxy integration tests
├── libsql-conformance/         # Selected xerial/sqlite-jdbc tests running against libSQL
├── libsql-perf/                # Driver-side codec/JDBC benchmark posted on pull requests
├── tools/                      # Maintainer scripts such as export-upstream
├── upstream-xerial_sqlite-jdbc/  # Vendored xerial/sqlite-jdbc sources for conformance tests
└── vendor/                     # Upstream specifications and documentation (Hrana v3, sqld)
```

### Module Guidelines
1. **`libsql-hrana-codec`**:
   - Zero external runtime dependencies.
   - `DirectHranaJsonCodec` handles streaming JSON encoding/decoding directly for maximum performance.
   - Preserves 64-bit integer precision and binary base64 encoding.

2. **`libsql-client`**:
   - Built on `java.net.http.HttpClient` with pooled connection reuse.
   - Manages baton lifecycle, `/v3/pipeline` execution, and stream close handshakes.

3. **`libsql-jdbc`**:
   - Standards-compliant JDBC 4.3 implementation.
   - Maps URL schemes: `jdbc:libsql:http://...`, `jdbc:libsql:https://...`, and `jdbc:sqlite:http://...`.
   - Maps SQLite dynamic typing to JDBC SQL types and translates Hrana error codes to `SQLException` hierarchies.

4. **`libsql-itests`**:
   - All tests run against a pinned `sqld` image (`ghcr.io/tursodatabase/libsql-server:v0.24.33`).
   - The pin lives in the root `pom.xml` as the `libsql.image` property, and Maven passes it to both the integration and conformance test JVMs. Override it with `-Dlibsql.image=...` only for a deliberate version bump.
   - Includes HikariCP connection pool churn tests and Toxiproxy network toxicity tests.

---

## 3. Code Style & Spotless Formatting

We enforce code hygiene using **Spotless**:
- **Format check**: `mise exec -- ./mvnw spotless:check`
- **Apply format**: `mise exec -- ./mvnw spotless:apply`

CI will fail if files contain trailing whitespace, missing newline terminations, or formatting violations. Run `spotless:apply` before submitting changes.

---

## 4. Testing & Verification Workflows

### Running Unit Tests
```bash
mise exec -- ./mvnw test
```

### Property-Based Tests

The unit lanes use jetCheck for the extracted pure seams and the connection transaction lifecycle.
A failure prints the minimized value and a `rechecking(...)` token. Use the token while debugging,
then add a named regression test for the minimized case; do not commit a fixed seed. See
[`docs/property-based-testing.md`](docs/property-based-testing.md) for the suite list, conventions,
and the xerial and Appendix B follow-ups.

### Running Integration & Toxicity Tests
Requires a running Docker or Podman daemon:
```bash
mise exec -- ./mvnw clean verify
```

To run a specific test:
```bash
mise exec -- ./mvnw -pl libsql-itests -Dtest=HikariCpIntegrationIT test
```

Under nono, set `DOCKER_CONFIG` before the command, as described in the workaround below.

### Performance Benchmark

`libsql-perf` measures codec decode/encode and the JDBC result-set getters without a server. The
[`Performance`](.github/workflows/perf.yml) workflow builds the PR merge commit and its base
commit, runs three interleaved rounds, and posts a sticky comparison comment. The job flags
regressions over 10% but does not fail the check.

```bash
mise exec -- ./mvnw -pl libsql-perf -am -DskipTests package
mise exec -- java -cp 'libsql-perf/target/classes:libsql-perf/target/lib/*' \
  io.github.magnusp.libsql.perf.PerfBenchmark --quick
```

See [`libsql-perf/README.md`](libsql-perf/README.md) for the workloads and how to change them. The
benchmark payload is a measured baseline: change it in a dedicated PR and call it out, so the first
comment after the change is not read as a regression.

### nono Sandbox and Docker Config

This environment may run inside a `nono` filesystem sandbox. The sandbox can block reads of `~/.docker/config.json` even when `docker info` works. Testcontainers then fails with:

```text
ServiceConfigurationError: ... EnvironmentAndSystemPropertyClientProviderStrategy could not be instantiated
Caused by: java.io.FileNotFoundException: /home/<user>/.docker/config.json (Permission denied)
```

Check the exact path with:

```bash
nono why --self --path ~/.docker/config.json --op read
```

Do not change file permissions or the nono profile from inside the sandbox.

The repository ships one shared readable Docker config at `libsql-conformance/src/test/docker-config/config.json`. It is an empty auth config, so it works only for public images such as the `sqld` and Toxiproxy images that the integration tests use. Both modules can use it.

`libsql-conformance` uses the config automatically. It copies `src/test/docker-config/config.json` to `target/docker-config` and sets `DOCKER_CONFIG` for the Failsafe fork. To use a readable copy of your real Docker config instead, override the module property:

```bash
./mvnw -pl libsql-conformance -am -Ddocker.config.dir=/path/to/docker-config verify
```

Setting `DOCKER_CONFIG` in the shell is not enough for `libsql-conformance`, because its Failsafe fork receives its own value from `docker.config.dir`.

`libsql-itests` has no copy step. Point `DOCKER_CONFIG` at the shared config directory in the shell, and the Failsafe fork inherits it:

```bash
DOCKER_CONFIG="$PWD/libsql-conformance/src/test/docker-config" \
  ./mvnw -pl libsql-itests -am verify
```

The same override works for a single integration test:

```bash
DOCKER_CONFIG="$PWD/libsql-conformance/src/test/docker-config" \
  ./mvnw -pl libsql-itests -am -Dit.test=HikariCpIntegrationIT verify
```

Use the absolute path shown. A relative `DOCKER_CONFIG` is resolved against the fork's working directory, not the shell's.

### JDBC 4.3 Appendix B policy

JDBC 4.3 Appendix B is the oracle for type mapping. The source is [`vendor/JDBC4-TYPE-MAPPING.md`](vendor/JDBC4-TYPE-MAPPING.md).

- When Appendix B defines a conversion or mapping and sqlite-jdbc disagrees, Appendix B wins. A conformance test that encodes the xerial behavior is quarantined in `libsql-conformance`, not copied.
- Keep sqlite-jdbc parity only where Appendix B is silent. Parity never overrides a defined Appendix B conversion.
- Table B-6 is a required-support matrix, not a rejection list. A representable declared column is not rejected because of its declared type; the stored value decides the conversion, matching sqlite-jdbc. Reject a declared type only for the five N/A types. Unimplemented getters still throw for any column.
- `ARRAY`, `STRUCT`, `REF`, `JAVA_OBJECT`, and `DISTINCT` are N/A. The driver throws `SQLFeatureNotSupportedException` and does not emulate them.
- An expression column has no declared type. Its storage-class fallback is a documented exception in [`docs/jdbc-appendix-b-compliance.md`](docs/jdbc-appendix-b-compliance.md), not a supported Appendix B row.

The compliance profile is [`docs/jdbc-appendix-b-compliance.md`](docs/jdbc-appendix-b-compliance.md). Run the commands in [`README.md`](README.md) before changing a type mapping.

---

## 5. Coding Standards & Conventions

- **Clean Commits**: Use [Conventional Commits](https://www.conventionalcommits.org/) (e.g. `feat(jdbc): ...`, `fix(codec): ...`, `docs: ...`).
- **No Proprietary Information**: Never commit credentials, private tokens, internal corporate urls, or proprietary notices.
- **Dependency Minimization**: Keep runtime dependencies minimal. Client and codec modules should remain lightweight.
- **Error Handling**: Do not swallow exceptions. Map protocol or transport errors into appropriate `SQLException` or `LibsqlException` types.
