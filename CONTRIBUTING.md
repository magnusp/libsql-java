# Contributing to libsql-java

Thank you for your interest in contributing to `libsql-java`! This document outlines guidelines and recommendations to make contributing smooth and enjoyable.

## Code of Conduct

All contributors and participants are expected to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting Started

### Prerequisites

- **Java**: Java LTS.
- **Tooling**: We use [mise](https://mise.jdx.dev/) for managing tool environments (`mise.toml`), and the Maven Wrapper (`./mvnw`).
- **Docker / Podman**: Required for integration tests running Testcontainers (`sqld` / libSQL instance).

To set up your environment with mise:
```bash
mise install
mise exec -- ./mvnw -version
```

### Building the Project

Compile and run local tests:
```bash
mise exec -- ./mvnw clean test
```

Run integration tests (requires Docker/Podman):
```bash
mise exec -- ./mvnw clean verify
```

## Project Structure

The repository is organized as a multi-module Maven project:
- **`libsql-hrana-codec`**: Pure Java protocol implementation (encodes and decodes Hrana 3 protocol frames).
- **`libsql-client`**: High-level reactive / asynchronous libSQL client.
- **`libsql-jdbc`**: Standard JDBC 4.3 compliant driver implementation (`jdbc:libsql:...`).
- **`libsql-itests`**: End-to-end integration tests using Testcontainers and sqld.

## Code Style & Quality

- **Formatting**: We use [Spotless](https://github.com/diffplug/spotless) with Google Java Format.
- Format code before committing:
  ```bash
  mise exec -- ./mvnw spotless:apply
  ```
- Verify formatting:
  ```bash
  mise exec -- ./mvnw spotless:check
  ```

## Development Workflow

1. **Fork & Branch**: Create a feature or fix branch from `main` (e.g. `feat/batch-pipeline`, `fix/null-handling`).
2. **Write Tests**: Ensure any bug fixes or new features are accompanied by tests.
3. **Commit Messages**: Use clear, concise commit messages (prefer [Conventional Commits](https://www.conventionalcommits.org/)).
4. **Run Checks**: Verify that `./mvnw clean verify` and `./mvnw spotless:check` pass locally.
5. **Open a Pull Request**: Submit your PR with a clear summary of changes and reference any related issues.

## Reporting Bugs & Suggesting Features

- Use our [Issue Templates](https://github.com/magnusp/libsql-java/issues/new/choose) to report bugs or suggest enhancements.
- For security vulnerabilities, refer to [SECURITY.md](SECURITY.md).
