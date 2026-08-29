"""Diff two directories of golden results and say whether an answer moved.

`schema_drift.py` catches upstream changing the *shape* of a component: an
attribute added, a default changed, a column moved from static to time-varying.
It cannot catch upstream changing an *answer*. A revised linearisation, a
different default tolerance, a fixed sign somewhere in the objective -- none of
those touch the schema, and all of them move the numbers this port is validated
against.

That gap is not hypothetical in the way an unread schema field is. Every
comparison in this repository is against `goldens/results`, generated once from
the pinned PyPSA. If upstream's answer for `unit-commitment` moves from 57,000 to
57,300, nothing says so until someone bumps the pin, and then 741 tests go red at
once with no indication of which change caused it.

So the drift workflow generates results from the newest PyPSA into a scratch
directory and hands both to this.

    python3 reference/results_drift.py reference/goldens/results new-results

Pure stdlib, like `schema_drift.py` and for the same two reasons: it runs on a
checkout with no virtualenv, and it can be tested without installing PyPSA at
all.

Exit status:

    0   nothing moved beyond tolerance
    1   something did, or the two sides disagree structurally
    2   bad usage

==What counts as drift==

Three kinds, and they are not equally interesting:

  structural  A network, section, key, column or row present on one side and not
              the other, or a frame that changed shape. Reported first and
              always fails: a missing frame is upstream having stopped producing
              something this port reads.
  numeric     A number that moved by more than the tolerance below. The point of
              the exercise.
  categorical A string or boolean that changed -- `condition` going from
              "optimal" to anything else, a `converged` flag flipping. Always
              fails, with no tolerance to apply.

==On tolerance==

The expected worry is that the goldens were generated on one machine and the
comparison runs on another, so some of any difference is the floating-point
environment rather than upstream.

Measured, and it is not: regenerating all 22 networks with the *pinned* PyPSA
1.3.0 on macOS/aarch64 -- a different machine and a different architecture from
the one that wrote the goldens -- reproduces every number in every frame
**exactly**. Not within tolerance; identical. At a tolerance of zero the
comparison reports nothing.

So the 1e-9 relative default with a 1e-12 floor is headroom rather than a
measured noise floor, and saying so matters: it is there because one machine pair
agreeing bit-for-bit is not a promise that every future HiGHS or BLAS build will,
and a last-bit difference is not worth waking anyone for. It also sits at the
tightest assertion any suite here makes -- `NewtonRaphsonSuite` compares voltages
at 1e-9 -- so a move large enough to be reported is a move large enough to fail a
test on a pin bump, which is the question worth answering.

If a clean re-run against the pinned version ever does trip it, that is worth
knowing on its own: it means the reproducibility measured above has stopped
holding, and the tolerance is the least interesting part of the news.
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

DEFAULT_RELATIVE = 1e-9
DEFAULT_ABSOLUTE = 1e-12

# Enough to see the shape of a drift without pasting a 852-bus frame into a job
# summary. The count of what was withheld is always printed -- a silent truncation
# reads as "that was all of it".
MAX_REPORTED = 40


class Drift:
    """One difference, with enough context to find it again."""

    def __init__(self, path: str, kind: str, old, new, relative: float | None = None) -> None:
        self.path = path
        self.kind = kind
        self.old = old
        self.new = new
        self.relative = relative

    def __str__(self) -> str:
        if self.kind == "structural":
            return f"{self.path}: {self.old} -> {self.new}"
        if self.relative is None:
            return f"{self.path}: {self.old!r} -> {self.new!r}"
        return f"{self.path}: {self.old!r} -> {self.new!r}  (relative {self.relative:.3e})"


def relative_change(old: float, new: float) -> float:
    """Change scaled so a value near zero does not make the ratio meaningless.

    The same scaling `ValidationLadder.relativeGap` uses on the Scala side, and
    for the same reason: dividing by an objective that is legitimately 0.0 gives
    infinity for a difference of 1e-18.
    """
    return abs(new - old) / max(1.0, abs(old))


def compare_number(path: str, old, new, rel_tol: float, abs_tol: float) -> list[Drift]:
    # NaN is a value here rather than an error: `lpf` records NaN by construction
    # for a link flow it does not determine, and two NaNs agreeing is agreement.
    old_nan = isinstance(old, float) and math.isnan(old)
    new_nan = isinstance(new, float) and math.isnan(new)
    if old_nan or new_nan:
        if old_nan and new_nan:
            return []
        return [Drift(path, "numeric", old, new)]

    if old == new:
        return []
    if abs(new - old) <= abs_tol:
        return []
    rel = relative_change(float(old), float(new))
    if rel <= rel_tol:
        return []
    return [Drift(path, "numeric", old, new, rel)]


def compare_frame(path: str, old: dict, new: dict, rel_tol: float, abs_tol: float) -> list[Drift]:
    """Compare a `{columns, values}` frame cell by cell.

    Columns are compared as a list rather than a set: order is what the row
    tuples are indexed by, so a reordering is a structural change even though
    every name survives.
    """
    if old["columns"] != new["columns"]:
        return [Drift(f"{path}.columns", "structural", old["columns"], new["columns"])]
    if len(old["values"]) != len(new["values"]):
        return [Drift(
            f"{path}.values", "structural",
            f"{len(old['values'])} rows", f"{len(new['values'])} rows",
        )]

    out: list[Drift] = []
    columns = old["columns"]
    for r, (old_row, new_row) in enumerate(zip(old["values"], new["values"])):
        if len(old_row) != len(new_row):
            out.append(Drift(
                f"{path}.values[{r}]", "structural",
                f"{len(old_row)} cells", f"{len(new_row)} cells",
            ))
            continue
        for c, (a, b) in enumerate(zip(old_row, new_row)):
            name = columns[c] if c < len(columns) else c
            out.extend(compare_value(f"{path}[{r}][{name}]", a, b, rel_tol, abs_tol))
    return out


def is_frame(value) -> bool:
    return isinstance(value, dict) and {"columns", "values"} <= set(value)


def tagged_nan(value):
    """`generate_goldens.py` writes NaN as `{"$nan": true}`, which JSON can hold."""
    if isinstance(value, dict) and set(value) == {"$nan"}:
        return math.nan
    return value


def compare_value(path: str, old, new, rel_tol: float, abs_tol: float) -> list[Drift]:
    old = tagged_nan(old)
    new = tagged_nan(new)

    if is_frame(old) != is_frame(new):
        return [Drift(path, "structural", type_name(old), type_name(new))]
    if is_frame(old):
        return compare_frame(path, old, new, rel_tol, abs_tol)

    if isinstance(old, dict) and isinstance(new, dict):
        out: list[Drift] = []
        for key in sorted(set(old) | set(new)):
            if key not in old:
                out.append(Drift(f"{path}.{key}", "structural", "absent", "present"))
            elif key not in new:
                out.append(Drift(f"{path}.{key}", "structural", "present", "absent"))
            else:
                out.extend(compare_value(f"{path}.{key}", old[key], new[key], rel_tol, abs_tol))
        return out

    if isinstance(old, list) and isinstance(new, list):
        if len(old) != len(new):
            return [Drift(path, "structural", f"{len(old)} items", f"{len(new)} items")]
        out = []
        for i, (a, b) in enumerate(zip(old, new)):
            out.extend(compare_value(f"{path}[{i}]", a, b, rel_tol, abs_tol))
        return out

    # bool before number: `True == 1` in Python, so a `converged` flag flipping
    # to 1.0 would otherwise compare equal and pass as no change.
    if isinstance(old, bool) or isinstance(new, bool):
        return [] if old is new else [Drift(path, "categorical", old, new)]

    if isinstance(old, (int, float)) and isinstance(new, (int, float)):
        return compare_number(path, old, new, rel_tol, abs_tol)

    if old != new:
        return [Drift(path, "categorical", old, new)]
    return []


def type_name(value) -> str:
    if is_frame(value):
        return "frame"
    return type(value).__name__


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def compare_directories(old_dir: Path, new_dir: Path, rel_tol: float, abs_tol: float):
    """Compare every `<network>.json` present in both, and name those that are not.

    A network missing from the new side is reported rather than skipped. Upstream
    dropping an example network is exactly the kind of thing that should reach a
    human, and a comparison that quietly iterated over the intersection would
    report "no drift" for a directory that had lost half its contents.
    """
    old_names = {p.stem for p in old_dir.glob("*.json")}
    new_names = {p.stem for p in new_dir.glob("*.json")}

    missing = sorted(old_names - new_names)
    added = sorted(new_names - old_names)

    drifts: list[Drift] = []
    for name in sorted(old_names & new_names):
        drifts.extend(compare_value(
            name, load(old_dir / f"{name}.json"), load(new_dir / f"{name}.json"), rel_tol, abs_tol,
        ))
    return drifts, missing, added, sorted(old_names & new_names)


def report(drifts, missing, added, compared, rel_tol, out, err) -> int:
    if not drifts and not missing and not added:
        print(
            f"{len(compared)} networks compared at relative tolerance {rel_tol:.0e}: "
            "every result is unchanged",
            file=out,
        )
        return 0

    print("## Results drift", file=out)
    print(file=out)

    for name in missing:
        print(f"- **{name}** is missing from the new results entirely", file=out)
    for name in added:
        print(f"- **{name}** is new and has no pinned result to compare", file=out)
    if missing or added:
        print(file=out)

    by_kind = {"structural": [], "categorical": [], "numeric": []}
    for drift in drifts:
        by_kind[drift.kind].append(drift)

    for kind, heading in (
        ("structural", "Structural — upstream changed the shape of a result"),
        ("categorical", "Categorical — a status or flag changed"),
        ("numeric", f"Numeric — moved by more than {rel_tol:.0e} relative"),
    ):
        found = by_kind[kind]
        if not found:
            continue
        print(f"### {heading} ({len(found)})", file=out)
        print(file=out)
        for drift in found[:MAX_REPORTED]:
            print(f"- `{drift}`", file=out)
        if len(found) > MAX_REPORTED:
            print(f"- …and {len(found) - MAX_REPORTED} more not listed", file=out)
        print(file=out)

    networks = sorted({str(d.path).split(".")[0] for d in drifts})
    print(f"{len(drifts)} differences across {len(networks)} networks: {', '.join(networks)}", file=err)
    return 1


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: results_drift.py OLD_RESULTS_DIR NEW_RESULTS_DIR", file=sys.stderr)
        return 2
    old_dir, new_dir = Path(argv[0]), Path(argv[1])
    for directory in (old_dir, new_dir):
        if not directory.is_dir():
            print(f"not a directory: {directory}", file=sys.stderr)
            return 2

    drifts, missing, added, compared = compare_directories(
        old_dir, new_dir, DEFAULT_RELATIVE, DEFAULT_ABSOLUTE
    )
    if not compared and not missing and not added:
        print(f"no result files in {old_dir} or {new_dir}", file=sys.stderr)
        return 2
    return report(drifts, missing, added, compared, DEFAULT_RELATIVE, sys.stdout, sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
