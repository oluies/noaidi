#!/usr/bin/env python3
"""Tests for `check_workflow_shell.py`.

Two failure modes, and the cases here are split between them.

Not firing when it should is the one that let `pages.yml` reach `main` with a
stray quote in it, so the first case is that exact script, reduced: a double
quote with nothing to close it, which must be reported.

Firing when it should not is the one that gets a check deleted rather than fixed.
A `run:` block can be Python or PowerShell, and handing either to `bash -n`
produces a syntax error that is not one -- so a step naming another shell must be
skipped, and a step with no `run:` at all must be ignored rather than treated as
an empty script.

Plain asserts and a `__main__`, matching `test_check_orphan_docs.py` and for the
same reason.

    python3 .github/scripts/test_check_workflow_shell.py
"""

from __future__ import annotations

import io
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import check_workflow_shell  # noqa: E402


def run(workflow: str) -> tuple[int, str, str]:
    """`check` against a tree holding one workflow."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        (root / ".github" / "workflows").mkdir(parents=True)
        (root / ".github" / "workflows" / "one.yml").write_text(workflow)
        out, err = io.StringIO(), io.StringIO()
        code = check_workflow_shell.check(root, out=out, err=err)
        return code, out.getvalue(), err.getvalue()


CASES: list[tuple[str, str, bool]] = [
    (
        "the stray quote that reached main",
        """
jobs:
  build:
    steps:
      - name: Assemble
        run: |
          set -euo pipefail
          out=site
          ls -la "$out
""",
        True,
    ),
    (
        "a script that parses",
        """
jobs:
  build:
    steps:
      - name: Assemble
        run: |
          set -euo pipefail
          out=site
          mkdir -p "$out"
          ls -la "$out"
""",
        False,
    ),
    (
        "a GitHub expression is not a shell error",
        """
jobs:
  build:
    steps:
      - name: Expressions
        run: |
          echo "${{ github.repository }}"
          if [ "${{ github.event_name }}" = push ]; then echo yes; fi
""",
        False,
    ),
    (
        "an unterminated heredoc is reported",
        """
jobs:
  build:
    steps:
      - name: Heredoc
        run: |
          cat <<'EOF'
          never closed
""",
        True,
    ),
    (
        "a python step is skipped rather than parsed as bash",
        """
jobs:
  build:
    steps:
      - name: Python
        shell: python
        run: |
          data = {"quote": '"'}
          print(data)
""",
        False,
    ),
    (
        "a job default shell of python is honoured",
        """
jobs:
  build:
    defaults:
      run:
        shell: python
    steps:
      - name: Python
        run: |
          print("unbalanced for bash: \\"")
""",
        False,
    ),
    (
        "a step with no run block is ignored",
        """
jobs:
  build:
    steps:
      - uses: actions/checkout@v7
      - name: Nothing
        with:
          path: site
""",
        False,
    ),
]


def main() -> int:
    failures = 0
    for name, workflow, should_fail in CASES:
        code, out, err = run(workflow)
        failed = code != 0
        if failed != should_fail:
            failures += 1
            expected = "a finding" if should_fail else "no finding"
            print(f"FAIL  {name}: expected {expected}, got code {code}")
            print(f"      out={out.strip()!r} err={err.strip()!r}")
        else:
            print(f"ok    {name}")

    # The message has to name the step, or a failure in a workflow with thirty
    # run blocks says only that one of them is broken.
    code, _, err = run(CASES[0][1])
    if "Assemble" not in err or "one.yml" not in err:
        failures += 1
        print(f"FAIL  the finding names its workflow and step: {err.strip()!r}")
    else:
        print("ok    the finding names its workflow and step")

    total = len(CASES) + 1
    if failures:
        print(f"\n{failures} of {total} cases failed", file=sys.stderr)
        return 1
    print(f"\n{total} cases passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
