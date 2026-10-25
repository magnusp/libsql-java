# Migrating to the `io.github.magnusp.libsql` groupId

Starting with `0.2.0`, the artifacts are published under `io.github.magnusp.libsql`. Versions
`0.1.x` were published only to GitHub Packages under `com.github.magnusp.libsql`.

Artifacts are published to GitHub Packages. No Maven `-SNAPSHOT` versions are published; `main`
builds use `0.2.0-rc.<shortsha>` and releases use the tag version. See
[Publishing](publishing.md) for the repository and authentication setup.

The artifact names, public types, and JDBC URLs did not change.

## Coordinates

| Old | New |
|---|---|
| `com.github.magnusp.libsql:libsql-jdbc` | `io.github.magnusp.libsql:libsql-jdbc` |
| `com.github.magnusp.libsql:libsql-client` | `io.github.magnusp.libsql:libsql-client` |
| `com.github.magnusp.libsql:libsql-hrana-codec` | `io.github.magnusp.libsql:libsql-hrana-codec` |

## Maven

```xml
<dependency>
    <groupId>io.github.magnusp.libsql</groupId>
    <artifactId>libsql-jdbc</artifactId>
    <version>0.2.0</version>
</dependency>
```

## Gradle

```kotlin
implementation("io.github.magnusp.libsql:libsql-jdbc:0.2.0")
```

## Imports

Change the package prefix and keep the rest of the import:

```java
// before
import com.github.magnusp.libsql.jdbc.LibsqlDriver;
import com.github.magnusp.libsql.client.LibsqlClient;

// after
import io.github.magnusp.libsql.jdbc.LibsqlDriver;
import io.github.magnusp.libsql.client.LibsqlClient;
```

## What did not change

- Artifact IDs: `libsql-jdbc`, `libsql-client`, `libsql-hrana-codec`.
- Public type names: `LibsqlDriver`, `LibsqlConnection`, `LibsqlClient`, `LibsqlException`, ...
- JDBC URLs: `jdbc:libsql:...` remains canonical, and `jdbc:sqlite:...` remains an alias.
- Driver behavior and the `META-INF/services/java.sql.Driver` registration.

## GitHub Packages consumers

GitHub Packages hosts the artifacts. The `0.1.x` versions used the old groupId; `0.2.0` and later
use `io.github.magnusp.libsql`. GitHub Packages requires authentication even for public packages,
so configure the `github` server in `~/.m2/settings.xml`; see [Publishing](publishing.md).

## No compatibility artifact

The old groupId is not republished. A shim would only preserve an obsolete coordinate; update the
groupId and imports instead.
