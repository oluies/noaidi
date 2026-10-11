#!/usr/bin/env python3
"""Check that every action is pinned to a floating major tag, and that Renovate stays actions-only.

Two conventions, neither enforced by anything else, and both load-bearing for how
dependency updates reach this repository.

==Every `uses:` pin is a floating major tag==

`actions/checkout@v7`, not `@v7.0.1` and not a SHA. That is what makes a patch or
minor release reach the workflow at run time with no pull request needed, and it
is the premise the Renovate configuration rests on: Renovate offers the next
*major* for a single-component tag and nothing else, so a floating pin gets
security and bug fixes automatically and a commit only when the major moves.

Pin an action to a SHA or an exact version and that stops being true in both
directions at once -- no run-time fix, and a bot that now proposes patch bumps
into a configuration written on the assumption that it will not. Either is a
change worth making deliberately; neither is a change worth making by accident,
which is why this is an error rather than a warning.

SHA pinning is the usual supply-chain hardening advice and it is a defensible
choice. It is simply not this repository's, and switching to it means changing
the Renovate configuration in the same commit -- `pinDigests`, and a review of
what `enabled: false` then hides. The error message says so.

==Renovate covers GitHub Actions and nothing else==

Scala dependencies belong to Scala Steward, which resolves the build; Renovate's
sbt manager reads `build.sbt` as text and could not resolve
`com.google.ortools:ortools-$p` or `org.lwjgl:undefined` when it tried. Under
Dependabot that split was structural -- it had no sbt ecosystem -- and under
Renovate it is one line of configuration, so the line is checked.

==What this used to be==

A cross-check of `dependabot.yml`'s five-entry `ignore` list against the same pin
convention: the list suppressed patch and minor updates, which is sound only for
a floating-major pin. Renovate needs no such list, so there is no list to
cross-check and the convention is asserted directly. The *warning* branch went
with it -- it existed for an action missing from the ignore list, which is no
longer a state that can exist.

Run it directly to check a working tree:

    python3 .github/scripts/check_action_pins.py

`.github/scripts/test_check_action_pins.py` exercises each branch against
fixtures, which is why `check` takes a root rather than reading a module-level
constant.
"""

from __future__ import annotations

import pathlib
import re
import sys

import json5
import yaml

FLOATING_MAJOR = re.compile(r"v\d+\Z")

# Renovate reads the first of these it finds. Only one may exist: two would be a
# silent precedence question, and the one that loses is the one someone edits.
CONFIG_NAMES = (
    "renovate.json5",
    "renovate.json",
    ".github/renovate.json5",
    ".github/renovate.json",
    ".renovaterc",
    ".renovaterc.json",
)


def scanned_files(root: pathlib.Path) -> list[pathlib.Path]:
    """Every file Renovate reads for `uses:` pins.

    The workflows directory plus an `action.yml` at the repository root -- the
    file that would make the repository itself an action.

    Composite actions under `.github/actions/` are deliberately *not* here. They
    carry `uses:` pins and they do run, but nothing in this repository has one,
    and a glob matching an empty directory is a check that silently covers
    nothing. Adding one means adding the glob in the same commit.
    """
    patterns = (
        ".github/workflows/*.yml",
        ".github/workflows/*.yaml",
        "action.yml",
        "action.yaml",
    )
    return sorted({path for pattern in patterns for path in root.glob(pattern)})


def uses_in(document: object) -> list[str]:
    """Every `uses:` value in a parsed workflow or action.

    Walks the tree rather than matching text. The regex this replaces matched its
    own source line -- a pattern containing "uses:" finds itself -- and counted
    commented-out steps as live.
    """
    found: list[str] = []

    def walk(node: object) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "uses" and isinstance(value, str):
                    found.append(value)
                else:
                    walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(document)
    return found


def renovate_config(root: pathlib.Path) -> tuple[pathlib.Path | None, object, list[str]]:
    """The one Renovate config, parsed, with any error about *finding* it."""
    present = [root / name for name in CONFIG_NAMES if (root / name).exists()]
    if not present:
        return None, {}, [
            "no Renovate configuration found, so nothing keeps the action pins current -- "
            f"expected one of {', '.join(CONFIG_NAMES)}"
        ]
    if len(present) > 1:
        return None, {}, [
            "several Renovate configurations exist, and Renovate reads only the first: "
            + ", ".join(str(p.relative_to(root)) for p in present)
        ]
    path = present[0]
    try:
        # json5 rather than json, because the config carries its own reasoning and
        # strict JSON has no comments. The parser is pinned in the workflow beside
        # PyYAML, for the reason given there.
        return path, json5.loads(path.read_text()), []
    except ValueError as error:
        return None, {}, [f"{path.relative_to(root)} does not parse: {error}"]


def check(root: pathlib.Path, out=sys.stdout, err=sys.stderr) -> int:
    files = scanned_files(root)
    if not files:
        print(
            f"no workflow or action files found under {root} -- has a directory moved?",
            file=err,
        )
        return 1

    used: dict[str, set[str]] = {}
    for path in files:
        for document in yaml.safe_load_all(path.read_text()):
            for reference in uses_in(document):
                # Local (`./.github/actions/x`) and container (`docker://`) uses
                # carry no version to pin.
                if "@" not in reference or reference.startswith(("./", "docker://")):
                    continue
                action, _, version = reference.rpartition("@")
                used.setdefault(action, set()).add(version)

    errors: list[str] = []

    for action in sorted(used):
        pinned = sorted(used[action])
        loose = [v for v in pinned if not FLOATING_MAJOR.fullmatch(v)]
        if loose:
            errors.append(
                f"{action} is pinned to {pinned}, and {loose} is not a floating major tag. "
                "A floating major is what delivers patch and minor fixes at run time, and it "
                "is what the Renovate configuration assumes: pin otherwise and those fixes "
                "stop arriving while a bot starts proposing them. Changing this convention "
                "means changing .github/renovate.json5 in the same commit."
            )

    path, config, problems = renovate_config(root)
    errors.extend(problems)

    if path is not None:
        managers = config.get("enabledManagers")
        if managers != ["github-actions"]:
            errors.append(
                f"{path.relative_to(root)} sets enabledManagers to {managers!r}; it must be "
                "['github-actions']. Scala dependencies belong to Scala Steward, which "
                "resolves the build -- Renovate's sbt manager reads build.sbt as text and "
                "could not resolve ortools' interpolated platform or the LWJGL set. Widening "
                "this puts two bots on the same dependencies."
            )

    if errors:
        print("the action pins and the Renovate configuration disagree:", file=err)
        for error in errors:
            print(f"  - {error}", file=err)
        return 1

    print(
        f"{len(files)} files, {len(used)} actions, all floating-major pinned; "
        f"{path.relative_to(root) if path else 'no config'} is actions-only",
        file=out,
    )
    return 0


def main() -> int:
    return check(pathlib.Path(__file__).resolve().parents[2])


if __name__ == "__main__":
    raise SystemExit(main())
