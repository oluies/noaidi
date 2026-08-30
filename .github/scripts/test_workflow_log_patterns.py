#!/usr/bin/env python3
"""Check the patterns the workflows use to read sbt's output against real output.

A workflow that greps a log has no compiler and no test framework behind it. The
pattern either matches or it does not, and a pattern that matches nothing looks
exactly like a log with nothing in it -- so the guard built on it reports the
absence of evidence as evidence.

That is not hypothetical. `pypsa-drift.yml` guarded its sweep with

    grep -E '^Test run .* finished:' sweep.log

and sbt colours that line, so it begins with an escape sequence rather than with
`Test`. The pattern matched nothing, the parsed count came out empty, and the
guard reported "the sweep reported no cases -- it did not run" directly beneath a
log line reading `0 failed, 0 ignored, 5 total`. The weekly schema-drift run had
been failing on that, which is worse than a false alarm: a job that always fails
is a job nobody reads, so the drift it exists to report would have gone with it.

Two kinds of line come out of an sbt run and they behave differently:

  framework  sbt's own -- `Test run ... finished`, `==> X`, `[info]`. Coloured,
             so an anchored pattern never matches.
  forked     what the test JVM prints itself, via `println`. Passed through
             uncoloured, because `Test / fork := true`, so `^` is safe.

Getting that backwards in either direction is silent. Hence this.

Each case pins the pattern *as it appears in the workflow* and a sample of the
line it has to read. Editing the pattern fails the presence check, which is the
point: the sample has to be updated alongside it, by someone who has looked at
what the log actually contains.

    python3 .github/scripts/test_workflow_log_patterns.py
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

# Real lines. Every sample below was copied from output, not written to look
# like it -- a pattern checked against an invented sample tests the invention.
#
# sbt colours its own lines when CI is attached to its logger and not when a
# local run is piped, so both forms are pinned: the coloured summary is from run
# 33254014936, where the anchored guard read it as no cases at all, and the plain
# one is from `sbt -batch networkModel/testOnly ...` here. A pattern has to read
# both, because the same workflow line runs in both places.
MUNIT_SUMMARY_CI = (
    "\x1b[0JTest run \x1b[36morg.noaidi.network.SchemaSweepSuite\x1b[0m finished: "
    "0 failed, 0 ignored, 5 total \x1b[90m0.24s\x1b[0m\x1b[0J"
)
MUNIT_SUMMARY_PLAIN = (
    "Test run org.noaidi.network.SchemaSweepSuite finished: 0 failed, 0 ignored, 5 total 0.04s"
)

# From `NOAIDI_GOLDENS=/nonexistent`, which fails every `assume` in the suite.
# This is the case the sweep guard's `skipped` count exists for: munit reports a
# failed assume as skipped and still exits 0, so the summary line alone is
# byte-identical to a clean run.
MUNIT_SKIPPED_PLAIN = (
    "==> s org.noaidi.network.SchemaSweepSuite.the sweep actually read the port and the schema skipped 0.01s"
)

# Printed by the forked JVM via `println`, which sbt passes through uncoloured
# because `Test / fork := true`. That is why `^` is safe on these and not on the
# framework's own lines above.
LP_HEADER = "instance             size                   prima             ojalgo"
MILP_HEADER = "instance           size              int prima     ojalgo"


class Case:
    def __init__(self, workflow: str, pattern: str, matches: list[str], rejects: list[str], why: str):
        self.workflow = workflow
        self.pattern = pattern
        self.matches = matches
        self.rejects = rejects
        self.why = why


CASES = [
    Case(
        "pypsa-drift.yml",
        r"grep -E 'Test run .* finished:'",
        [MUNIT_SUMMARY_CI, MUNIT_SUMMARY_PLAIN],
        [],
        "the sweep guard's count; anchored, it read a clean run as no run at all",
    ),
    Case(
        "pypsa-drift.yml",
        r",/Test run .* finished/p",
        [MUNIT_SUMMARY_CI, MUNIT_SUMMARY_PLAIN],
        [],
        "the failure extract ends at the summary; anchored, the range ran to end of file",
    ),
    Case(
        "pypsa-drift.yml",
        r"grep -cE '==> s [A-Za-z]'",
        [MUNIT_SKIPPED_PLAIN],
        [MUNIT_SUMMARY_CI, MUNIT_SUMMARY_PLAIN],
        "skipped cases exit 0 with a summary identical to a clean run, so they are counted separately",
    ),
    Case(
        "ci.yml",
        r"sed -n '/^instance /,$p' report.txt",
        [LP_HEADER],
        [],
        "the LP table; the previous pattern wanted size and status on one line and no line has both",
    ),
    Case(
        "ci.yml",
        r"sed -n '/^instance /,$p' milp-report.txt",
        [MILP_HEADER],
        [],
        "the MILP table",
    ),
]


def regex_of(pattern: str) -> str:
    """The regex out of a `grep -E '...'` or a `sed` address.

    Only the shapes used above, deliberately: a general shell parser here would
    be a second thing that can be wrong about what the workflow says. Each case
    pins one address, so a range is listed by the end address that has to match
    rather than by the whole expression.
    """
    grep = re.search(r"grep -[a-zA-Z]*E '([^']*)'", pattern)
    if grep:
        return grep.group(1)
    address = re.search(r"/([^/]*)/[,p]", pattern)
    if address:
        return address.group(1)
    raise ValueError(f"no pattern extracted from {pattern!r}")


def main() -> int:
    failures = 0

    for case in CASES:
        text = (ROOT / ".github" / "workflows" / case.workflow).read_text(encoding="utf-8")
        name = f"{case.workflow}: {case.pattern}"

        if case.pattern not in text:
            failures += 1
            print(f"FAIL  {name}\n      not found in the workflow -- if it was edited, update the sample here too")
            continue

        ok = True
        for regex in [regex_of(case.pattern)]:
            compiled = re.compile(regex)
            for sample in case.matches:
                if not compiled.search(sample):
                    # An anchored pattern against a coloured line is the whole
                    # subject of this file, so say which it was.
                    hint = " (anchored at ^, but the line starts with an escape)" if regex.startswith("^") else ""
                    failures += 1
                    ok = False
                    print(f"FAIL  {name}\n      /{regex}/ did not match {sample[:70]!r}{hint}")
            for sample in case.rejects:
                if compiled.search(sample):
                    failures += 1
                    ok = False
                    print(f"FAIL  {name}\n      /{regex}/ matched {sample[:70]!r}, which it must not")
        if ok:
            print(f"ok    {case.workflow}: {case.why}")

    # The samples have to stay real. `==> s` and the summary line come from sbt,
    # and if its output format ever changes these are only as good as the day
    # they were copied -- so at least assert they still look like what the
    # parsing expects, rather than having quietly rotted into prose.
    if "finished:" not in MUNIT_SUMMARY_CI or "\x1b[" not in MUNIT_SUMMARY_CI:
        failures += 1
        print("FAIL  the coloured summary sample no longer looks like sbt output")
    if "\x1b[" in MUNIT_SUMMARY_PLAIN:
        failures += 1
        print("FAIL  the plain summary sample is coloured, so the pair tests one case twice")
    if "\x1b[" in LP_HEADER:
        failures += 1
        print("FAIL  the report sample is coloured; the forked-JVM assumption is wrong")

    if failures:
        print(f"\n{failures} pattern check(s) failed", file=sys.stderr)
        return 1
    print(f"\n{len(CASES)} patterns checked against real sbt output")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
