#!/usr/bin/env python3
"""Check that no Scala definition carries a stack of doc comments.

Scala attaches the doc comment *immediately* preceding a definition. Write two
in a row and only the last one attaches; the earlier ones are dropped in silence.
Verified rather than assumed -- two blocks over one `def`, run through
`scala-cli doc`, and only the second reaches the HTML. The compiler says nothing,
`-Wunused:all` says nothing, and scaladoc does not warn either, so nothing in the
toolchain notices.

The shape turns up when a definition is deleted or moved and its comment is left
behind: the orphan lands on whatever member follows and now documents the wrong
thing, while the text a reader wanted is gone. This repository has produced it
six times across four commits -- twice in the commit that was removing two other
instances of it -- which is what a defect class invisible to the compiler looks
like. Run against those commits the script reproduces all six.

Two rules, both about a comment that documents nothing:

  stacked   Two or more doc comments separated by nothing but blank lines. All
            but the last are dropped.
  dangling  A doc comment with no definition after it at all -- end of file, or
            only a closing brace or `end` marker. It documents nothing and the
            reader is told otherwise.

Both are errors. Neither has a legitimate use: a comment meant to sit above
another comment is a `//` line comment or a `/* */` block, and either of those is
left alone here.

Run it directly to check a working tree:

    python3 .github/scripts/check_orphan_docs.py

`.github/scripts/test_check_orphan_docs.py` exercises the scanner against
fixtures, which is why `check` takes a root and `doc_comments` takes text.
"""

from __future__ import annotations

import pathlib
import sys

# Directories that hold build output rather than source. `target` is sbt's, and
# it contains copies of the sources under `src_managed` and unpacked dependency
# sources -- scanning it would report other people's files.
SKIP_DIRS = {"target", ".git", ".bloop", ".metals", ".scala-build"}


class Scan:
    """A cursor over Scala source that knows what is code and what is not.

    Hand-written rather than regex-based because every construct this has to skip
    can contain the delimiters of the others: a `"/**"` in a string is not a
    comment, a `/** */` inside a `/* */` is not a doc comment because Scala block
    comments nest, and `'"'` is a quote that opens nothing. A regex that gets
    those right is longer than this and harder to be sure of.
    """

    def __init__(self, text: str) -> None:
        self.text = text
        self.i = 0
        self.n = len(text)

    def at(self, s: str) -> bool:
        return self.text.startswith(s, self.i)

    def skip_line_comment(self) -> None:
        end = self.text.find("\n", self.i)
        self.i = self.n if end < 0 else end

    def skip_block_comment(self) -> int:
        """Skip a `/* */`, honouring nesting. Returns the index just past it."""
        depth = 0
        while self.i < self.n:
            if self.at("/*"):
                depth += 1
                self.i += 2
            elif self.at("*/"):
                depth -= 1
                self.i += 2
                if depth == 0:
                    return self.i
            else:
                self.i += 1
        return self.i

    def skip_string(self) -> None:
        if self.at('"""'):
            self.i += 3
            end = self.text.find('"""', self.i)
            # Scala closes a triple-quoted string on the *last* of a run of
            # quotes, so `"""a""""` is `a"`. Walk past any extra.
            if end < 0:
                self.i = self.n
                return
            self.i = end + 3
            while self.i < self.n and self.text[self.i] == '"':
                self.i += 1
            return
        self.i += 1
        while self.i < self.n:
            c = self.text[self.i]
            if c == "\\":
                self.i += 2
                continue
            self.i += 1
            if c == '"' or c == "\n":
                return

    def skip_char_literal(self) -> bool:
        """Skip `'x'` or `'\\n'`. Returns False if this quote is not one.

        Scala 3 also spells macro quotes `'{` and `'[`, and a backtick-free
        `'ident` was once a symbol, so a lone quote is not evidence of a literal.
        Consuming one greedily would swallow real code, so the shape is checked
        before anything is consumed.
        """
        rest = self.text[self.i : self.i + 4]
        # From index 3, not 2: index 2 is the escaped character, and for `'\''`
        # that character *is* a quote. Searching from 2 finds it, consumes three
        # of the four characters and leaves a stray quote in the stream.
        if len(rest) >= 4 and rest[1] == "\\" and rest.find("'", 3) > 0:
            self.i += rest.find("'", 3) + 1
            return True
        if len(rest) >= 3 and rest[2] == "'" and rest[1] not in "\\\n":
            self.i += 3
            return True
        return False


def doc_comments(text: str) -> list[tuple[int, int, int, int]]:
    """Every `/** */` in `text`, as (start line, end line, start offset, end offset).

    Lines are 1-based and offsets are into `text`, the offset half being what the
    rules need: a comment's line says nothing about whether code shares that line
    with it, and both rules turn on exactly that.

    Only top-level comments: a doc comment nested inside a block comment is
    commented out and attaches to nothing, so it is not a finding.
    """
    scan = Scan(text)
    starts: list[tuple[int, int]] = []
    while scan.i < scan.n:
        c = scan.text[scan.i]
        if c == '"':
            scan.skip_string()
        elif c == "'":
            if not scan.skip_char_literal():
                scan.i += 1
        elif scan.at("//"):
            scan.skip_line_comment()
        elif scan.at("/*"):
            begin = scan.i
            is_doc = scan.at("/**") and not scan.at("/**/")
            end = scan.skip_block_comment()
            if is_doc:
                starts.append((begin, end))
        else:
            scan.i += 1

    # Offsets to line numbers in one pass rather than one `count` per comment.
    line_of = {}
    line = 1
    for idx, ch in enumerate(text):
        line_of[idx] = line
        if ch == "\n":
            line += 1
    line_of[len(text)] = line

    return [(line_of[a], line_of.get(b - 1, line), a, b) for a, b in starts]


def documents_nothing(text: str, after: int) -> bool:
    """True if nothing after offset `after` can carry a doc comment.

    "Nothing" means end of file or a scope ending -- a closing bracket or an
    `end` marker. A doc comment there is attached to no definition at all.

    Offsets rather than lines, so that `/** orphan */ }` is read the same as the
    same two tokens on separate lines. Working in whole lines missed the first,
    and a rule that depends on where someone pressed return is not a rule.

    Ordinary comments in between are stepped over. A `// revisit this` above the
    closing brace does not give the doc comment something to document, and
    stopping at one let the whole rule be defeated by a note.

    A following *doc* comment is deliberately not stepped over. That pair is the
    stacked rule's business, and skipping it would report the same orphan twice.
    """
    scan = Scan(text)
    scan.i = after
    while scan.i < scan.n:
        c = scan.text[scan.i]
        if c.isspace():
            scan.i += 1
        elif scan.at("//"):
            scan.skip_line_comment()
        elif scan.at("/*") and not (scan.at("/**") and not scan.at("/**/")):
            scan.skip_block_comment()
        else:
            break

    rest = scan.text[scan.i:]
    if not rest:
        return True
    if rest[0] in "}])":
        return True
    return rest == "end" or rest.startswith("end ") or rest.startswith("end\n")


def findings(text: str) -> list[tuple[int, str]]:
    """Every orphaned doc comment in one file, as (line, message)."""
    comments = doc_comments(text)
    out: list[tuple[int, str]] = []

    for (a_line, _, _, a_end), (b_line, _, b_start, _) in zip(comments, comments[1:]):
        # The span between the two comments, not the lines between them. A
        # `/** a */ val x = 1` line carries its own definition, and reading whole
        # lines called that pair stacked when the first attaches correctly.
        if not text[a_end:b_start].strip():
            out.append((
                a_line,
                f"doc comment is followed by another at line {b_line} with only blank lines between, "
                "so this one is dropped and never reaches the docs",
            ))

    # Every comment, not just the last. A comment left behind at the end of a
    # braced block is the shape this exists to catch -- a definition deleted and
    # its comment not -- and checking only the final comment in the file missed
    # it wherever another class followed.
    for line, _, _, end in comments:
        if documents_nothing(text, end):
            out.append((line, "doc comment has no definition after it, so it documents nothing"))

    return sorted(out)


def scala_files(root: pathlib.Path) -> list[pathlib.Path]:
    files = []
    for path in sorted(root.rglob("*.scala")):
        if any(part in SKIP_DIRS for part in path.relative_to(root).parts):
            continue
        files.append(path)
    return files


def check(root: pathlib.Path, out=sys.stdout, err=sys.stderr) -> int:
    files = scala_files(root)
    errors: list[str] = []

    for path in files:
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(root)
        for line, message in findings(text):
            errors.append(f"{rel}:{line}: {message}")

    if errors:
        print("orphaned doc comments -- each of these documents nothing:", file=err)
        for error in errors:
            print(f"  - {error}", file=err)
        print(
            "\nA comment meant to sit above another comment is a `//` line comment or a `/* */` block; "
            "only `/** */` attaches to a definition.",
            file=err,
        )
        return 1

    print(f"{len(files)} Scala files: every doc comment attaches to a definition", file=out)
    return 0


def main() -> int:
    return check(pathlib.Path(__file__).resolve().parents[2])


if __name__ == "__main__":
    raise SystemExit(main())
