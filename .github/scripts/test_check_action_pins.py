#!/usr/bin/env python3
"""Tests for `check_action_pins.py`.

These existed as a shell session and a paragraph in a commit message, which is
worth nothing to the next person: the script's logic is glob matching, tree
walking, a set comparison and an error/warning split, and its failure mode is a
check that passes while the invariant it names is broken.

Plain asserts and a `__main__`, deliberately. The whole argument for pinning
`pyyaml` in the workflow is that a check about dependency discipline should not
be casual about its own, and adding pytest to buy `assert` would undercut it.

    python3 .github/scripts/test_check_action_pins.py
"""

from __future__ import annotations

import io
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from check_action_pins import check  # noqa: E402

IGNORE = """\
version: 2
updates:
  - package-ecosystem: "github-actions"
    directory: "/"
    ignore:
{entries}
"""

ENTRY = """\
      - dependency-name: "{name}"
        update-types: ["version-update:semver-patch", "version-update:semver-minor"]
"""


def workflow(*uses: str) -> str:
    steps = "\n".join(f"      - uses: {u}" for u in uses)
    return f"name: w\non: push\njobs:\n  j:\n    runs-on: ubuntu-latest\n    steps:\n{steps}\n"


def run(files: dict[str, str]) -> tuple[int, str, str]:
    """Run `check` against a throwaway tree, returning (code, stdout, stderr)."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        for name, content in files.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        out, err = io.StringIO(), io.StringIO()
        code = check(root, out=out, err=err)
        return code, out.getvalue(), err.getvalue()


def tree(*, uses: tuple[str, ...], ignored: tuple[str, ...], workflow_name: str = "ci.yml") -> dict[str, str]:
    entries = "".join(ENTRY.format(name=n) for n in ignored)
    return {
        f".github/workflows/{workflow_name}": workflow(*uses),
        ".github/dependabot.yml": IGNORE.format(entries=entries),
    }


CASES: list[tuple[str, dict[str, str], int, str]] = [
    (
        "a floating-major action in the ignore list passes",
        tree(uses=("actions/checkout@v7",), ignored=("actions/checkout",)),
        0,
        "every ignored action is floating-major pinned",
    ),
    (
        "an ignored action pinned to a SHA is an error",
        tree(uses=("actions/checkout@" + "a" * 40,), ignored=("actions/checkout",)),
        1,
        "freezes it silently",
    ),
    (
        "an ignored action pinned to an exact version is an error",
        tree(uses=("actions/checkout@v7.1.2",), ignored=("actions/checkout",)),
        1,
        "freezes it silently",
    ),
    (
        "an ignore entry matching nothing in use is an error",
        tree(uses=("actions/checkout@v7",), ignored=("actions/checkout", "actions/nowhere")),
        1,
        "matches no action",
    ),
    (
        "a new floating-major action is a warning, not an error",
        tree(uses=("actions/checkout@v7", "astral-sh/setup-uv@v7"), ignored=("actions/checkout",)),
        0,
        "::warning::astral-sh/setup-uv",
    ),
    (
        "a .yaml workflow is scanned too",
        tree(uses=("actions/checkout@" + "b" * 40,), ignored=("actions/checkout",), workflow_name="ci.yaml"),
        1,
        "freezes it silently",
    ),
    (
        "a root action.yml is scanned, as Dependabot scans it",
        {
            **tree(uses=("actions/checkout@v7",), ignored=("actions/checkout",)),
            "action.yml": "name: a\nruns:\n  using: composite\n  steps:\n    - uses: sbt/setup-sbt@v1\n",
        },
        0,
        "::warning::sbt/setup-sbt",
    ),
    (
        "a commented-out uses: is not counted",
        {
            ".github/workflows/ci.yml": "name: w\non: push\njobs:\n  j:\n    runs-on: ubuntu-latest\n"
            "    steps:\n      - uses: actions/checkout@v7\n      # - uses: astral-sh/setup-uv@v7\n",
            ".github/dependabot.yml": IGNORE.format(entries=ENTRY.format(name="actions/checkout")),
        },
        0,
        "1 files, 1 actions",
    ),
    (
        "a glob entry with different casing resolves",
        tree(uses=("actions/checkout@v7", "actions/setup-java@v5"), ignored=("Actions/*",)),
        0,
        "every ignored action is floating-major pinned",
    ),
    (
        "an ignore entry with no update-types is an error",
        {
            ".github/workflows/ci.yml": workflow("actions/checkout@v7"),
            ".github/dependabot.yml": "version: 2\nupdates:\n  - package-ecosystem: \"github-actions\"\n"
            "    directory: \"/\"\n    ignore:\n      - dependency-name: \"actions/checkout\"\n",
        },
        1,
        "every update type",
    ),
    (
        "an ignore entry that also suppresses majors is an error",
        {
            ".github/workflows/ci.yml": workflow("actions/checkout@v7"),
            ".github/dependabot.yml": "version: 2\nupdates:\n  - package-ecosystem: \"github-actions\"\n"
            "    directory: \"/\"\n    ignore:\n      - dependency-name: \"actions/checkout\"\n"
            "        update-types: [\"version-update:semver-patch\", \"version-update:semver-minor\", "
            "\"version-update:semver-major\"]\n",
        },
        1,
        "unexpected ['version-update:semver-major']",
    ),
    (
        "no scanned files at all is an error, not a silent pass",
        {".github/dependabot.yml": IGNORE.format(entries=ENTRY.format(name="actions/checkout"))},
        1,
        "has a directory moved?",
    ),
    (
        "a dependabot.yml with no updates list is reported clearly",
        {".github/workflows/ci.yml": workflow("actions/checkout@v7"), ".github/dependabot.yml": "version: 2\n"},
        1,
        "no `updates` list",
    ),
    (
        "a dependabot.yml with no github-actions ecosystem is reported clearly",
        {
            ".github/workflows/ci.yml": workflow("actions/checkout@v7"),
            ".github/dependabot.yml": "version: 2\nupdates:\n  - package-ecosystem: \"npm\"\n    directory: \"/\"\n",
        },
        1,
        "no `github-actions` entry",
    ),
    (
        "a local composite reference has no version to pin and is skipped",
        tree(uses=("actions/checkout@v7", "./.github/actions/local"), ignored=("actions/checkout",)),
        0,
        "1 actions",
    ),
]


def main() -> int:
    failures = 0
    for name, files, expected_code, expected_text in CASES:
        code, out, err = run(files)
        combined = out + err
        if code != expected_code or expected_text not in combined:
            failures += 1
            print(f"FAIL  {name}")
            print(f"      expected exit {expected_code} containing {expected_text!r}")
            print(f"      got exit {code}: {combined.strip()}")
        else:
            print(f"ok    {name}")

    if failures:
        print(f"\n{failures} of {len(CASES)} cases failed", file=sys.stderr)
        return 1
    print(f"\n{len(CASES)} cases passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
