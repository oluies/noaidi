#!/usr/bin/env python3
"""Check `count_tests.sh` against real sbt logs, including the ones that broke it.

The counter this exercises is the only thing standing between "sbt exited 0" and
"the tests ran". It has been wrong twice, in opposite directions:

  - reading only munit's per-suite lines called a replayed `primaOrtools/testFull`
    zero tests and failed a job whose ten tests had all passed;
  - reading only sbt's `Passed: Total` undercounts a partially warm cache, which
    is why the aggregate step stopped using it.

So the fixtures are the four states, and the expectations are what a human read
off each log by hand. Each fixture holds the lines the counters actually read,
extracted verbatim from a real run -- `test_workflow_log_patterns.py` gives the
reason in full: a check written against an invented sample tests the invention.

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
        "sbt-nothing.log",
        0,
        0,
        0,
        False,
        "nothing ran at all, which is the only state the floor is meant to catch",
    ),
)


def run(fixture: str) -> tuple[dict[str, int], str]:
    result = subprocess.run(
        ["bash", str(SCRIPT), str(FIXTURES / fixture)],
        capture_output=True,
        text=True,
        check=True,
    )
    counts = {}
    for line in result.stdout.splitlines():
        key, _, value = line.partition("=")
        counts[key] = int(value)
    return counts, result.stderr


def main() -> int:
    failures = 0
    for fixture, suites, summary, total, note, why in CASES:
        counts, stderr = run(fixture)
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

    print(f"\n{len(CASES)} sbt log states counted")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
