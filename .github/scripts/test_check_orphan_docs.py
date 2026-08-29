#!/usr/bin/env python3
"""Tests for `check_orphan_docs.py`.

The check's failure mode is the one its subject has: passing while the thing it
names is broken. It is worth more than usual here, because the defect it looks
for is invisible to the compiler and to scaladoc, so a silently-broken check
would leave nothing at all watching.

Most of these cases are about *not* firing. The scanner has to tell a doc comment
from the same three characters inside a string, inside a nested block comment, or
after a quote that is a macro splice rather than a char literal, and a checker
that cries wolf on those gets disabled rather than fixed.

The fixtures are the shapes this repository actually produced, not invented ones:
a flush pair, a pair separated by a blank line, and a stack of three. All six
historical instances were one of those, every one was found by review rather than
by tooling, and the script reproduces all six when run against those commits.

Plain asserts and a `__main__`, matching `test_check_action_pins.py` and for the
same reason.

    python3 .github/scripts/test_check_orphan_docs.py
"""

from __future__ import annotations

import io
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from check_orphan_docs import Scan, check, doc_comments, findings  # noqa: E402


def run(files: dict[str, str]) -> tuple[int, str, str]:
    """Run `check` against a throwaway tree, returning (code, stdout, stderr)."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        for name, content in files.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        out, err = io.StringIO(), io.StringIO()
        code = check(root, out=out, err=err)
        return code, out.getvalue(), err.getvalue()


# --- the defect, in the shape it actually shipped in -------------------------

STACKED = """\
package probe

object Probe:

  /** The comment left behind when its definition moved. */
  /** The comment that actually attaches. */
  def target: Int = 1
"""

# GapRefusalSuite, SclopfSuite and RoundTripSuite all had the orphan separated
# from its successor by a blank line rather than sitting flush against it.
STACKED_WITH_BLANK = """\
package probe

object Probe:

  /** Orphaned, with a blank line after it.
    *
    * Multi-line, as every real instance was.
    */

  /** The one that attaches. */
  def target: Int = 1
"""

# SclopfSuite had two orphans over one member, not one.
STACKED_THREE = """\
package probe

object Probe:

  /** First orphan. */
  /** Second orphan. */
  /** Attaches. */
  def target: Int = 1
"""

DANGLING_AT_EOF = """\
package probe

object Probe:
  def target: Int = 1

  /** Nothing follows this at all. */
"""

DANGLING_BEFORE_END = """\
package probe

object Probe:
  def target: Int = 1

  /** Only an end marker follows. */

end Probe
"""

# The shape that distinguishes checking every comment from checking only the last:
# the orphan is at the end of a braced block that is not the last block in the
# file, so `DANGLING_AT_EOF` and `DANGLING_BEFORE_END` both pass an
# implementation that misses this one.
DANGLING_MID_FILE = """\
class A {
  def f = 1

  /** Left behind when `g` was deleted. */
}

class B {
  /** Real doc. */
  def h = 2
}
"""

# Same, with the orphan sharing its line with the brace. A rule that reads whole
# lines cannot see this one at all.
DANGLING_SAME_LINE = """\
class A {
  def f = 1
  /** Left behind. */ }

class B {
  /** Real doc. */
  def h = 2
}
"""

# A note between the orphan and the brace. Skipping only whitespace let a single
# `// revisit this` defeat the dangling rule entirely.
DANGLING_PAST_LINE_COMMENT = """\
class A {
  def f = 1

  /** Left behind when `g` was deleted. */
  // revisit this
}

class B {
  /** Real doc. */
  def h = 2
}
"""

DANGLING_PAST_BLOCK_COMMENT = """\
class A {
  def f = 1

  /** Left behind. */
  /* a note, not documentation */
}

class B {
  /** Real doc. */
  def h = 2
}
"""

# --- shapes that must not fire ----------------------------------------------

CLEAN = """\
package probe

/** A class, documented once. */
class Probe:

  /** A member, documented once. */
  def target: Int = 1

  /** Another member. */
  def other: Int = 2
"""

# A `//` or `/* */` above a doc comment is the documented way to write a note
# that is not documentation, so neither may be reported.
LINE_COMMENT_ABOVE = """\
package probe

object Probe:

  // A note for the reader of the source, not for the docs.
  /** The documentation. */
  def target: Int = 1
"""

BLOCK_COMMENT_ABOVE = """\
package probe

object Probe:

  /* Not a doc comment: one star. */
  /** The documentation. */
  def target: Int = 1
"""

# Scala block comments nest, so the inner `/**` is commented out and attaches to
# nothing. Reporting it would be reporting text that is already inert.
NESTED_IN_BLOCK_COMMENT = """\
package probe

object Probe:

  /* Disabled for now:
     /** The documentation. */
     def disabled: Int = 0
  */
  /** The live documentation. */
  def target: Int = 1
"""

DOC_MARKERS_IN_STRING = '''\
package probe

object Probe:

  /** The only doc comment here. */
  def target: String = "/** not a comment */ /** nor this */"
'''

DOC_MARKERS_IN_TRIPLE_STRING = '''\
package probe

object Probe:

  /** The only doc comment here. */
  def target: String =
    """
    /** not a comment */

    /** nor this */
    """
'''

# A quote that opens nothing: `'{` is a macro splice in Scala 3, and consuming it
# as a char literal would swallow the code after it and lose a real comment.
MACRO_QUOTE = """\
package probe

object Probe:

  /** Documented once. */
  inline def target: Int = ${ impl }

  /** Also documented once. */
  def other: Char = '"'
"""

CHAR_LITERAL_QUOTE = """\
package probe

object Probe:

  /** Documented once. */
  def quote: Char = '\\''

  /** Also documented once. */
  def slash: Char = '/'
"""

# Two comments that each attach to their own definition on the same line. Read as
# whole lines these look stacked, because the line where one ends is also the line
# where its definition sits.
SAME_LINE_DEFINITIONS = """\
class A:
  /** Documents x. */ val x = 1
  /** Documents y. */ val y = 2
"""

# `/**/` is an empty block comment, not a doc comment that opens.
EMPTY_BLOCK_COMMENT = """\
package probe

object Probe:
  /**/
  /** The only doc comment. */
  def target: Int = 1
"""

CASES: list[tuple[str, dict[str, str], int, str]] = [
    ("a stacked pair is reported", {"src/P.scala": STACKED}, 1, "is followed by another at line 6"),
    ("a stacked pair separated by a blank line is reported",
     {"src/P.scala": STACKED_WITH_BLANK}, 1, "never reaches the docs"),
    ("both orphans of a stack of three are reported",
     {"src/P.scala": STACKED_THREE}, 1, "line 6"),
    ("a doc comment at end of file is reported",
     {"src/P.scala": DANGLING_AT_EOF}, 1, "no definition after it"),
    ("a doc comment before only an end marker is reported",
     {"src/P.scala": DANGLING_BEFORE_END}, 1, "no definition after it"),

    ("a doc comment before a mid-file closing brace is reported",
     {"src/P.scala": DANGLING_MID_FILE}, 1, "no definition after it"),
    ("a doc comment sharing its line with a closing brace is reported",
     {"src/P.scala": DANGLING_SAME_LINE}, 1, "no definition after it"),

    ("a note between the orphan and the brace does not hide it",
     {"src/P.scala": DANGLING_PAST_LINE_COMMENT}, 1, "no definition after it"),
    ("a block-comment note between the orphan and the brace does not hide it",
     {"src/P.scala": DANGLING_PAST_BLOCK_COMMENT}, 1, "no definition after it"),

    ("a correctly documented file passes", {"src/P.scala": CLEAN}, 0, "every doc comment attaches"),
    ("definitions sharing a line with their comments are not stacked",
     {"src/P.scala": SAME_LINE_DEFINITIONS}, 0, "every doc comment attaches"),
    ("a line comment above a doc comment passes",
     {"src/P.scala": LINE_COMMENT_ABOVE}, 0, "every doc comment attaches"),
    ("a plain block comment above a doc comment passes",
     {"src/P.scala": BLOCK_COMMENT_ABOVE}, 0, "every doc comment attaches"),
    ("a doc comment nested in a block comment is not counted",
     {"src/P.scala": NESTED_IN_BLOCK_COMMENT}, 0, "every doc comment attaches"),
    ("doc markers inside a string are not comments",
     {"src/P.scala": DOC_MARKERS_IN_STRING}, 0, "every doc comment attaches"),
    ("doc markers inside a triple-quoted string are not comments",
     {"src/P.scala": DOC_MARKERS_IN_TRIPLE_STRING}, 0, "every doc comment attaches"),
    ("a macro quote does not swallow the following code",
     {"src/P.scala": MACRO_QUOTE}, 0, "every doc comment attaches"),
    ("char literals holding a quote or a slash are skipped",
     {"src/P.scala": CHAR_LITERAL_QUOTE}, 0, "every doc comment attaches"),
    ("an empty block comment does not open a doc comment",
     {"src/P.scala": EMPTY_BLOCK_COMMENT}, 0, "every doc comment attaches"),

    ("build output is not scanned",
     {"target/scala-3.7.4/src_managed/P.scala": STACKED}, 0, "every doc comment attaches"),
    ("a tree with no Scala files passes", {"README.md": "nothing here"}, 0, "0 Scala files"),
    ("the offending file and line are named",
     {"modules/a/src/P.scala": STACKED}, 1, "modules/a/src/P.scala:5"),
]


def unit_checks() -> list[str]:
    """Direct checks on the two helpers, for what `check`'s output cannot show."""
    problems = []

    # The scanner's line numbers are what a reader clicks, so an off-by-one here
    # is worse than a miss: it sends them to the wrong comment. Offsets are
    # checked by behaviour rather than by value, in the same-line cases above.
    lines = [(a, b) for a, b, _, _ in doc_comments(STACKED)]
    if lines != [(5, 5), (6, 6)]:
        problems.append(f"doc_comments(STACKED) lines gave {lines}, expected [(5, 5), (6, 6)]")

    lines = [(a, b) for a, b, _, _ in doc_comments(STACKED_WITH_BLANK)]
    if lines != [(5, 8), (10, 10)]:
        problems.append(f"doc_comments(STACKED_WITH_BLANK) lines gave {lines}, expected [(5, 8), (10, 10)]")

    # Offsets must bracket the comment exactly, since both rules test the text
    # between one comment's end and the next one's start.
    for start_line, _, begin, end in doc_comments(STACKED):
        span = STACKED[begin:end]
        if not (span.startswith("/**") and span.endswith("*/")):
            problems.append(f"offsets for the comment at line {start_line} gave {span!r}")

    # Exit code 1 is produced by any number of findings, so the dangling cases
    # above pass just as happily if the rule also flags the `/** Real doc. */`
    # that follows -- which is the natural failure mode of widening the rule from
    # "the last comment" to "every comment". Counts and lines are asserted here so
    # a false positive on the second comment fails the suite.
    for name, fixture, expected in (
        ("DANGLING_MID_FILE", DANGLING_MID_FILE, (4,)),
        ("DANGLING_SAME_LINE", DANGLING_SAME_LINE, (3,)),
        ("DANGLING_PAST_LINE_COMMENT", DANGLING_PAST_LINE_COMMENT, (4,)),
        ("DANGLING_PAST_BLOCK_COMMENT", DANGLING_PAST_BLOCK_COMMENT, (4,)),
    ):
        lines = tuple(line for line, _ in findings(fixture))
        if lines != expected:
            problems.append(f"findings({name}) reported lines {lines}, expected {expected}")

    # An escaped quote is four characters. Consuming three leaves a stray quote
    # that can re-enter the scanner at the wrong offset.
    scan = Scan("'" + chr(92) + "''")
    if not scan.skip_char_literal() or scan.i != 4:
        problems.append(f"skip_char_literal on an escaped quote stopped at {scan.i}, expected 4")

    # A stack of three is two findings, not one: each dropped comment is its own
    # loss, and reporting only the first would leave the second to a later run.
    got = findings(STACKED_THREE)
    if len(got) != 2:
        problems.append(f"findings(STACKED_THREE) gave {len(got)} findings, expected 2")

    # The clean file must produce nothing at all, not merely exit zero.
    got = findings(CLEAN)
    if got:
        problems.append(f"findings(CLEAN) gave {got}, expected none")

    return problems


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

    problems = unit_checks()
    for problem in problems:
        failures += 1
        print(f"FAIL  {problem}")
    if not problems:
        print("ok    scanner spans and finding counts")

    total = len(CASES) + 1
    if failures:
        print(f"\n{failures} of {total} cases failed", file=sys.stderr)
        return 1
    print(f"\n{total} cases passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
