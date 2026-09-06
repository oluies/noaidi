#!/usr/bin/env python3
"""Check `count_tests.sh` against real sbt logs, including the ones that broke it.

The counter this exercises is the only thing standing between "sbt exited 0" and
"the tests ran". It has been wrong twice, in opposite directions:

  - reading only munit's per-suite lines called a replayed `primaOrtools/testFull`
    zero tests and failed a job whose ten tests had all passed;
  - reading only sbt's `Passed: Total` undercounts a partially warm cache, which
    is why the aggregate step stopped using it.

So the fixtures are the log states the counter has to survive, and the
expectations are what a human read off each log by hand. Each fixture holds the
lines the counters actually read, extracted verbatim from a real run --
`test_workflow_log_patterns.py` gives the reason in full: a check written
against an invented sample tests the invention.

One of them is coloured, and that is not decoration. sbt writes terminal control
sequences when CI is attached to its logger and not when a local run is piped to
a file, so uncoloured fixtures alone would leave the form CI reads unexercised --
and an anchored pattern, the mistake behind the pypsa-drift outage, matches a
coloured line nowhere while passing every uncoloured fixture. `sbt-ortools-coloured.log`
comes from run 34013731543 via `gh run view --log`, whose caret notation was
decoded back to the escape bytes `tee` captures inside the step.

    python3 .github/scripts/test_count_tests.py
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github" / "scripts" / "count_tests.sh"
FIXTURES = ROOT / ".github" / "scripts" / "fixtures"

# (fixture, suites, summary, total, expect a replay note)
CASES = (
    (
        "sbt-aggregate.log",
        755,
        613,
        755,
        False,
        "a partially warm cache: every framework line present, sbt's own total short by 142",
    ),
    (
        "sbt-ortools.log",
        14,
        14,
        14,
        False,
        "an ordinary run, where the two counters agree and neither is doing any work",
    ),
    (
        "sbt-ortools-replayed.log",
        0,
        14,
        14,
        True,
        "a replayed test action: sbt reports its cached total and the framework emits nothing. "
        "This is the shape that failed CI on JDK 25",
    ),
    (
        "sbt-ortools-coloured.log",
        14,
        14,
        14,
        False,
        "an ordinary run as it reaches the counters inside Actions, with sbt's terminal "
        "control sequences intact -- a different run from the uncoloured fixture above, and "
        "the only one here in the form CI actually reads. Tightening either pattern to "
        "`^Test run` or `^[info] Passed:` passes every other fixture and matches nothing at "
        "all in this one, which is the pypsa-drift outage exactly",
    ),
    (
        "sbt-nothing.log",
        0,
        0,
        0,
        False,
        "nothing ran at all, which is the only state the floor is meant to catch",
    ),
)


# What the script must refuse, and with which message.
#
# Separate from CASES because these have no counts to compare -- the script is
# expected not to reach the counting at all. Without them the guard at
# `count_tests.sh`'s head has no coverage: deleting it outright, or replacing it
# with a no-op, left this file green at five for five. That is the same defect
# this file was written to fix one level up, and it shipped in the commit that
# fixed it.
REFUSALS = (
    (
        [str(FIXTURES / "does-not-exist.log")],
        1,
        str(FIXTURES / "does-not-exist.log"),
        "a path that is not there must name the path, not report an empty suite",
    ),
    (
        [str(FIXTURES)],
        1,
        str(FIXTURES),
        "a directory is readable, so `-r` alone let it through to the same false zero",
    ),
    (
        [],
        1,
        "usage:",
        "no argument at all is a caller error, not a log with no tests in it",
    ),
)


def run(*arguments: str) -> tuple[int, str, str]:
    """Run the script, returning (code, stdout, stderr).

    The exit code is returned rather than raised -- `check=True` would turn a
    refusal into a traceback, which is why the guard it protects could not be
    tested at all. The two sibling harnesses, `test_check_action_pins.py` and
    `test_check_orphan_docs.py`, return the same triple for the same reason.
    """
    result = subprocess.run(
        ["bash", str(SCRIPT), *arguments],
        capture_output=True,
        text=True,
        check=False,
    )
    return result.returncode, result.stdout, result.stderr


def counts_of(stdout: str) -> dict[str, int]:
    counts = {}
    for line in stdout.splitlines():
        key, _, value = line.partition("=")
        counts[key] = int(value)
    return counts


def main() -> int:
    failures = 0
    for fixture, suites, summary, total, note, why in CASES:
        code, stdout, stderr = run(str(FIXTURES / fixture))
        if code != 0:
            failures += 1
            print(f"FAIL  {fixture}: exited {code} on a log it should have counted -- {stderr.strip()}")
            continue
        counts = counts_of(stdout)
        expected = {"suites": suites, "summary": summary, "total": total}
        if counts != expected:
            failures += 1
            print(f"FAIL  {fixture}: got {counts}, expected {expected} -- {why}")
            continue
        saw_note = "replayed a cached test action" in stderr
        if saw_note != note:
            failures += 1
            said = "did" if saw_note else "did not"
            wanted = "should" if note else "should not"
            print(f"FAIL  {fixture}: {said} report a replay, {wanted} -- {why}")
            continue
        print(f"ok    {fixture}: {total} tests -- {why}")

    # The property the maximum rests on, stated as a test rather than as a
    # comment: no log makes both counters wrong, so the larger is right.
    for fixture, suites, summary, total, _, _ in CASES:
        if total not in (suites, summary):
            failures += 1
            print(f"FAIL  {fixture}: the total {total} came from neither counter")
        if total == 0 and (suites or summary):
            failures += 1
            print(f"FAIL  {fixture}: reported nothing ran while a counter saw tests")

    for arguments, expected_code, expected_text, why in REFUSALS:
        code, stdout, stderr = run(*arguments)
        if code != expected_code or expected_text not in stderr:
            failures += 1
            print(f"FAIL  {arguments}: expected exit {expected_code} mentioning {expected_text!r} -- {why}")
            print(f"      got exit {code}: {(stdout + stderr).strip()}")
            continue
        print(f"ok    {arguments or 'no argument'}: refused -- {why}")

    print(f"\n{len(CASES)} sbt log states counted, {len(REFUSALS)} refusals")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
