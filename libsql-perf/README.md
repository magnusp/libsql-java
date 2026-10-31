# libsql-perf

Driver-side performance benchmark for the Hrana JSON codec and the JDBC result-set getters.

The [`Performance`](../.github/workflows/perf.yml) workflow runs it for every pull request, compares
the PR merge commit with the base commit, and posts the result as a sticky comment. The job is
informational: regressions are flagged in the comment, not failed in the check.

## What it measures

| Workload | What it exercises |
|---|---|
| `codec.direct.decode5k` | `DirectHranaJsonCodec` decoding a 5,000-row, six-column `execute` result |
| `codec.jackson.decode5k` | The optional Jackson codec on the same payload, as a baseline |
| `codec.direct.decode20k` | The direct codec on a 20,000-row result |
| `codec.direct.encode` | The direct codec serializing one parameterized execute |
| `codec.jackson.encode` | The Jackson codec on the same request, as a baseline |
| `jdbc.scan.all` | `LibsqlResultSet.next()` plus typed getters over 20,000 rows |
| `jdbc.scan.object` | `ResultSet.getObject()` over all six columns of 20,000 rows |

Every workload reports operations per second, rows per second (where applicable), and allocated
bytes per operation from `java.lang.management.ThreadMXBean`. The benchmark takes the median of
several measurement windows and prints Markdown, or writes JSON for the PR comment.

The workload is deliberately driver-side. Starting `sqld` would add server and network time that
dominates the codec and result-set costs the benchmark is meant to track. The integration tests in
`libsql-itests` cover end-to-end throughput.

## Running it locally

```bash
./mvnw -pl libsql-perf -am -DskipTests package
java -cp 'libsql-perf/target/classes:libsql-perf/target/lib/*' \
  io.github.magnusp.libsql.perf.PerfBenchmark
```

Useful flags:

| Flag | Effect |
|---|---|
| `--quick` | Shorter warmup and fewer, shorter windows for a rough check |
| `--json <path>` | Write machine-readable results |
| `--commit <sha>` | Set the commit recorded in the JSON output |
| `--quiet` | Do not print the Markdown summary |

To see the same comparison the workflow posts, run the base and head revisions and render them:

```bash
./tools/perf/render-comment.py \
  --base base.json --head head.json \
  --base-sha <base-sha> --head-sha <head-sha>
```

## Reading the comparison

The workflow checks out the base commit and the PR merge commit, builds both, then runs three
interleaved rounds per side on the same runner. The comment reports medians across those rounds.
Throughput drops and allocation increases larger than 10% are marked with a warning; shared-runner
noise below that band is not meaningful.

The comparison is against the PR base, not a historical record. To follow a metric across releases,
compare the comments or the `perf-results` artifact attached to each run.

## Changing the benchmark

Adding a workload means adding a `measure(...)` call in `PerfBenchmark` and a matching entry in
`WORKLOADS` in [`tools/perf/render-comment.py`](../tools/perf/render-comment.py). Changing the
payload, warmup, or window size changes the baseline; call that out in the PR description so the
first comment after the change is not read as a regression.
