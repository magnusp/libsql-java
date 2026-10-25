# Project setup: `io.github.magnusp.libsql`

- **Status:** Executed and verified
- **Owner:** @magnusp
- **Date:** 2026-10-25
- **Publishing target:** GitHub Packages only

This records the final namespace, versioning, and publishing setup. The earlier Maven Central
attempt was abandoned; see [publishing.md](publishing.md) for the operational details.

## 1. Names and coordinates

| Item | Value |
|---|---|
| Maven groupId | `io.github.magnusp.libsql` |
| Java packages | `io.github.magnusp.libsql.*` |
| Artifacts | `libsql-java-parent`, `libsql-hrana-codec`, `libsql-client`, `libsql-jdbc`, `libsql-itests`, `libsql-conformance` |
| Repository | `github.com/magnusp/libsql-java` |
| Public types | `LibsqlDriver`, `LibsqlConnection`, `LibsqlClient`, `LibsqlException`, ... |
| JDBC URLs | `jdbc:libsql:` (canonical), `jdbc:sqlite:` (alias) |

`io.github.magnusp` is the verified Maven Central namespace. Maven Central namespaces are
recursive, so `io.github.magnusp.libsql` is valid beneath it. The groupId and package root now
match, and the artifactIds carry the project name.

```xml
<dependency>
    <groupId>io.github.magnusp.libsql</groupId>
    <artifactId>libsql-jdbc</artifactId>
    <version>0.2.0</version>
</dependency>
```

## 2. Versioning

The build uses Maven CI-friendly versions:

```xml
<version>${revision}${sha1}${changelist}</version>
```

Defaults in the root `pom.xml`: `revision=0.2.0`, empty `sha1`, `changelist=-dev`.

| Build | Version |
|---|---|
| Local `./mvnw clean install` | `0.2.0-dev` |
| Push to `main` | `0.2.0-<12-char-sha>` |
| Tag `v0.2.0` | `0.2.0` |
| Tag `v0.2.0-rc.1` | `0.2.0-rc.1` |

No Maven `-SNAPSHOT` version is produced or published. The `changelist` placeholder carries
`-dev` locally and is cleared in CI; the `${sha1}` placeholder carries the commit suffix for branch
builds. The flatten plugin resolves the placeholders in installed and deployed POMs.

## 3. Publishing

`.github/workflows/publish.yml` publishes to GitHub Packages:

- Push to `main`: `0.2.0-<12-char-sha>`.
- Push a `v*` tag: the tag version without the leading `v`.
- Any resolved `-SNAPSHOT` version is rejected before deployment.
- The workflow uses `GITHUB_TOKEN` with `packages: write`; no GPG key, Central Portal account, or
  external credentials are involved.

The root POM points `distributionManagement` at
`https://maven.pkg.github.com/magnusp/libsql-java` with repository id `github`. The `central`
profile, GPG plugin, and Central publishing plugin have been removed. The `release` profile
remains to attach sources and javadoc. `libsql-itests` and `libsql-conformance` set
`maven.deploy.skip`, so only the parent POM and the three library modules are published.

GitHub Packages Maven is immutable. A rerun for the same commit resolves to the same
`0.2.0-<sha>` version and is rejected rather than overwritten. Publish a new commit or a tag
instead. Consumption requires GitHub authentication even for public packages; see
[publishing.md](publishing.md).

## 4. Changes applied

- GroupId `com.github.magnusp.libsql` → `io.github.magnusp.libsql`.
- Java packages `com.github.magnusp.libsql.*` → `io.github.magnusp.libsql.*`, moved with `git mv`.
- Proto `java_package`, shade relocations, JDBC service file, and JUnit service files updated.
- The xerial-control exclude path updated to `**/io/github/magnusp/**`.
- `revision`, `sha1`, and `changelist` wired into the project version; the flatten plugin is
  configured for `resolveCiFriendliesOnly`.
- GitHub Packages publishing restored for SHA prereleases and tags; Maven Central publishing
  removed.
- `README.md`, `CONTRIBUTING.md`, and `docs/migration-from-libsql-java.md` updated.
- `docs/publishing.md` added as the publishing and versioning reference.

## 5. Verification

| Gate | Result |
|---|---|
| `clean test` | 1,510 JDBC + 35 codec unit tests |
| `libsql-itests -am verify` | 191 integration tests |
| `-Pxerial-control verify` | 249 tests, 2 skipped |
| `clean verify` | 191 integration + 270 conformance tests |
| `-Prelease` package | main, sources, and javadoc jars |
| Consumer smoke test | resolves `io.github.magnusp.libsql:libsql-jdbc`, loads `LibsqlDriver` via `ServiceLoader`, accepts `jdbc:libsql:` and `jdbc:sqlite:` |
| Version resolution | `0.2.0-dev`, `0.2.0-abc1234`, `0.2.0`, and a custom `0.3.0` |

The first full-suite run hit a transient RootlessKit port conflict on the Toxiproxy container; the
isolated test and the subsequent full run passed.

## 6. Notes

- GitHub Packages Maven is immutable: do not rebuild and republish the same version.
- No `-SNAPSHOT` in POMs, workflows, or examples. Use a SHA prerelease or a tag.
- The groupId and package root are now identical; do not reintroduce a `com.github` prefix.
- `.idea/workspace.xml` is ignored and may retain old package names until the IDE reimports.

## 7. Definition of done

- [x] GroupId is `io.github.magnusp.libsql` in every POM.
- [x] Java packages start with `io.github.magnusp.libsql`.
- [x] ArtifactIds, repository name, public types, and JDBC schemes are unchanged.
- [x] No tracked file contains `com.github.magnusp`, except the migration document.
- [x] No `-SNAPSHOT` version is produced or published.
- [x] GitHub Packages workflow publishes SHA prereleases and tag releases.
- [x] The Maven Central profile and Central publishing plugin are removed.
- [x] `./mvnw clean verify` passes.
- [x] Consumer smoke test passes against `io.github.magnusp.libsql:libsql-jdbc`.
- [x] `docs/publishing.md` documents the target, versions, and consumer setup.
