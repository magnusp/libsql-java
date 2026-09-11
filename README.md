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
| [`libsql-jdbc`](libsql-jdbc) | Standard JDBC 4.3 driver (`jdbc:libsql:...`) |
| [`libsql-client`](libsql-client) | High-level client API for libSQL queries and streaming |
| [`libsql-hrana-codec`](libsql-hrana-codec) | Core codec implementation for the Hrana 3 protocol |
| [`libsql-itests`](libsql-itests) | Integration tests against `sqld` running in Testcontainers |

## Getting Started

### Maven Dependency

```xml
<dependency>
    <groupId>com.github.magnusp.libsql</groupId>
    <artifactId>libsql-jdbc</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

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

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines and development workflow, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community standards.

## Security

Please report security issues according to our [Security Policy](SECURITY.md).

## License

This project is licensed under the [MIT License](LICENSE).
