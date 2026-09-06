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
  program    what the test JVM prints itself, via `println`. sbt passes a
             program's stdout through untouched, so `^` is safe on it.

That second reason is about who wrote the line, not about `Test / fork`. An
earlier version of this file said forking was what made it safe; `primaValidation`,
which writes both reports pinned below, does not set `Test / fork` at all.

Getting that backwards in either direction is silent. Hence this.

Each case pins the pattern *as it appears in the workflow* and a sample of the
line it has to read. Editing the pattern fails the presence check, which is the
point: the sample has to be updated alongside it, by someone who has looked at
what the log actually contains.

The test-count pipeline is *not* here, and deliberately. Pinning its four stages
individually needed a hand-written sample for each -- which this file forbids in
the next paragraph, and which two of them violated -- and still left the
composition unpinned, so reordering the stages to point the `^`-anchored grep at
sbt's coloured line would have kept every case green. It lives in
`count_tests.sh` now, and `test_count_tests.py` runs the whole thing against
real logs. Executing the pipeline covers the patterns, their order and their
arithmetic at once, which is what this file can only approximate.

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

# Not captures, and labelled so nobody reads them as one. No coloured `==> X` or
# `==> s` has been seen: the sweep has passed every run since this was written,
# so neither line has appeared in a CI log to copy. Inventing where sbt puts the
# escapes would be asserting a guess -- an earlier draft of this file did exactly
# that and was wrong about it.
#
# What these do assert is weaker and sound: wherever an escape falls, the pattern
# still matches. Each variant is the plain line with an escape inserted at one of
# the positions colour could plausibly take, so a pattern demanding literal bytes
# at any of them fails here rather than in the one run that had something to
# report.
def _with_escapes(line: str) -> list[str]:
    marker = line.split(" ", 2)
    return [
        "\x1b[0J" + line,                                    # leading, as on the summary line
        "\x1b[31m" + marker[0] + " " + marker[1] + "\x1b[0m " + marker[2],   # the marker wrapped
        marker[0] + " " + marker[1] + " \x1b[36m" + marker[2],                # the name wrapped
    ]


MUNIT_SKIPPED_VARIANTS = _with_escapes(MUNIT_SKIPPED_PLAIN)
MUNIT_FAILURE_PLAIN = (
    "==> X org.noaidi.network.SchemaSweepSuite.every input attribute is accounted for 0.1s munit.FailException"
)
MUNIT_FAILURE_VARIANTS = _with_escapes(MUNIT_FAILURE_PLAIN)

# sbt's own per-project total, as opposed to the framework's per-suite lines
# above. Coloured the same way, and load-bearing for a different reason: it is
# the only counter that survives sbt 2's action cache replaying a whole test
# task, where the framework never runs and emits nothing.
SBT_SUMMARY_PLAIN = "[info] Passed: Total 10, Failed 0, Errors 0, Passed 10"
SBT_SUMMARY_CI = (
    "\x1b[0J\x1b[0m[\x1b[0m\x1b[0minfo\x1b[0m] \x1b[0m\x1b[0mPassed: Total 10, "
    "Failed 0, Errors 0, Passed 10\x1b[0m\x1b[0J"
)

# Printed by the report program itself via `println`. sbt passes a program's
# stdout through untouched, which is why `^` is safe on these and not on the
# framework's own lines above. Captured locally; the workflow keeps its `|| cat`
# fallback for the case where CI differs, so a wrong guess here degrades to
# publishing the whole file rather than an empty one.
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
        r"/==> X.*SchemaSweepSuite/,",
        [MUNIT_FAILURE_PLAIN] + MUNIT_FAILURE_VARIANTS,
        [],
        "the failure extract's start; it demanded literal bytes between the marker and the suite name",
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
        r"grep -cE '==> s'",
        [MUNIT_SKIPPED_PLAIN] + MUNIT_SKIPPED_VARIANTS,
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
    r"""The regex out of a `grep -E '...'` or a `sed` address.

    Only the shapes used above, deliberately: a general shell parser here would
    be a second thing that can be wrong about what the workflow says. Each case
    pins one address, so a range is listed by the address that has to match
    rather than by the whole expression.

    ==The dialect this assumes==

    `grep -E` is POSIX ERE, a `sed` address is BRE, and this evaluates both with
    Python's `re`. The patterns pinned here mean the same thing in all three, but
    the premise of the file is that someone will edit one -- and a BRE-only
    `\(...\)`, a literal `+`, a `\{n\}`, or a GNU extension like `\s` (already
    used by a neighbouring grep) would then be read with the wrong semantics and
    pass or fail for the wrong reason. Those constructs are rejected rather than
    reinterpreted: a loud refusal is worth more than a check that quietly means
    something else.
    """
    grep = re.search(r"grep -[a-zA-Z]*E '([^']*)'", pattern)
    if grep:
        return check_dialect(grep.group(1))
    address = re.search(r"/([^/]*)/[,p]", pattern)
    if address:
        return check_dialect(address.group(1))
    raise ValueError(f"no pattern extracted from {pattern!r}")


# Constructs whose meaning differs between ERE, BRE and Python's `re`.
DIALECT_TRAPS = (
    (r"\(", "BRE grouping; ERE and Python read this as a literal parenthesis"),
    (r"\{", "BRE interval; ERE and Python read this as a literal brace"),
    (r"\s", "a GNU extension, not POSIX; Python agrees by coincidence rather than by standard"),
    (r"\d", "a GNU/Python extension, not POSIX ERE or BRE"),
    (r"\+", "BRE one-or-more; ERE and Python read this as a literal plus"),
)


def check_dialect(regex: str) -> str:
    for construct, why in DIALECT_TRAPS:
        if construct in regex:
            raise ValueError(
                f"{regex!r} contains {construct!r}, which this cannot evaluate faithfully: {why}. "
                "Rewrite the workflow pattern in the common subset, or teach this file the dialect."
            )
    return regex


# The substitutions that turn the summary line into numbers. Pinned separately
# from the grep that finds the line, because the reported symptom was "`total`
# came out empty" and `total` is produced *here* -- the grep only supplies the
# input. A change to either half reintroduces the same silence, and until now
# only one half was covered.
EXTRACTIONS = (
    (r"sed -nE 's/.*[^0-9]([0-9]+) total.*/\1/p'", r".*[^0-9]([0-9]+) total.*", "5"),
    (r"sed -nE 's/.*[^0-9]([0-9]+) ignored.*/\1/p'", r".*[^0-9]([0-9]+) ignored.*", "0"),
)


def check_extractions() -> int:
    """Apply the workflow's own substitutions to the summary line and read the digits."""
    failures = 0
    text = (ROOT / ".github" / "workflows" / "pypsa-drift.yml").read_text(encoding="utf-8")
    for pinned, regex, expected in EXTRACTIONS:
        if pinned not in text:
            failures += 1
            print(f"FAIL  {pinned} not found in pypsa-drift.yml -- if it was edited, update the expectation here")
            continue
        compiled = re.compile(regex)
        for sample, label in ((MUNIT_SUMMARY_CI, "coloured"), (MUNIT_SUMMARY_PLAIN, "plain")):
            m = compiled.fullmatch(sample) or compiled.search(sample)
            got = m.group(1) if m else None
            if got != expected:
                failures += 1
                print(f"FAIL  {pinned} on the {label} summary gave {got!r}, expected {expected!r}")
    if not failures:
        print("ok    the summary line yields its counts, coloured and plain")
    return failures


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
        regex = regex_of(case.pattern)
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

    failures += check_extractions()

    if failures:
        print(f"\n{failures} pattern check(s) failed", file=sys.stderr)
        return 1
    print(f"\n{len(CASES)} patterns checked against real sbt output")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
