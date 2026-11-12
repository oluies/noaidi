#!/usr/bin/env python3
"""What the drift job installed, beside what the goldens were generated with.

The weekly drift job installs `--upgrade pypsa highspy` into a fresh virtualenv, which
resolves the newest pandas, numpy, linopy and scipy as well. Until this existed it
recorded **only** PyPSA's version, and the report compared only that.

On 2026-11-02 that cost a diagnosis. The golden generator died with
`pandas.errors.LossySetitemError` -> `TypeError: Invalid value '[1.0, 1.0, 3.0, 1.0]' for
dtype 'int64'`, and the summary read:

    | pinned | `1.4.0` |
    | latest | `1.4.0` |

PyPSA had not moved at all. Something underneath it had, the report could not say what, and
finding out meant installing the newest packages by hand and bisecting. A drift job that
cannot name what moved sends the reader back to do the job itself.

Two modes, because the two halves run under different interpreters: `collect` runs inside
the venv whose packages are the question, and `table` runs under the runner's own
interpreter against the committed manifest. Standard library only, in both.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Ordered deliberately: PyPSA first because it is what the job is named for, python next
# because an interpreter change moves everything under it, then the array and solver
# libraries that an answer actually comes out of.
PACKAGES = ("pypsa", "python", "pandas", "numpy", "linopy", "highspy", "scipy", "xarray")


def collect() -> dict[str, str]:
    """Versions of everything in this interpreter that the goldens could depend on.

    `importlib.metadata` rather than `module.__version__`: it does not import the package,
    so a broken install reports a version instead of a traceback, and it is the same source
    `pip list` reads. A package that is absent is left out rather than recorded as empty --
    "not installed" and "installed, version unknown" are different facts and the table
    below distinguishes them.
    """
    import importlib.metadata as metadata

    found = {"python": ".".join(str(part) for part in sys.version_info[:3])}
    for package in PACKAGES:
        if package == "python":
            continue
        try:
            found[package] = metadata.version(package)
        except metadata.PackageNotFoundError:
            pass
    return found


def table(pinned: dict[str, str], latest: dict[str, str]) -> str:
    """The comparison, as the markdown the job summary wants.

    The fourth column is the point of the whole file. A reader arriving at a failed drift
    run wants one question answered -- what moved -- and a table of version pairs makes
    that a diff the reader has to perform. `**moved**` performs it.

    A package the goldens do not pin is marked as such rather than left blank, because the
    two cases call for different actions: a pinned package that moved is a drift question,
    and an unpinned one that moved is a gap in the manifest.

    `python` is marked `runner differs` rather than `**moved**` for the same reason read the
    other way: the interpreter is the workflow's own choice through `setup-python` and is
    not resolved by pip, so it differs on every scheduled run against locally generated
    goldens. It stays visible -- a Python change can move an answer -- without being
    counted as the thing that did.
    """
    rows = [
        "| | pinned | latest | |",
        "| --- | --- | --- | --- |",
    ]
    for package in PACKAGES:
        here, there = pinned.get(package), latest.get(package)
        if here is None and there is None:
            continue
        if here is None:
            mark = "not pinned"
        elif there is None:
            mark = "not installed"
        elif here == there:
            mark = ""
        elif package == "python":
            # Not `**moved**`. The interpreter is chosen by the workflow's `setup-python`,
            # not resolved by pip, and the goldens are generated locally -- so this row
            # differs on every scheduled run whether or not anything changed. Marked as
            # `**moved**` it was a guaranteed false lead in the one column this file exists
            # to provide, and the failure annotation sends the reader straight here.
            mark = "runner differs"
        else:
            mark = "**moved**"
        rows.append(f"| {package} | `{here or '—'}` | `{there or '—'}` | {mark} |")
    return "\n".join(rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)
    sub.add_parser("collect", help="print this interpreter's versions as JSON")
    render = sub.add_parser("table", help="print the pinned/latest comparison as markdown")
    render.add_argument("--manifest", type=Path, required=True,
                        help="reference/goldens/manifest.json, which pins the generation")
    render.add_argument("--latest", type=Path, required=True,
                        help="the JSON that `collect` wrote inside the venv")
    args = parser.parse_args(argv)

    if args.mode == "collect":
        json.dump(collect(), sys.stdout, sort_keys=True)
        print()
        return 0

    pinned = json.loads(args.manifest.read_text())["versions"]
    # A missing or unreadable latest file is reported as an empty set rather than as a
    # crash: the report step runs `if: always()`, so it has to survive an install step that
    # never got far enough to write one. Every row then reads "not installed", which is
    # itself the finding.
    try:
        latest = json.loads(args.latest.read_text())
    except (OSError, json.JSONDecodeError):
        latest = {}
    print(table(pinned, latest))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
