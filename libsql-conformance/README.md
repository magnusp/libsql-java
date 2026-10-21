# libSQL xerial Conformance Tests

This module runs a selected subset of the xerial `sqlite-jdbc` test sources against
libSQL over the Hrana protocol. It is a compatibility check for the JDBC driver, not a
claim that a `jdbc:sqlite:` URL is served by a local SQLite file.

## URL mapping

The conformance driver maps `jdbc:sqlite:` URLs to sqld namespaces so the vendored
tests can run unchanged:

| URL | Namespace |
|---|---|
| `jdbc:sqlite:` | A new in-memory namespace per connection |
| `jdbc:sqlite::memory:` | A new in-memory namespace per connection |
| `jdbc:sqlite:<path>` | One namespace derived from `<path>` |

A file path is resolved against the process working directory and normalized before
it is hashed. A relative path and the equivalent absolute path therefore address the
same namespace; for example, `data/example.db` and `/work/data/example.db` match when
the process working directory is `/work`. Normalization resolves `.` and `..` but does
not resolve symbolic links.

The query string is ignored when deriving the namespace, so URL pragmas such as
`?journal_mode=WAL` have no effect on the conformance server. No local SQLite file is
created; the data lives in the sqld namespace.

## Persistence

A path namespace is retained until the suite shuts down, so closing every connection
and reopening the same path observes the data written earlier. The retained namespaces
are deleted during suite shutdown and the shared sqld container is stopped. In-memory
namespaces are not retained.

The conformance copy of an upstream test diverges from xerial where Hrana connection
lifecycle makes the original behavior impossible; each divergence is recorded in
`../upstream-xerial_sqlite-jdbc.manifest`.
