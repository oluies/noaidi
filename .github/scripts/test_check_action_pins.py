#!/usr/bin/env python3
"""Tests for `check_action_pins.py`.

These existed as a shell session and a paragraph in a commit message, which is
worth nothing to the next person: the script's logic is globbing, tree walking
and a regex over version strings, and its failure mode is a check that passes
while the invariant it names is broken.

Plain asserts and a `__main__`, deliberately. The whole argument for pinning
`pyyaml` and `json5` in the workflow is that a check about dependency discipline
should not be casual about its own, and adding pytest to buy `assert` would
undercut it.

Rewritten when Renovate replaced Dependabot. The cases that went away were about
`dependabot.yml`'s `ignore` list -- an entry matching nothing, an entry
suppressing the wrong update types, an action missing from the list -- none of
which is a state that can exist now: Renovate needs no such list, because it
offers only the next major for a floating tag. The cases about *pinning* stayed
and became errors rather than a cross-check, and two are new, for the Renovate
config itself.

    python3 .github/scripts/test_check_action_pins.py
"""

from __future__ import annotations

import io
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from check_action_pins import check  # noqa: E402

# The shape that matters, not the whole file: `enabledManagers` is the only key
# the checker reads, and a fixture carrying the rest would drift from the real
# config without testing anything more.
CONFIG = '{\n  // a comment, because the real file is json5 for its comments\n  enabledManagers: ["github-actions"],\n}\n'


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


def tree(*, uses: tuple[str, ...], workflow_name: str = "ci.yml", config: str = CONFIG) -> dict[str, str]:
    return {
        f".github/workflows/{workflow_name}": workflow(*uses),
        ".github/renovate.json5": config,
    }


CASES: list[tuple[str, dict[str, str], int, str]] = [
    (
        "floating-major pins pass",
        tree(uses=("actions/checkout@v7", "sbt/setup-sbt@v1")),
        0,
        "all floating-major pinned",
    ),
    (
        "a SHA pin is an error",
        tree(uses=("actions/checkout@" + "a" * 40,)),
        1,
        "not a floating major tag",
    ),
    (
        "an exact version pin is an error",
        tree(uses=("actions/checkout@v7.1.2",)),
        1,
        "not a floating major tag",
    ),
    (
        "a branch pin is an error",
        tree(uses=("actions/checkout@main",)),
        1,
        "not a floating major tag",
    ),
    (
        "one action pinned two ways reports the loose pin only",
        {
            ".github/workflows/ci.yml": workflow("actions/checkout@v7"),
            ".github/workflows/pages.yml": workflow("actions/checkout@v7.1.2"),
            ".github/renovate.json5": CONFIG,
        },
        1,
        "['v7', 'v7.1.2'], and ['v7.1.2'] is not",
    ),
    (
        "a .yaml workflow is scanned too",
        tree(uses=("actions/checkout@" + "b" * 40,), workflow_name="ci.yaml"),
        1,
        "not a floating major tag",
    ),
    (
        "a root action.yml is scanned",
        {
            **tree(uses=("actions/checkout@v7",)),
            "action.yml": "name: a\nruns:\n  using: composite\n  steps:\n    - uses: sbt/setup-sbt@v1.2\n",
        },
        1,
        "sbt/setup-sbt is pinned to ['v1.2']",
    ),
    (
        "a commented-out uses: is not counted",
        {
            ".github/workflows/ci.yml": "name: w\non: push\njobs:\n  j:\n    runs-on: ubuntu-latest\n"
            "    steps:\n      - uses: actions/checkout@v7\n      # - uses: astral-sh/setup-uv@main\n",
            ".github/renovate.json5": CONFIG,
        },
        0,
        "1 files, 1 actions",
    ),
    (
        "a local composite reference has no version to pin and is skipped",
        tree(uses=("actions/checkout@v7", "./.github/actions/local")),
        0,
        "1 actions",
    ),
    (
        "a docker:// reference is skipped",
        tree(uses=("actions/checkout@v7", "docker://alpine:3.20")),
        0,
        "1 actions",
    ),
    (
        "no scanned files at all is an error, not a silent pass",
        {".github/renovate.json5": CONFIG},
        1,
        "has a directory moved?",
    ),
    (
        "no Renovate configuration at all is an error",
        {".github/workflows/ci.yml": workflow("actions/checkout@v7")},
        1,
        "no Renovate configuration found",
    ),
    (
        "two Renovate configurations are an error, because only the first is read",
        {
            ".github/workflows/ci.yml": workflow("actions/checkout@v7"),
            "renovate.json": '{"enabledManagers": ["github-actions"]}',
            ".github/renovate.json5": CONFIG,
        },
        1,
        "several Renovate configurations exist",
    ),
    (
        "a Renovate configuration that does not parse is reported as such",
        tree(uses=("actions/checkout@v7",), config="{ enabledManagers: [ \n"),
        1,
        "does not parse",
    ),
    (
        "widening enabledManagers to sbt is an error",
        tree(uses=("actions/checkout@v7",), config='{ enabledManagers: ["github-actions", "sbt"] }'),
        1,
        "puts two bots on the same dependencies",
    ),
    (
        "omitting enabledManagers is an error, since the default is every manager",
        tree(uses=("actions/checkout@v7",), config="{ labels: [\"dependencies\"] }"),
        1,
        "enabledManagers to None",
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
