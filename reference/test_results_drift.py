#!/usr/bin/env python3
"""Tests for `results_drift.py`.

Same reason as `test_schema_drift.py`, and it applies harder here: this script's
failure mode is reporting "every result is unchanged" while an objective moved
underneath it, and the whole value of the weekly run is that a green result is
taken as evidence nothing happened upstream.

So most of what follows checks that it *fires*, and the rest checks the two ways
it could fire wrongly -- on floating-point noise, and on a NaN that was NaN
before.

Plain asserts and a `__main__`, matching the other checkers here and keeping this
runnable on a bare checkout, which is the point of `results_drift.py` being
stdlib-only.

    python3 reference/test_results_drift.py
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from results_drift import (  # noqa: E402
    DEFAULT_ABSOLUTE,
    DEFAULT_RELATIVE,
    compare_value,
    main,
)


def frame(columns: list[str], values: list[list]) -> dict:
    return {"columns": columns, "values": values}


BASE = {
    "optimize": {
        "objective": 17000.0,
        "condition": "optimal",
        "generator_p": frame(["base", "peak"], [[100.0, 0.0], [80.0, 20.0]]),
        "nominal_opt": {"base": 200.0},
    },
    "pf": {
        "converged": frame(["bus"], [[True]]),
        "bus_v_mag_pu": frame(["bus"], [[1.0]]),
    },
}


def diff(old, new) -> list:
    """Compare two result bodies at the shipped tolerances."""
    return compare_value("net", old, new, DEFAULT_RELATIVE, DEFAULT_ABSOLUTE)


def edited(**sections) -> dict:
    """`BASE` with one or more sections deep-replaced."""
    out = json.loads(json.dumps(BASE))
    for section, changes in sections.items():
        out[section].update(changes)
    return out


def run(old: dict[str, dict], new: dict[str, dict]) -> tuple[int, str]:
    """Run `main` over two throwaway directories, returning (code, output)."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        old_dir, new_dir = root / "old", root / "new"
        for path, bodies in ((old_dir, old), (new_dir, new)):
            path.mkdir()
            for name, body in bodies.items():
                (path / f"{name}.json").write_text(json.dumps(body), encoding="utf-8")

        out, err = io.StringIO(), io.StringIO()
        real_out, real_err = sys.stdout, sys.stderr
        sys.stdout, sys.stderr = out, err
        try:
            code = main([str(old_dir), str(new_dir)])
        finally:
            sys.stdout, sys.stderr = real_out, real_err
        return code, out.getvalue() + err.getvalue()


def unit_cases() -> list[str]:
    problems = []

    def check(name: str, got: list, expect_count: int, expect_kind: str | None = None):
        if len(got) != expect_count:
            problems.append(f"{name}: got {len(got)} differences, expected {expect_count} -- {[str(g) for g in got]}")
            return
        if expect_kind and got and got[0].kind != expect_kind:
            problems.append(f"{name}: got kind {got[0].kind!r}, expected {expect_kind!r}")

    # Identical input is the case that must never report anything, because it is
    # the case that runs every week.
    check("identical", diff(BASE, BASE), 0)

    # An objective that moved. The reason the script exists.
    check("objective moved", diff(BASE, edited(optimize={"objective": 17300.0})), 1, "numeric")

    # Below tolerance: the floating-point environment differing between the
    # machine that generated the goldens and the one re-running them.
    check("noise ignored", diff(BASE, edited(optimize={"objective": 17000.0 * (1 + 1e-13)})), 0)

    # Just above it. If this stops firing the tolerance has been loosened.
    check("just above tolerance", diff(BASE, edited(optimize={"objective": 17000.0 * (1 + 1e-8)})), 1, "numeric")

    # A status that changed carries no tolerance at all.
    check("condition changed", diff(BASE, edited(optimize={"condition": "infeasible"})), 1, "categorical")

    # `True == 1` in Python, so a converged flag becoming a number must not
    # compare equal and slip through as unchanged.
    check(
        "converged flag became a number",
        diff(BASE, edited(pf={"converged": frame(["bus"], [[1.0]])})),
        1,
        "categorical",
    )

    # Structural changes, which are upstream having changed what it produces.
    check(
        "column dropped",
        diff(BASE, edited(optimize={"generator_p": frame(["base"], [[100.0], [80.0]])})),
        1,
        "structural",
    )
    check(
        "column reordered",
        diff(BASE, edited(optimize={"generator_p": frame(["peak", "base"], [[100.0, 0.0], [80.0, 20.0]])})),
        1,
        "structural",
    )
    check(
        "row added",
        diff(BASE, edited(optimize={"generator_p": frame(["base", "peak"], [[100.0, 0.0], [80.0, 20.0], [0.0, 0.0]])})),
        1,
        "structural",
    )
    check(
        "key added",
        diff(BASE, edited(optimize={"new_frame": frame(["a"], [[1.0]])})),
        1,
        "structural",
    )
    check(
        "nested value moved",
        diff(BASE, edited(optimize={"nominal_opt": {"base": 250.0}})),
        1,
        "numeric",
    )

    # NaN is a value in these files -- `lpf` records it by construction for a
    # link flow it does not determine -- so two NaNs agreeing is agreement, and a
    # NaN appearing where a number was is not.
    nan = {"$nan": True}
    check("NaN unchanged", diff({"a": nan}, {"a": nan}), 0)
    check("NaN replaced a number", diff({"a": 1.0}, {"a": nan}), 1, "numeric")
    check("number replaced a NaN", diff({"a": nan}, {"a": 1.0}), 1, "numeric")

    # Every cell is compared, not just the first row.
    check(
        "a later cell moved",
        diff(BASE, edited(optimize={"generator_p": frame(["base", "peak"], [[100.0, 0.0], [80.0, 25.0]])})),
        1,
        "numeric",
    )

    # The path has to name the cell, or a 852-bus frame reports a difference
    # nobody can locate.
    moved = diff(BASE, edited(optimize={"generator_p": frame(["base", "peak"], [[100.0, 0.0], [80.0, 25.0]])}))
    if moved and "[1][peak]" not in moved[0].path:
        problems.append(f"path did not name the moved cell: {moved[0].path}")

    return problems


CASES: list[tuple[str, dict, dict, int, str]] = [
    ("identical directories pass", {"net": BASE}, {"net": BASE}, 0, "every result is unchanged"),
    ("a moved objective fails",
     {"net": BASE}, {"net": edited(optimize={"objective": 17300.0})}, 1, "Numeric"),
    ("a changed condition fails",
     {"net": BASE}, {"net": edited(optimize={"condition": "infeasible"})}, 1, "Categorical"),
    ("a dropped column fails",
     {"net": BASE}, {"net": edited(optimize={"generator_p": frame(["base"], [[100.0], [80.0]])})},
     1, "Structural"),
    ("a network missing from the new side is named",
     {"net": BASE, "other": BASE}, {"net": BASE}, 1, "**other** is missing"),
    ("a network only on the new side is named",
     {"net": BASE}, {"net": BASE, "extra": BASE}, 1, "**extra** is new"),
    ("two empty directories are a usage error", {}, {}, 2, "no result files"),
]


def main_tests() -> int:
    failures = 0
    for name, old, new, expected_code, expected_text in CASES:
        code, output = run(old, new)
        if code != expected_code or expected_text not in output:
            failures += 1
            print(f"FAIL  {name}")
            print(f"      expected exit {expected_code} containing {expected_text!r}")
            print(f"      got exit {code}: {output.strip()[:300]}")
        else:
            print(f"ok    {name}")

    problems = unit_cases()
    for problem in problems:
        failures += 1
        print(f"FAIL  {problem}")
    if not problems:
        print("ok    comparison rules")

    total = len(CASES) + 1
    if failures:
        print(f"\n{failures} of {total} cases failed", file=sys.stderr)
        return 1
    print(f"\n{total} cases passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main_tests())
