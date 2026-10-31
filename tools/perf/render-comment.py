#!/usr/bin/env python3
"""Render the PR performance comment from PerfBenchmark JSON output.

The base and head sides are each measured in several runs. Per metric, this script takes the median
across runs and compares head with base. Throughput drops and allocation increases beyond the
threshold are flagged; the job stays green either way, so reviewers judge the numbers.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

MARKER = "<!-- libsql-perf -->"
DEFAULT_THRESHOLD = 10.0

# metric name, display label, rate column ("rows" or "ops")
WORKLOADS = [
    ("codec.direct.decode5k", "Decode 5k rows (direct)", "rows"),
    ("codec.jackson.decode5k", "Decode 5k rows (Jackson baseline)", "rows"),
    ("codec.direct.decode20k", "Decode 20k rows (direct)", "rows"),
    ("codec.direct.encode", "Encode one execute (direct)", "ops"),
    ("codec.jackson.encode", "Encode one execute (Jackson baseline)", "ops"),
    ("jdbc.scan.all", "JDBC scan + getters (20k rows)", "rows"),
    ("jdbc.scan.object", "JDBC scan + getObject (20k rows)", "rows"),
]


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", nargs="+", type=Path, default=None, help="base JSON files")
    parser.add_argument("--head", nargs="+", type=Path, required=True, help="head JSON files")
    parser.add_argument("--base-sha", default="unknown")
    parser.add_argument("--head-sha", default="unknown")
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    parser.add_argument("--out", type=Path, default=None)
    return parser.parse_args(argv)


def load_runs(paths: list[Path] | None) -> tuple[dict, dict] | tuple[None, None]:
    if not paths:
        return None, {}
    runs = [json.loads(path.read_text()) for path in paths]
    names = set(runs[0]["metrics"])
    metrics = {}
    for name in names:
        metrics[name] = {
            key: statistics.median(run["metrics"][name][key] for run in runs if name in run["metrics"])
            for key in ("opsPerSec", "rowsPerSec", "bytesPerOp")
        }
    return runs[0], metrics


def fmt_rate(value: float, kind: str) -> str:
    unit = "rows/s" if kind == "rows" else "req/s"
    if value >= 1_000_000:
        return f"{value / 1_000_000:.2f}M {unit}"
    if value >= 1_000:
        return f"{value / 1_000:.1f}k {unit}"
    return f"{value:.0f} {unit}"


def fmt_bytes(value: float) -> str:
    if value >= 1_000_000:
        return f"{value / 1_000_000:.2f} MB/op"
    if value >= 1_000:
        return f"{value / 1_000:.2f} kB/op"
    return f"{value:.0f} B/op"


def pct(base: float, head: float) -> float | None:
    if base == 0:
        return None
    return (head - base) / base * 100.0


def fmt_delta(delta: float | None, lower_is_better: bool, threshold: float) -> str:
    if delta is None:
        return "n/a"
    text = f"{delta:+.1f}%"
    regressed = delta > threshold if lower_is_better else delta < -threshold
    if regressed:
        text += " ⚠️"
    return text


def short(sha: str) -> str:
    return sha[:12] if sha and sha != "unknown" else "unknown"


def plural(count: int, word: str) -> str:
    return word if count == 1 else word + "s"


def render(args: argparse.Namespace) -> str:
    base_meta, base_metrics = load_runs(args.base)
    head_meta, head_metrics = load_runs(args.head)
    if head_meta is None:
        raise SystemExit("no head results to render")

    head_runs = len(args.head)
    base_runs = len(args.base) if args.base else 0

    lines = [MARKER, "## Performance benchmark", ""]
    if base_meta is None:
        lines.append(
            f"Head `{short(args.head_sha)}`: absolute numbers only, because the benchmark is not "
            "present in the base commit. Deltas start with the next PR."
        )
    else:
        lines.append(
            f"Base `{short(args.base_sha)}` → head `{short(args.head_sha)}`. "
            f"Medians of {base_runs} base {plural(base_runs, 'run')} and "
            f"{head_runs} head {plural(head_runs, 'run')}, interleaved on the same runner."
        )
    lines.append("")
    lines.append(
        f"Driver-side microbenchmarks only; server and network time are excluded. "
        f"Shared-runner noise is about ±{args.threshold:.0f}%, so only larger regressions are flagged."
    )
    lines.append("")
    lines.append(f"JDK {head_meta.get('javaVersion', '?')}, {head_meta.get('osName', '?')} "
                 f"{head_meta.get('osArch', '?')}, {head_meta.get('availableProcessors', '?')} CPUs.")
    lines.append("")

    lines.append("### Throughput")
    lines.append("")
    lines.append("| Workload | Base | Head | Δ |")
    lines.append("|---|---:|---:|---:|")
    for name, label, kind in WORKLOADS:
        if name not in head_metrics:
            continue
        head_value = head_metrics[name]["rowsPerSec" if kind == "rows" else "opsPerSec"]
        if base_metrics and name in base_metrics:
            base_value = base_metrics[name]["rowsPerSec" if kind == "rows" else "opsPerSec"]
            base_text = fmt_rate(base_value, kind)
            delta = fmt_delta(pct(base_value, head_value), False, args.threshold)
        else:
            base_text = "—"
            delta = "n/a"
        lines.append(f"| {label} | {base_text} | {fmt_rate(head_value, kind)} | {delta} |")
    lines.append("")

    lines.append("### Allocation")
    lines.append("")
    lines.append("| Workload | Base | Head | Δ |")
    lines.append("|---|---:|---:|---:|")
    for name, label, _ in WORKLOADS:
        if name not in head_metrics:
            continue
        head_value = head_metrics[name]["bytesPerOp"]
        if base_metrics and name in base_metrics:
            base_value = base_metrics[name]["bytesPerOp"]
            base_text = fmt_bytes(base_value)
            delta = fmt_delta(pct(base_value, head_value), True, args.threshold)
        else:
            base_text = "—"
            delta = "n/a"
        lines.append(f"| {label} | {base_text} | {fmt_bytes(head_value)} | {delta} |")
    lines.append("")
    lines.append(
        "<sub>Workloads and thresholds live in `tools/perf/render-comment.py`. "
        "Run locally with `./mvnw -pl libsql-perf -am -DskipTests package` and see "
        "[`libsql-perf/README.md`](libsql-perf/README.md).</sub>"
    )
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    comment = render(args)
    if args.out:
        args.out.write_text(comment)
    else:
        sys.stdout.write(comment)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
