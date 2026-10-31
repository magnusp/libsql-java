# Property-based testing

The unit lanes use [jetCheck](https://github.com/JetBrains/jetCheck) for property-based tests. The
first set of properties is a pilot: it covers the seams the recent refactors extracted, and it stays
in the unit lane. The Appendix B profile and the xerial conformance suite remain the oracles for
type mapping and driver parity.

## Library

The dependency is pinned in the root `pom.xml` and declared test-scoped in `libsql-jdbc` and
`libsql-hrana-codec`:

```xml
<dependency>
    <groupId>org.jetbrains</groupId>
    <artifactId>jetCheck</artifactId>
    <version>0.3.0</version>
    <scope>test</scope>
</dependency>
```

jetCheck is independent of the test framework, so the suites are ordinary JUnit 6 `@Test` methods.
It brings `org.jetbrains:annotations:13.0` onto the test classpath. It never enters a runtime or
shaded artifact.

## Suites

| Suite | Module | Properties | Iterations |
|---|---|---|---|
| `SqlScriptPropertyTest` | `libsql-jdbc` | statement conservation, never throws, no blank statements, split fixpoint, placeholder counting | 500 |
| `SqliteValueTextPropertyTest` | `libsql-jdbc` | REAL, LONG, DOUBLE and ISO date-time text round trips | 1 000 |
| `LibsqlUrlParserPropertyTest` | `libsql-jdbc` | only `SQLException` escapes parsing, metadata redaction idempotence | 500 |
| `SqlMetadataParsingPropertyTest` | `libsql-jdbc` | declared-type parsing and AUTOINCREMENT detection | 500 |
| `ConnectionLifecyclePropertyTest` | `libsql-jdbc` | model-based transaction and savepoint state machine using `ImperativeCommand` | 1 000 scenarios, up to 50 commands |
| `HranaJsonCodecPropertyTest` | `libsql-hrana-codec` | both codecs agree on encode and decode, 64-bit integers, base64 blobs | 1 000 |

The suites add well under a second to the unit lane. They do not start a container.

The statement splitter was the first target because a wrong split silently changes migration
behavior. The generated scripts combine text literals, quoted identifiers, line and block comments,
and trigger bodies that contain inner semicolons. The properties assert that:

- `split` returns exactly the statements that were joined, in order;
- arbitrary text never makes `split` throw;
- every returned statement is non-blank;
- splitting any returned statement again returns that same statement;
- `?` inside quoted text, bracket identifiers, blob literals and comments is not a placeholder.

The lifecycle suite is a small model, not a replay of named examples. The model tracks `closed`,
`autoCommit`, whether a transaction is active, whether a savepoint started it, and the live savepoint
names. jetCheck generates and shrinks the command sequence, and prints the command log when a
scenario fails. The fake client never rejects a statement, so a failure is a driver state-machine
error rather than a server error.

## Running and reproducing

Run the normal unit lane:

```bash
mise exec -- ./mvnw test
```

Run one suite:

```bash
mise exec -- ./mvnw -pl libsql-jdbc -Dtest=SqlScriptPropertyTest test
mise exec -- ./mvnw -pl libsql-hrana-codec -Dtest=HranaJsonCodecPropertyTest test
```

A failure is a `PropertyFalsified` exception. It reports the minimized value, the shrink statistics,
and a `rechecking(...)` token:

```text
Falsified on DECIMAL(a)
Shrunk in 5 stages, by trying 5 examples

To re-run the minimal failing case, run
  PropertyChecker.customized().rechecking("6riN/RX6zqn4DQEBYQ==")
    .forAll(...)
```

Use the token while debugging, then add a named regression test for the minimized value. Do not
commit `withSeed` calls: jetCheck deprecates them on purpose, because a seed becomes obsolete when
the test or the framework changes. For CI, run random seeds and keep the printed token in the
failure report.

The iteration counts are per-suite constants so the unit-lane cost stays visible. A container-lane
property should use a much smaller count, since startup dominates.

## Defects found

### A non-numeric timeout escaped as `NumberFormatException`

`LibsqlUrlParser.parse` is a JDBC boundary. Any input it accepts should produce a config or a
`SQLException`. The property generated a valid URL with an arbitrary `connectTimeout` value and
falsified on an empty value and on overflowing text. `resolveDuration` now wraps the parse failure in
`SQLException` with the parameter name. A named regression test covers both the URL and the
`Properties` path.

A related gap remains: a negative or zero timeout still parses, but
`HttpClient.newBuilder().connectTimeout(...)` rejects it later. That is worth a follow-up property
and a decision on whether the URL parser or the transport should own validation.

### Repeated user info was not redacted completely

`metadataUrlIsIdempotent` falsified on:

```text
jdbc:libsql:http://user:pass@user:pass@
```

The old user-info pattern removed only the first `user:pass@` segment, so a second redaction
removed the next one and the first result still carried a credential. The pattern now consumes
through the last `@` before the path, query or fragment. Named tests cover repeated user info and an
`@` in the query string.

## Comparison with QuickTheories

A second implementation of the same suites used QuickTheories 0.26. Both libraries found the timeout
defect; jetCheck also found the repeated-user-info case. The comparison informed the choice of
jetCheck:

| Dimension | QuickTheories 0.26 | jetCheck 0.3.0 |
|---|---|---|
| Last release | 2019 | 2026 |
| Stateful testing | No first-class support; generate a list and replay it yourself. | `ImperativeCommand` + `checkScenarios`, with command logs and sequence shrinking. |
| Shrinking | Automatic; prints several falsifying values and the seed. | Automatic; re-runs the minimal example and prints a `rechecking(...)` token. |
| Reproducibility | `QT_SEED`, `.withFixedSeed`, reported seed. | `rechecking` token and iteration seed; `withSeed` is deliberately deprecated. |
| Generators | `longs`, arrays, big integers, big decimals, dates, strings over all code points. | No `long` or array generator; char-based strings, so supplementary code points need a custom generator. Has `recursive`, `suchThat`, `noShrink`. |
| Assertions | `checkAssert(Consumer)` is a natural AssertJ fit. | Predicate API; assertions work but need `return true`. |
| JUnit integration | Failures are `AssertionError`, reported as failures. | Failures are `PropertyFalsified` (a `RuntimeException`), reported as errors. |
| Dependencies | Provided `jsr305`; no transitive runtime dependency. | Test-scope `org.jetbrains:annotations:13.0`. |

The stateful support is the decisive difference for this repository. The connection, transaction
and savepoint work has more value in generated command sequences than in more primitive generators.

## Conventions and limits

- Property tests complement the named examples. They do not replace the Appendix B profile or the
  xerial conformance suite.
- Use AssertJ inside the property and finish with `return true`. The assertion failure is caught and
  shrunk by jetCheck.
- Keep generators described and deterministic. Do not depend on outside state.
- Avoid `suchThat` over sparse domains. Build the constrained generator instead.
- jetCheck's string generator is `char`-based. For arbitrary Unicode, generate code points and build
  the string with `appendCodePoint`; do not let automatic shrinking split a surrogate pair. The codec
  suite uses BMP ranges plus fixed supplementary constants.
- The two codecs produce equivalent, not byte-identical, JSON: the direct writer emits supplementary
  characters as raw UTF-8, while Jackson escapes them as a surrogate pair. Compare a canonical token
  stream, not bytes.
- `PropertyFalsified` is a `RuntimeException`. CI fails either way, but reports say "error" rather
  than "failure". Decide once whether to wrap it in `AssertionError` or keep the jetCheck type.
- Keep container-lane iteration counts low. The unit suites are cheap; container startup is not.

## Follow-ups

1. **Xerial differential oracle.** Add a low-iteration property in the container lane. The test needs
   both drivers on the classpath and a temporary SQLite file for xerial. For each generated value or
   statement, run it through both drivers and compare column metadata, Java value classes, values,
   and error types and messages. Appendix B wins over xerial, so the property needs a quarantine
   list shaped like `XerialQuarantine`, keyed by property and generated case, with a reason.
2. **Appendix B matrix.** `JdbcValueConverter` is a pure source-type by target-type table. Encode the
   table from `vendor/JDBC4-TYPE-MAPPING.md`, generate `(value, target type)` pairs, and assert
   either the table's result or `SQLFeatureNotSupportedException`. This belongs in the unit lane and
   is the highest-value next step after the differential oracle.
3. **Lifecycle failure injection.** Add commands for a stale baton, an expired stream, and a server
   error on `BEGIN`, `COMMIT` or `ROLLBACK TO`. jetCheck's command log is the reason to model these
   as `ImperativeCommand`s.
4. **Shared generator helpers.** If more suites need 64-bit values or code-point strings, move the
   small helpers into a test utility rather than copying them.

## Files

- `pom.xml`, `libsql-jdbc/pom.xml`, `libsql-hrana-codec/pom.xml` — dependency management and test
  scope.
- `libsql-jdbc/src/test/java/io/github/magnusp/libsql/jdbc/SqlScriptPropertyTest.java`
- `libsql-jdbc/src/test/java/io/github/magnusp/libsql/jdbc/SqliteValueTextPropertyTest.java`
- `libsql-jdbc/src/test/java/io/github/magnusp/libsql/jdbc/LibsqlUrlParserPropertyTest.java`
- `libsql-jdbc/src/test/java/io/github/magnusp/libsql/jdbc/SqlMetadataParsingPropertyTest.java`
- `libsql-jdbc/src/test/java/io/github/magnusp/libsql/jdbc/ConnectionLifecyclePropertyTest.java`
- `libsql-hrana-codec/src/test/java/io/github/magnusp/libsql/hrana/codec/HranaJsonCodecPropertyTest.java`
- `libsql-jdbc/src/main/java/io/github/magnusp/libsql/jdbc/LibsqlUrlParser.java` — the two fixes the
  properties found.
