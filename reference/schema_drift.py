"""Diff two PyPSA component schemas and say whether the port has to care.

The goldens are pinned, so they cannot drift on their own. What drifts is
*upstream*: PyPSA adds an attribute, changes a default, or moves one from static
to time-varying, and this repository carries on agreeing with a version nobody
runs any more. Nothing in CI can see that, because CI only ever reads the pinned
install.

So the drift workflow generates a schema from the newest PyPSA and hands both
files to this. Pure stdlib on purpose: it must run on a checkout with no
virtualenv, which is also what makes it testable without installing PyPSA at all.

    python3 reference/schema_drift.py reference/goldens/schema.json new-schema.json

Exit status is the whole point of it being a script rather than a report:

    0   nothing that can change an answer moved
    1   something did
    2   bad usage

A cosmetic change -- a reworded description -- is printed and does not fail. A
changed unit does fail: MW against kW is a factor of a thousand that no test in
this repository would catch, because both sides would be reading the same wrong
number out of the same file.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# Fields whose change can move a number. `type` decides how a column is stored
# and, for the varying ones, its shape; `default` is what an omitted column means,
# and the sweep in SchemaSweepSuite leans on several of them being inert; `status`
# is what separates an input from a computed output, which is the filter that
# whole sweep starts from.
MATERIAL = ("type", "default", "status", "varying", "unit")

# There is deliberately no attribute-level cosmetic comparison. `component_schema()`
# emits exactly `type`, `unit`, `default`, `status` and `varying` per attribute --
# no `description` -- so a walk over one would be unreachable against any schema
# this repository can produce, and a test for it would be validating a shape that
# cannot occur. Components *do* carry a description, and that comparison is real.


def load(path: Path) -> dict:
    """Read a schema, or exit 2.

    Two rather than one, and the difference matters more than it looks. 1 means
    "something drifted", and the workflow acts on it: it opens an issue telling
    maintainers that upstream changed. An unreadable file exiting 1 would produce
    that issue with an empty diff in it and the actual reason on stderr, which is
    a bug report about PyPSA for what is really a missing file.

    `OSError` rather than `FileNotFoundError` so a directory or a permission
    problem lands here too instead of escaping as a traceback.

    `UnicodeDecodeError` is caught alongside it because it is *not* an
    `OSError` -- it is a `ValueError`, and `Path.read_text()` raises it for a
    file that is not valid UTF-8 or that was truncated mid-multibyte. That is a
    plausible shape for a half-written download, and uncaught it escaped as a
    traceback and exited 1, which is the status reserved for drift: the workflow
    would have opened an issue saying upstream changed, with an empty diff in it.
    Truncation only lands in the `JSONDecodeError` branch when the cut happens to
    fall on an ASCII boundary.

    `encoding="utf-8"` rather than the default, which is the *locale's* encoding
    and makes both the classification and its test locale-dependent. Under a
    latin-1 runner `b"\\xff\\xfe{"` decodes cleanly to `ÿþ{` and comes out of the
    `JSONDecodeError` branch instead, so the fixture below would fail on a
    difference in the environment rather than in the behaviour. The real path is
    exposed too: a candidate schema from a newer PyPSA carrying a non-ASCII unit
    or description would be classified differently depending on the runner.
    """
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError) as e:
        print(f"schema_drift: cannot read {path}: {e}", file=sys.stderr)
        raise SystemExit(2)
    except json.JSONDecodeError as e:
        print(f"schema_drift: {path} is not JSON: {e}", file=sys.stderr)
        raise SystemExit(2)


def is_input(row: dict) -> bool:
    """The same filter the Scala sweep applies, and for the same reason.

    An output attribute is computed by the solve and never read from a file, so
    upstream adding one cannot make this port silently wrong -- it can only make
    it incomplete, which is loud.
    """
    return (row.get("type") or "").strip().lower() != "series" and "output" not in (
        row.get("status") or ""
    ).lower()


def diff(old: dict, new: dict) -> tuple[list[str], list[str], list[str]]:
    """Returns (material, cosmetic, new_inputs) as lines of report."""
    material: list[str] = []
    cosmetic: list[str] = []
    new_inputs: list[str] = []

    for gone in sorted(set(old) - set(new)):
        material.append(f"component removed: {gone} ({len(old[gone].get('attributes', {}))} attributes)")
    for added in sorted(set(new) - set(old)):
        attrs = new[added].get("attributes", {})
        material.append(f"component added: {added} ({len(attrs)} attributes)")
        new_inputs.extend(f"{added}.{a}" for a, row in sorted(attrs.items()) if is_input(row))

    for name in sorted(set(old) & set(new)):
        before, after = old[name], new[name]
        for field in ("list_name", "category"):
            if before.get(field) != after.get(field):
                material.append(f"{name}: {field} {before.get(field)!r} -> {after.get(field)!r}")
        if before.get("description") != after.get("description"):
            cosmetic.append(f"{name}: description reworded")

        oa, na = before.get("attributes", {}), after.get("attributes", {})
        for gone in sorted(set(oa) - set(na)):
            material.append(f"{name}.{gone}: attribute removed")
        for added in sorted(set(na) - set(oa)):
            row = na[added]
            kind = "input" if is_input(row) else "output"
            material.append(f"{name}.{added}: attribute added ({kind}, default {row.get('default')!r})")
            if is_input(row):
                new_inputs.append(f"{name}.{added}")

        for attr in sorted(set(oa) & set(na)):
            for field in MATERIAL:
                if oa[attr].get(field) != na[attr].get(field):
                    material.append(
                        f"{name}.{attr}: {field} {oa[attr].get(field)!r} -> {na[attr].get(field)!r}"
                    )

    return material, cosmetic, new_inputs


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: schema_drift.py PINNED.json CANDIDATE.json", file=sys.stderr)
        return 2

    pinned, candidate = Path(argv[0]), Path(argv[1])
    material, cosmetic, new_inputs = diff(load(pinned), load(candidate))

    if not material and not cosmetic:
        print("No schema drift: the component registries are identical.")
        return 0

    if material:
        print(f"## {len(material)} change(s) that can move an answer\n")
        for line in material:
            print(f"- {line}")
        print()

    if new_inputs:
        # Called out separately because these are the ones with a known next
        # step. Each is an attribute a network may now set that this port has
        # never seen, which is the exact shape of all six silent wrong answers
        # the original sweep turned up -- and each will arrive as an unaccounted
        # attribute the moment the sweep runs against this schema.
        print(f"## {len(new_inputs)} new input attribute(s)\n")
        print("Each needs the sweep's question asked of it: does PyPSA read it, and")
        print("does ignoring it change an answer? Handle, refuse, or rule on it in")
        print("`SchemaSweepSuite`.\n")
        for line in new_inputs:
            print(f"- `{line}`")
        print()

    if cosmetic:
        print(f"## {len(cosmetic)} cosmetic change(s)\n")
        for line in cosmetic:
            print(f"- {line}")
        print()

    return 1 if material else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
