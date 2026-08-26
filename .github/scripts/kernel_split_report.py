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

# Operations every coverage delegates verbatim. `controls_for` widens this for
# the default coverage, which delegates three more.
ALWAYS_DELEGATED = {"spmv", "copy"}

MARKER = re.compile(r"=== pass (\S+) backend (\S+) (?:lanes|MaxVectorSize) (\S+) ===")
RUN = re.compile(r"backend: (\S+)")
ITERS = re.compile(r"iterations (\d+)")
# Kernel calls scale with line-search trials, not with iterations: `Pdhg.step`
# calls `primalStep`, `dot` and `squaredNorm` once per trial. An arm with a
# higher rejection rate therefore does more kernel work per iteration, and
# charging that to the backend as slowness is the confound this figure exists to
# expose.
TRIALS = re.compile(r"line-search trials (\d+)")
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
    # `-XX:MaxVectorSize` bounds C2's auto-vectorisation as well as the Vector
    # API, so a width-limited run is comparable only to another at the same
    # width. The reference backend's name does not carry the setting -- it is
    # `scala-reference` either way -- so it is taken from the marker the job
    # prints before each run, and comparisons are made within a width.
    width = "default"
    for line in open(path, encoding="utf-8", errors="replace"):
        if m := MARKER.search(line):
            width = m.group(3)
        if m := RUN.search(line):
            current = {"backend": m.group(1), "ops": {}, "iterations": None,
                       "trials": None, "wall": None, "width": width}
            runs.append(current)
        if current is None:
            continue
        if m := ITERS.search(line):
            current["iterations"] = int(m.group(1))
        if m := TRIALS.search(line):
            current["trials"] = int(m.group(1))
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


def per_call(rows: list[dict], op: str) -> float | None:
    """Mean time for one call of `op`, normalised by that arm's own call count.

    The whole point, and what the first version got wrong: it multiplied each
    arm's *total* by the reference's call count, so the vector arm's totals were
    weighted by counts that were not theirs and never divided by their own. With
    per-call times a, b and a uniform count ratio f = Cb/Ca, that expression
    equals (1/f) x the true ratio -- so a 25% difference in call counts moved the
    "drift" by 20%, and the correction it fed was measuring the iteration
    confound it exists to remove.
    """
    # Microseconds. The harness prints the total in ms and the count beside it,
    # so the quotient is ms/call and was displayed under a "us" heading.
    total_ms = mean([r["ops"][op][0] for r in rows if op in r["ops"]])
    calls = mean([float(r["ops"][op][1]) for r in rows if op in r["ops"]])
    return 1000.0 * total_ms / calls if total_ms is not None and calls else None


def controls_for(backend: str, ref: list[dict], rs: list[dict]) -> list[str]:
    """Operations that are byte-identical code in both arms.

    `spmv` and `copy` are delegated under every coverage. The default coverage
    also delegates `axpby`, `scale` and `dualStep`, and `axpby` alone carries
    more calls than `copy` by two orders of magnitude -- so excluding them left
    the estimate resting almost entirely on `spmv`. The `-all` suffix is what
    distinguishes the coverages.
    """
    shared = set(ALWAYS_DELEGATED)
    if not backend.endswith("-all"):
        shared |= {"axpby", "scale", "dualStep"}
    # Presence is not enough: `Instrumented.report()` prints all eight names
    # unconditionally, so an operation the solve never reached appears as
    # `0.0 ms / 0 calls`. `scale` is only used on the averaging path, so a short
    # solve on a small network produces exactly that -- and `per_call` returns
    # `None` for it, which used to reach an unguarded multiply.
    def usable(o: str) -> bool:
        if not all(o in r["ops"] for r in ref + rs):
            return False
        return per_call(ref, o) not in (None, 0.0) and per_call(rs, o) not in (None, 0.0)

    return sorted(o for o in shared if usable(o))


def report(runs: list[dict]) -> list[str]:
    out: list[str] = []
    by = defaultdict(list)
    for r in runs:
        by[(r["width"], r["backend"])].append(r)

    out.append("### Per backend\n")
    out.append("| backend | MaxVectorSize | runs | wall clock | in kernels | iterations |")
    out.append("| --- | --- | --- | --- | --- | --- |")
    for (width, name), rs in by.items():
        walls = [r["wall"] for r in rs if r["wall"]]
        kerns = [r["kernels"] for r in rs if r.get("kernels")]
        iters = {r["iterations"] for r in rs if r["iterations"]}
        out.append(
            f"| `{name}` | {width} | {len(rs)} | {fmt(mean(walls), ' ms')} | "
            f"{fmt(mean(kerns), ' ms')} | {', '.join(str(i) for i in sorted(iters)) or 'n/a'} |"
        )

    for (width, name), rs in by.items():
        if name == "scala-reference":
            continue
        ref = by.get((width, "scala-reference"))
        if not ref:
            out.append(f"\n### `{name}` at MaxVectorSize {width}\n")
            out.append("- No reference run at this width, so there is nothing to compare "
                       "against. `-XX:MaxVectorSize` bounds C2's auto-vectorisation too, so "
                       "dividing by a full-width scalar run would mix the width change with "
                       "crippling SuperWord on the other arm.")
            continue
        out.append(f"\n### `{name}` against the reference at MaxVectorSize {width}\n")
        if len(ref) < 2 or len(rs) < 2:
            out.append(f"- **Single-run reading** ({len(ref)} reference, {len(rs)} vector). "
                       "No spread, and nothing counterbalanced within this width.")

        # Trials per iteration, per arm. This is the quantity the acceptance test
        # moves: it runs on `dot` and `squaredNorm`, which the vector backend
        # reassociates, so a backend can converge in the same iterations while
        # rejecting more steps -- doing strictly more kernel work per iteration
        # for reasons that are not its speed.
        def trials_per_iter(rows: list[dict]) -> float | None:
            return mean([r["trials"] / r["iterations"]
                         for r in rows if r.get("trials") and r.get("iterations")])

        tr, tv = trials_per_iter(ref), trials_per_iter(rs)
        if tr and tv:
            out.append(f"- Line-search trials per iteration: reference {tr:.3f}, "
                       f"`{name}` {tv:.3f}.")
            if abs(tv / tr - 1) > 0.01:
                # Symmetric, because the quantity is signed: the guard fires for
                # *fewer* trials too, and the previous wording then said "-3.2%
                # more trials ... is more kernel work charged as slowness",
                # which is backwards twice. Both ratios are named, since the
                # wall-clock one is the figure this report calls end-to-end.
                out.append(
                    f"  **Both per-iteration ratios below are confounded by this**: kernel calls "
                    f"scale with trials, so `{name}` does {100 * tv / tr:.1f}% of the reference's "
                    "kernel work per iteration for a reason that is not its speed, and the ratios "
                    "charge that to the backend."
                )

        ri = {r["iterations"] for r in ref if r["iterations"]}
        vi = {r["iterations"] for r in rs if r["iterations"]}
        if ri and vi and ri != vi:
            pc = 100 * (mean(list(vi)) / mean(list(ri)) - 1)
            out.append(
                f"- Iteration counts differ: {sorted(ri)} against {sorted(vi)} "
                f"({pc:+.1f}%). Ratios below are per iteration for that reason, and the "
                "end-to-end effect is the wall-clock ratio times that difference."
            )

        def per_iter(rows: list[dict], key: str) -> float | None:
            return mean([r[key] / r["iterations"]
                         for r in rows if r.get(key) and r["iterations"]])

        def ratio(key: str) -> float | None:
            a, b = per_iter(ref, key), per_iter(rs, key)
            return a / b if a and b else None

        wall_ratio, kern_ratio = ratio("wall"), ratio("kernels")

        shared = controls_for(name, ref, rs)
        drift = None
        if shared:
            weights = {o: mean([float(r["ops"][o][1]) for r in ref]) for o in shared}
            ref_t = sum(per_call(ref, o) * weights[o] for o in shared)
            vec_t = sum(per_call(rs, o) * weights[o] for o in shared)
            drift = ref_t / vec_t if vec_t else None
            each = {o: per_call(ref, o) / per_call(rs, o) for o in shared}
            spread = f"{min(each.values()):.3f}–{max(each.values()):.3f}"
            listed = ", ".join(f"`{o}` {v:.3f}" for o, v in sorted(each.items()))
            out.append(f"- Controls (identical code in both): {listed}.")
            if drift:
                width_of_spread = max(each.values()) - min(each.values())
                # A correction that is essentially the identity cannot be
                # unusable: with drift at 1.000 the `2 * abs(drift - 1)` term is
                # zero, so any spread at all condemned a correction that changes
                # nothing -- and blanked the per-operation table with it.
                correction = abs(drift - 1.0)
                usable_drift = (correction < 0.005
                                or width_of_spread <= 2 * correction
                                or width_of_spread < 0.02)
                out.append(
                    f"- Time-weighted drift **{drift:.3f}**, controls spanning {spread} — "
                    "**below** 1.0 means the machine was slower during the "
                    f"`{name}` runs."
                )
                if not usable_drift:
                    out.append(
                        f"  The controls disagree by {width_of_spread:.3f} against a correction of "
                        f"{abs(drift - 1.0):.3f}, so **the correction is not usable** and the raw "
                        "ratios below are the reading. Judged rather than asserted in prose beside "
                        "a bold corrected number, which is how one was published and withdrawn."
                    )
                    drift = None

        for label, r in (("Wall clock", wall_ratio), ("Time inside `Kernels`", kern_ratio)):
            if r is None:
                out.append(f"- {label}: n/a (the log did not carry both figures).")
            elif drift:
                out.append(f"- {label}: **{r:.2f}x** raw, **{r / drift:.2f}x** drift-corrected.")
            else:
                out.append(f"- {label}: **{r:.2f}x**.")

        share = mean([r["kernels"] / r["wall"] for r in ref if r.get("wall") and r.get("kernels")])
        if share is not None and share >= 0.9:
            out.append(f"- The timed operations are {100 * share:.0f}% of the reference solve, so "
                       "the two ratios should agree closely; a gap between them is time spent "
                       "outside `Kernels`.")
        elif share is not None:
            out.append(f"- The timed operations are only {100 * share:.0f}% of the reference "
                       "solve, so the kernel ratio is not the end-to-end figure — the wall-clock "
                       "ratio is, and the kernel one bounds it.")

        # Per operation, computed here rather than by hand from the raw block.
        # Every previously published per-operation figure was derived in a
        # notebook, which is the practice this script exists to end.
        # The corrected column exists only when there is a correction to put in
        # it. Emitting the heading with a column of dashes advertises a quantity
        # the run did not produce, which is the shape of every figure this
        # section has had to withdraw.
        ops = sorted({o for r in ref + rs for o in r["ops"]})
        rows = []
        for o in ops:
            a, b = per_call(ref, o), per_call(rs, o)
            if not a or not b:
                continue
            raw = a / b
            cell = f" {raw / drift:.2f}x |" if drift else ""
            rows.append(f"| `{o}` | {a:.1f} µs | {b:.1f} µs | {raw:.2f}x |{cell}")
        if rows:
            head = "| operation | reference | this backend | raw |"
            rule = "| --- | --- | --- | --- |"
            if drift:
                head += " drift-corrected |"
                rule += " --- |"
            out.append("\n" + head)
            out.append(rule)
            out.extend(rows)
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
