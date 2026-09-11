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
├── libsql-hrana-codec/       # Core protocol data structures & DirectHranaJsonCodec
├── libsql-client/            # Low-level HTTP transport, baton state management, pipeline queue
├── libsql-jdbc/              # Standard JDBC 4.3 driver (java.sql.* implementation)
├── libsql-itests/            # Testcontainers (sqld) and Toxiproxy integration tests
└── vendor/                   # Upstream specifications and documentation (Hrana v3, sqld)
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
   - All tests run against containerized `sqld` (`ghcr.io/tursodatabase/libsql-server:latest`).
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

### Running Integration & Toxicity Tests
Requires a running Docker or Podman daemon:
```bash
mise exec -- ./mvnw clean verify
```

To run a specific test:
```bash
mise exec -- ./mvnw -pl libsql-itests -Dtest=HikariCpIntegrationIT test
```

---

## 5. Coding Standards & Conventions

- **Clean Commits**: Use [Conventional Commits](https://www.conventionalcommits.org/) (e.g. `feat(jdbc): ...`, `fix(codec): ...`, `docs: ...`).
- **No Proprietary Information**: Never commit credentials, private tokens, internal corporate urls, or proprietary notices.
- **Dependency Minimization**: Keep runtime dependencies minimal. Client and codec modules should remain lightweight.
- **Error Handling**: Do not swallow exceptions. Map protocol or transport errors into appropriate `SQLException` or `LibsqlException` types.
