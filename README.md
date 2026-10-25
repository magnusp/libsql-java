# libsql-java

[![CI & Integration Tests](https://github.com/magnusp/libsql-java/actions/workflows/ci.yml/badge.svg)](https://github.com/magnusp/libsql-java/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java LTS](https://img.shields.io/badge/Java-LTS-orange.svg)](https://openjdk.org/)

A pure-Java JDBC driver, client library, and protocol codec for **libSQL** and **sqld** databases over the Hrana protocol.

---

## Features

- **Standard JDBC Driver**: Drop-in `java.sql` compliance (`jdbc:libsql:...`) for applications, connection pools (HikariCP), and ORMs.
- **Pure Java**: No JNI, C-bindings, or native dependencies required.
- **Hrana 3 Protocol Support**: High-performance binary and JSON protocol codecs for libSQL/sqld.
- **Lightweight & Fast**: Built for modern Java (LTS).
- **Batteries Included**: Multi-module architecture providing fine-grained dependencies.

## Modules

| Module | Description |
|---|---|
| [`libsql-hrana-codec`](libsql-hrana-codec) | Core codec implementation for the Hrana 3 protocol |
| [`libsql-client`](libsql-client) | High-level client API for libSQL queries and streaming |
| [`libsql-jdbc`](libsql-jdbc) | Standard JDBC 4.3 driver (`jdbc:libsql:...`) |
| [`libsql-itests`](libsql-itests) | Integration tests against `sqld` running in Testcontainers |
| [`libsql-conformance`](libsql-conformance/README.md) | Selected xerial/sqlite-jdbc tests running against libSQL |

## Getting Started

### Maven Dependency

Artifacts are hosted on GitHub Packages. Add the repository and configure credentials as
described in [Publishing](docs/publishing.md).

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/magnusp/libsql-java</url>
    </repository>
</repositories>
```

```xml
<dependency>
    <groupId>io.github.magnusp.libsql</groupId>
    <artifactId>libsql-jdbc</artifactId>
    <version>0.2.0</version>
</dependency>
```

Prerelease builds from `main` use `0.2.0-rc.<shortsha>`. No `-SNAPSHOT` versions are published.

### JDBC Usage Example

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class Example {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:libsql:http://localhost:8080";

        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 AS col")) {

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    System.out.println("Result: " + rs.getInt("col"));
                }
            }
        }
    }
}
```

## Development & Building

### Prerequisites

- **Java**: Java LTS
- **Tooling**: Managed via [mise](https://mise.jdx.dev/) and Maven Wrapper (`./mvnw`)
- **Docker**: For running integration tests against `sqld`

### Commands

```bash
# Compile and run unit tests
mise exec -- ./mvnw clean test

# Run full integration test suite (requires Docker)
mise exec -- ./mvnw clean verify

# Format code with Spotless (Google Java Format)
mise exec -- ./mvnw spotless:apply
```

### JDBC 4.3 Appendix B checks

Run the Appendix B unit suite.

```bash
./mvnw -q -pl libsql-jdbc -Dtest='Jdbc4AppendixB*' -Dsurefire.failIfNoSpecifiedTests=false test
```

Run the full `libsql-jdbc` unit suite.

```bash
./mvnw -q -pl libsql-jdbc test
```

Run the Appendix B server suite. The install step puts the current driver in the local repository. Under the nono sandbox, the Docker config path is required.

```bash
./mvnw -q -DskipTests install -pl libsql-hrana-codec,libsql-client,libsql-jdbc
DOCKER_CONFIG="$PWD/libsql-conformance/src/test/docker-config" ./mvnw -pl libsql-itests -Dit.test=Jdbc4AppendixBTypeMappingIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

Run the xerial conformance suite.

```bash
./mvnw -pl libsql-conformance -am verify
```

Run the full gate.

```bash
DOCKER_CONFIG="$PWD/libsql-conformance/src/test/docker-config" ./mvnw clean verify
```

The compliance profile is in [`docs/jdbc-appendix-b-compliance.md`](docs/jdbc-appendix-b-compliance.md).

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines and development workflow, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community standards.

## Security

Please report security issues according to our [Security Policy](SECURITY.md).

## License

This project is licensed under the [MIT License](LICENSE).
