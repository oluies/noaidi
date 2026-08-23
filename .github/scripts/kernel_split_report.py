#!/usr/bin/env python3
"""Turn a `KernelSplit` log into a comparison that states its own basis.

Every number this prints was previously computed inline in `ci.yml`, or by hand
in a notebook and pasted into NOTES. Three reviews in a row found the same class
of problem with that: a ratio whose quantity was not stated, a drift correction
whose estimator was not written down, and an iteration-count confound that was
described in prose and implemented nowhere.

So the analysis lives here, where it can be read and tested.

Three things it does that dividing two totals does not:

  - **Says which quantity.** Wall clock and time-inside-`Kernels` are different
    numbers and both are printed by the harness. A ratio of one labelled as the
    other is the error that propagated through three commits.
  - **Normalises by iteration count.** The widened reductions reassociate, so
    the two backends can converge in a different number of steps. Dividing raw
    totals charges that difference to the backend as if it were speed.
  - **Estimates drift from the operations that cannot have changed.** Several
    operations are byte-identical across backends because the vector backend
    delegates them. Any ratio they show is the machine, not the code -- and the
    estimator is time-weighted, because an unweighted mean lets `scale` at 0.1%
    of the total move the correction as much as `spmv` at 40%.
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict

# Operations the vector backends delegate verbatim under at least one coverage.
# Their cross-backend ratio is machine state by construction.
CONTROLS = {"spmv", "copy"}

RUN = re.compile(r"backend: (\S+)")
ITERS = re.compile(r"iterations (\d+)")
WALL = re.compile(r"wall clock ([\d.,]+) ms, in kernels ([\d.,]+) ms")
OP = re.compile(r"^(?:\[info\] )?\s+([a-zA-Z]+) +([\d.,]+) ms +[\d.,]+% +(\d+) calls")


def number(text: str) -> float:
    """A float from output that may use either decimal separator.

    The harness formats through `f"..."`, which follows the JVM's default locale
    -- so the same build prints `5709.7` on a CI runner and `5709,7` on a machine
    set to a European locale. Parsing only the first silently drops every digit
    after the separator on the second.
    """
    return float(text.replace(",", "."))


def parse(path: str) -> list[dict]:
    runs: list[dict] = []
    current: dict | None = None
    for line in open(path, encoding="utf-8", errors="replace"):
        if m := RUN.search(line):
            current = {"backend": m.group(1), "ops": {}, "iterations": None, "wall": None}
            runs.append(current)
        if current is None:
            continue
        if m := ITERS.search(line):
            current["iterations"] = int(m.group(1))
        if m := WALL.search(line):
            current["wall"], current["kernels"] = number(m.group(1)), number(m.group(2))
        if m := OP.match(line.rstrip("\n")):
            current["ops"][m.group(1)] = (number(m.group(2)), int(m.group(3)))
    return [r for r in runs if r["ops"]]


def mean(xs: list[float]) -> float | None:
    """None for an empty sample rather than a `ZeroDivisionError`.

    A log can legitimately be missing a line -- a run killed mid-solve, a format
    that moved -- and a reporting script that dies on that turns a benchmark into
    a failed step. Callers render `None` as `n/a`.
    """
    return sum(xs) / len(xs) if xs else None


def fmt(x: float | None, unit: str = "") -> str:
    return "n/a" if x is None else f"{x:.0f}{unit}"


def report(runs: list[dict]) -> list[str]:
    out: list[str] = []
    by = defaultdict(list)
    for r in runs:
        by[r["backend"]].append(r)

    out.append("### Per backend\n")
    out.append("| backend | runs | wall clock | in kernels | iterations |")
    out.append("| --- | --- | --- | --- | --- |")
    for name, rs in by.items():
        walls = [r["wall"] for r in rs if r["wall"]]
        kerns = [r["kernels"] for r in rs if r.get("kernels")]
        iters = {r["iterations"] for r in rs if r["iterations"]}
        out.append(
            f"| `{name}` | {len(rs)} | {fmt(mean(walls), ' ms')} | {fmt(mean(kerns), ' ms')} | "
            f"{', '.join(str(i) for i in sorted(iters)) or 'n/a'} |"
        )

    ref = next((rs for n, rs in by.items() if n == "scala-reference"), None)
    if not ref:
        out.append("\nNo `scala-reference` runs: nothing to compare against.")
        return out

    for name, rs in by.items():
        if name == "scala-reference":
            continue
        out.append(f"\n### `{name}` against the reference\n")

        # Iteration confound, stated before any ratio is shown.
        ri = {r["iterations"] for r in ref if r["iterations"]}
        vi = {r["iterations"] for r in rs if r["iterations"]}
        if ri and vi and ri != vi:
            drift_pc = 100 * (mean(list(vi)) / mean(list(ri)) - 1)
            out.append(
                f"- Iteration counts differ: {sorted(ri)} against {sorted(vi)} "
                f"({drift_pc:+.1f}%). Ratios below are per iteration for that reason."
            )

        def per_iter(rows: list[dict], key: str) -> float | None:
            return mean([r[key] / r["iterations"]
                         for r in rows if r.get(key) and r["iterations"]])

        def ratio(key: str) -> float | None:
            a, b = per_iter(ref, key), per_iter(rs, key)
            return a / b if a and b else None

        wall_ratio, kern_ratio = ratio("wall"), ratio("kernels")

        # Drift, time-weighted over the operations that are the same code.
        shared = [o for o in CONTROLS if all(o in r["ops"] for r in ref + rs)]
        drift = None
        if shared:
            ref_t = sum(mean([r["ops"][o][0] for r in ref]) * mean([r["ops"][o][1] for r in ref])
                        for o in shared)
            vec_t = sum(mean([r["ops"][o][0] for r in rs]) * mean([r["ops"][o][1] for r in ref])
                        for o in shared)
            drift = ref_t / vec_t
            each = ", ".join(
                f"`{o}` {mean([r['ops'][o][0] for r in ref]) / mean([r['ops'][o][0] for r in rs]):.3f}"
                for o in sorted(shared)
            )
            out.append(
                f"- Control operations (identical code in both): {each}. "
                f"Time-weighted drift **{drift:.3f}** — above 1.0 means the machine was "
                f"slower during the `{name}` runs."
            )

        for label, r in (("Wall clock", wall_ratio), ("Time inside `Kernels`", kern_ratio)):
            if r is None:
                out.append(f"- {label}: n/a (the log did not carry both figures).")
            elif drift:
                out.append(f"- {label}: **{r:.2f}x** raw, **{r / drift:.2f}x** drift-corrected.")
            else:
                out.append(f"- {label}: **{r:.2f}x**.")
        share = mean([r["kernels"] / r["wall"] for r in ref if r.get("wall") and r.get("kernels")])
        if share is not None:
            out.append(f"- The timed operations are {100 * share:.0f}% of the reference solve, so "
                       "the two ratios should agree closely; a gap between them is time spent "
                       "outside `Kernels`.")
    return out


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: kernel_split_report.py <split.log>", file=sys.stderr)
        return 2
    runs = parse(sys.argv[1])
    if not runs:
        print("No runs found in the log.")
        return 0
    print("\n".join(report(runs)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
