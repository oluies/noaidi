#!/usr/bin/env python3
"""Every `run:` block in every workflow, parsed by the shell that will run it.

YAML validity says nothing about the script inside it. A `run:` block is a string
as far as the parser is concerned, so an unbalanced quote in one is a perfectly
good YAML document -- and the error arrives on a runner, after checkout, after
`setup-java`, after a cache restore, as `line 37: unexpected EOF while looking
for matching '"'` and exit code 2.

That is exactly how `pages.yml` reached `main` broken. The quote was stray, every
command in the block had been run by hand and worked, the YAML parsed, and the
job still failed on its first real execution with the step never starting. `bash
-n` would have caught it in milliseconds.

Only syntax is checked, which is the part that can be checked without side
effects. A script that parses can still be wrong; a script that does not parse is
wrong for certain, and that is the class this closes.

Anything bash writes counts, not only a non-zero exit. An unterminated heredoc is
why: `bash -n` accepts one, exits 0, and warns about it on stderr instead.

GitHub expressions are left alone rather than substituted. `${{ matrix.java }}`
parses as a parameter expansion -- bash would object at run time and never does,
because the expression is replaced before the shell sees it -- so there is
nothing to stand in for, and a placeholder could mask a real error beside it.

    python3 .github/scripts/check_workflow_shell.py
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile
from typing import TextIO

import yaml

# The shells this can check. Anything else -- `python`, `pwsh`, `node` -- is a
# different language and is skipped rather than guessed at, because handing a
# Python script to `bash -n` would report a syntax error that is not one.
POSIX_SHELLS = {"bash", "sh", "bash -e {0}", "bash -eo pipefail {0}"}


def shell_of(workflow: dict, job: dict, step: dict) -> str:
    """The shell a step will use, following GitHub's own precedence.

    Step, then job defaults, then workflow defaults, then `bash` -- which is the
    default for every Linux runner, and every runner in this repository is Linux.
    """
    for scope in (step, job, workflow):
        if scope is step:
            named = scope.get("shell")
        else:
            named = (scope.get("defaults") or {}).get("run", {}).get("shell")
        if named:
            return named
    return "bash"


def problems_in(path: pathlib.Path) -> list[str]:
    document = yaml.safe_load(path.read_text())
    if not isinstance(document, dict):
        return [f"{path}: not a mapping"]

    found: list[str] = []
    for job_name, job in (document.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        for index, step in enumerate(job.get("steps") or []):
            if not isinstance(step, dict):
                continue
            script = step.get("run")
            if not script:
                continue

            shell = shell_of(document, job, step)
            if shell.split()[0] not in {s.split()[0] for s in POSIX_SHELLS}:
                continue

            name = step.get("name") or f"step {index + 1}"
            with tempfile.NamedTemporaryFile(
                "w", suffix=".sh", delete=False, encoding="utf-8"
            ) as handle:
                handle.write(script)
                written = pathlib.Path(handle.name)
            try:
                done = subprocess.run(
                    ["bash", "-n", str(written)], capture_output=True, text=True
                )
            finally:
                written.unlink(missing_ok=True)

            # Anything bash says, not only a non-zero exit. An unterminated
            # heredoc is the case that forces this: `bash -n` accepts it, exits
            # 0, and warns on stderr -- "here-document at line 1 delimited by
            # end-of-file" -- which is a real defect in a workflow and would
            # otherwise pass. None of this repository's own run blocks emit
            # anything on stderr, so there is no noise to tolerate.
            if done.returncode != 0 or done.stderr.strip():
                detail = done.stderr.strip().replace(str(written), "<script>")
                found.append(f"{path.name} / {job_name} / {name}: {detail or 'exit ' + str(done.returncode)}")
    return found


def check(root: pathlib.Path, out: TextIO = sys.stdout, err: TextIO = sys.stderr) -> int:
    workflows = sorted(
        p for p in (root / ".github" / "workflows").iterdir()
        if p.suffix in {".yml", ".yaml"}
    )

    problems: list[str] = []
    blocks = 0
    for path in workflows:
        document = yaml.safe_load(path.read_text())
        if isinstance(document, dict):
            blocks += sum(
                1
                for job in (document.get("jobs") or {}).values()
                if isinstance(job, dict)
                for step in (job.get("steps") or [])
                if isinstance(step, dict) and step.get("run")
            )
        problems.extend(problems_in(path))

    if problems:
        print("A workflow script does not parse as a shell script:", file=err)
        for problem in problems:
            print(f"  - {problem}", file=err)
        print(
            "\nValid YAML says nothing about the script inside a `run:` block. "
            "This is the error that would otherwise arrive on a runner as "
            "`unexpected EOF` and exit code 2.",
            file=err,
        )
        return 1

    print(
        f"{blocks} run blocks across {len(workflows)} workflows: every one parses as a shell script",
        file=out,
    )
    return 0


def main() -> int:
    return check(pathlib.Path(__file__).resolve().parents[2])


if __name__ == "__main__":
    raise SystemExit(main())
