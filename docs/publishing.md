# Publishing

Artifacts are published to GitHub Packages only. Maven Central is not used.

## Target

- **Repository:** `https://maven.pkg.github.com/magnusp/libsql-java`
- **Repository id:** `github`
- **GroupId:** `io.github.magnusp.libsql`
- **ArtifactIds:** `libsql-jdbc`, `libsql-client`, `libsql-hrana-codec`, plus the parent POM.
  `libsql-itests` and `libsql-conformance` set `maven.deploy.skip` and are not published.

The `github` id matches the server that `actions/setup-java` configures from `GITHUB_ACTOR` and
`GITHUB_TOKEN` in `.github/workflows/publish.yml`.

## Versioning

The build uses [Maven CI-friendly versions](https://maven.apache.org/guides/mini/guide-maven-ci-friendly.html):
`<version>${revision}${sha1}${changelist}</version>`. The defaults live in the root `pom.xml`.

| Build | Trigger or command | Resolved version |
|---|---|---|
| Local development | `./mvnw clean install` | `0.2.0-dev` |
| `main` prerelease | push to `main` | `0.2.0-<12-char-sha>` |
| Release | tag `v0.2.0` | `0.2.0` |
| Release candidate | tag `v0.2.0-rc.1` | `0.2.0-rc.1` |
| Custom | `./mvnw -Drevision=0.3.0 -Dsha1= -Dchangelist= clean install` | `0.3.0` |

`${sha1}` carries the short commit hash for branch builds and is empty for tag builds.
`${changelist}` defaults to `-dev` locally; CI clears it.

### No Maven SNAPSHOT versions

`-SNAPSHOT` is never produced or published. GitHub Packages for Maven is immutable, so a repeated
`-SNAPSHOT` publish would fail or silently confuse consumers. Every published version is a
concrete prerelease or release.

A side effect of immutability: a given commit maps to one `0.2.0-<sha>` version. Re-running the
publish workflow for the same commit attempts to republish the same version and will be rejected
by GitHub Packages. Publish a new commit or a tag instead.

## Workflow

`.github/workflows/publish.yml` runs on pushes to `main` and on `v*` tags:

1. Tag build: the version is the tag name without the leading `v`.
2. Branch build: the base `revision` is read from the POM and suffixed with the 12-character commit
   SHA.
3. The workflow refuses any resolved version containing `-SNAPSHOT`.
4. `./mvnw -Prelease clean deploy` publishes the main, sources, and javadoc artifacts.

## Release process

1. Merge to `main`. The workflow publishes `0.2.0-<sha>`.
2. Tag the commit and push the tag:
   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```
3. The workflow publishes `0.2.0`.
4. Bump `revision` in the root `pom.xml` for the next development cycle.

## Consuming

GitHub Packages requires authentication even for public packages.

Add the repository:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/magnusp/libsql-java</url>
    </repository>
</repositories>
```

Add a server to `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>github</id>
        <username>YOUR_GITHUB_USERNAME</username>
        <password>${env.GITHUB_TOKEN}</password>
    </server>
</servers>
```

The token needs `read:packages` to consume. Publishing uses the workflow's `GITHUB_TOKEN` with
`packages: write`.

Then depend on:

```xml
<dependency>
    <groupId>io.github.magnusp.libsql</groupId>
    <artifactId>libsql-jdbc</artifactId>
    <version>0.2.0</version>
</dependency>
```

Use a `0.2.0-<sha>` version to consume a specific `main` build.
