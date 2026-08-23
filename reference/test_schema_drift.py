#!/usr/bin/env python3
"""Tests for `schema_drift.py`.

Written for the same reason `test_check_action_pins.py` was, and it is the same
reason: the script is a set comparison and a field-by-field walk, and its failure
mode is reporting "no drift" while a default moved underneath it. A drift
detector that silently detects nothing is worse than not having one, because the
green run is taken as evidence.

Plain asserts and a `__main__`, matching the other checker in this repository and
keeping this runnable on a bare checkout -- which is the whole point of
`schema_drift.py` being stdlib-only.

    python3 reference/test_schema_drift.py
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from schema_drift import main  # noqa: E402


def component(**attributes) -> dict:
    return {"list_name": "things", "category": "passive_one_port", "description": "a thing",
            "attributes": attributes}


def attribute(**overrides) -> dict:
    """An attribute row in exactly the shape `component_schema()` emits.

    Five keys, and no `description` -- the generator does not write one at
    attribute level. A fixture carrying fields the real thing never has would let
    a test pass against a shape that cannot occur, which is how the comparator
    grew a `description` walk that could never fire.
    """
    row = {"type": "float", "unit": "MW", "default": 0.0, "status": "Input (optional)",
           "varying": False}
    row.update(overrides)
    return row


BASE = {"Thing": component(p_nom=attribute(), name=attribute(type="string", default=""))}


def run(old: dict, new: dict) -> tuple[int, str]:
    with tempfile.TemporaryDirectory() as tmp:
        a, b = Path(tmp) / "old.json", Path(tmp) / "new.json"
        a.write_text(json.dumps(old))
        b.write_text(json.dumps(new))
        out = io.StringIO()
        with redirect_stdout(out):
            code = main([str(a), str(b)])
        return code, out.getvalue()


def edited(**changes) -> dict:
    """BASE with `Thing.p_nom` altered."""
    new = json.loads(json.dumps(BASE))
    new["Thing"]["attributes"]["p_nom"].update(changes)
    return new


def added(name: str, **overrides) -> dict:
    new = json.loads(json.dumps(BASE))
    new["Thing"]["attributes"][name] = attribute(**overrides)
    return new


def component_edited(**changes) -> dict:
    """BASE with the component's own fields altered."""
    new = json.loads(json.dumps(BASE))
    new["Thing"].update(changes)
    return new


# (name, old, new, expected exit, text that must appear)
CASES = [
    ("identical schemas are not drift", BASE, BASE, 0, "identical"),

    # The four field changes that can move a number. `unit` is in here because a
    # rescaled unit is the one change where both PyPSA and this port would keep
    # agreeing with each other and both be wrong about the world.
    ("a changed default fails", BASE, edited(default=1.0), 1, "default 0.0 -> 1.0"),
    ("a changed type fails", BASE, edited(type="int"), 1, "type 'float' -> 'int'"),
    ("a changed unit fails", BASE, edited(unit="kW"), 1, "unit 'MW' -> 'kW'"),
    ("a static attribute becoming time-varying fails", BASE, edited(varying=True), 1,
     "varying False -> True"),
    ("an input becoming an output fails", BASE, edited(status="Output"), 1, "status"),

    # A reworded description is the churn a version bump produces most of, and
    # failing on it would train everyone to ignore the job. Only components carry
    # one -- attribute rows have no description field at all, which is why the
    # comparator does not walk for one.
    ("a reworded component description is reported but passes", BASE,
     component_edited(description="reworded"), 0, "cosmetic"),

    # The other two component-level fields, which decide what a component *is*:
    # `list_name` is the CSV file it exports to and `category` is what topology
    # is derived from, so either moving is a structural change to the model.
    ("a changed list_name fails", BASE, component_edited(list_name="widgets"), 1,
     "list_name 'things' -> 'widgets'"),
    ("a changed category fails", BASE, component_edited(category="controllable_branch"), 1,
     "category 'passive_one_port' -> 'controllable_branch'"),

    ("a removed attribute fails", BASE, {"Thing": component(name=attribute(type="string"))}, 1,
     "attribute removed"),
    ("a removed component fails", BASE, {}, 1, "component removed"),

    # The case the whole workflow exists for: upstream adds a column a network
    # may now set and this port has never seen.
    ("a new input attribute fails and is called out", BASE, added("e_sum_max"), 1,
     "new input attribute"),
    ("a new input attribute names itself", BASE, added("e_sum_max"), 1, "`Thing.e_sum_max`"),

    # A new *output* is incompleteness, not silence: nothing reads it from a
    # file, so no network can set it and get a wrong answer back.
    ("a new output attribute fails without being called an input", BASE,
     added("mu_upper", type="series", status="Output"), 1, "output"),
    ("a new component lists its inputs", BASE, {**BASE, "Widget": component(q=attribute())}, 1,
     "`Widget.q`"),

    # Everything below is a *file-level* failure rather than a comparison, and
    # every one of them must exit 2. Exiting 1 would mean "drift", which the
    # workflow acts on by opening an issue telling maintainers upstream changed
    # -- so an unreadable file would be reported as a PyPSA release, with an
    # empty diff in the issue and the real reason on stderr where nobody looks.
    #
    # Each asserts the diagnostic as well as the status. `run_special` goes out
    # of its way to capture stderr precisely so these can, and while they all
    # passed `""` they were not using it: deleting both `print(..., stderr)`
    # calls in `load()` left the suite green, and a silent exit 2 reproduces half
    # the failure it exists to prevent -- the operator is shown nothing about why.
    ("bad usage exits 2", "usage", None, 2, "usage:"),
    ("a missing file exits 2, not 1", "missing", None, 2, "cannot read"),
    ("a directory in place of a file exits 2, not 1", "directory", None, 2, "cannot read"),
    ("malformed JSON exits 2, not 1", "badjson", None, 2, "is not JSON"),
    # Not valid UTF-8, which is a `ValueError` rather than an `OSError` and so
    # escaped both branches as a traceback -- exit 1, read by the workflow as
    # drift. The shape of a half-written download.
    ("a non-UTF-8 file exits 2, not 1", "badbytes", None, 2, "cannot read"),
]


def run_special(kind: str) -> tuple[int, str]:
    """The file-level failures, which raise SystemExit rather than returning."""
    with tempfile.TemporaryDirectory() as tmp:
        good = Path(tmp) / "good.json"
        good.write_text(json.dumps(BASE))
        if kind == "usage":
            argv = ["only-one-argument"]
        elif kind == "missing":
            argv = [str(good), str(Path(tmp) / "nope.json")]
        elif kind == "directory":
            argv = [str(good), tmp]
        elif kind == "badjson":
            bad = Path(tmp) / "bad.json"
            bad.write_text("{not json,")
            argv = [str(good), str(bad)]
        elif kind == "badbytes":
            # Written as bytes: a UTF-16 BOM followed by `{`, which `read_text`
            # rejects before `json.loads` ever sees it.
            bad = Path(tmp) / "bad.json"
            bad.write_bytes(b"\xff\xfe{")
            argv = [str(good), str(bad)]
        else:
            raise AssertionError(f"unknown case kind {kind!r}")

        out, err = io.StringIO(), io.StringIO()
        try:
            with redirect_stdout(out), redirect_stderr(err):
                code = main(argv)
        except SystemExit as e:
            code = e.code if isinstance(e.code, int) else 1
        # stderr counts as output here: that is where these diagnostics go, and
        # a case asserting on the message would otherwise be asserting on silence.
        return code, out.getvalue() + err.getvalue()


def main_() -> int:
    failures = 0
    for name, old, new, expected_code, expected_text in CASES:
        if isinstance(old, str):
            code, text = run_special(old)
        else:
            code, text = run(old, new)

        ok = code == expected_code and expected_text.lower() in text.lower()
        # The "new input" case must not also claim the *output* case is an input,
        # so a couple of cases assert on absence as well.
        if name.endswith("without being called an input"):
            ok = ok and "new input attribute" not in text.lower()
        if not ok:
            failures += 1
            print(f"FAIL  {name}")
            print(f"      expected exit {expected_code} containing {expected_text!r}")
            print(f"      got exit {code}: {text.strip()[:400]}")
        else:
            print(f"ok    {name}")

    if failures:
        print(f"\n{failures} of {len(CASES)} cases failed", file=sys.stderr)
        return 1
    print(f"\n{len(CASES)} cases passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main_())
