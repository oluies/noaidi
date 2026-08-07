#!/usr/bin/env python3
"""Check that `.github/dependabot.yml`'s ignore list matches how actions are pinned.

`dependabot.yml` suppresses patch and minor updates for a named set of actions.
That is sound only for an action pinned to a floating major tag, which picks up
such a release at run time: an action pinned to a SHA or an exact version gets
no run-time fix, so ignoring its updates would freeze it silently and for ever.

The premise is a convention, and a convention enforced nowhere is a comment. This
is the enforcement.

Two severities, deliberately, because the two directions are not equally bad:

  error    An ignored action is *not* floating-major pinned. That is the silent
           freeze above, and the reason this script exists.
  error    An ignore entry names something no scanned file uses, or does not
           suppress exactly patch and minor. An entry with no `update-types`
           suppresses majors too -- the same freeze by another route.
  warning  A floating-major action is missing from the list. Nothing breaks;
           Dependabot proposes patch bumps that change nothing at run time, which
           is noise rather than error, and `dependabot.yml` documents that as the
           default for anything newly added.

Run it directly to check a working tree:

    python3 .github/scripts/check_action_pins.py

`.github/scripts/test_check_action_pins.py` exercises each branch against
fixtures, which is why `check` takes a root rather than reading a module-level
constant.
"""

from __future__ import annotations

import fnmatch
import pathlib
import re
import sys

import yaml

# What an ignore entry has to suppress. Anything else -- most importantly an
# entry that omits `update-types` altogether -- also blocks majors, which is the
# freeze this script exists to prevent.
REQUIRED_UPDATE_TYPES = {"version-update:semver-patch", "version-update:semver-minor"}

FLOATING_MAJOR = re.compile(r"v\d+\Z")


def scanned_files(root: pathlib.Path) -> list[pathlib.Path]:
    """Every file Dependabot reads for `uses:` pins under `directory: "/"`.

    Matching its scope rather than guessing at a wider one. For the
    `github-actions` ecosystem that is the workflows directory plus an
    `action.yml` at the repository root -- the file that makes the repository
    itself an action. Both extensions, because GitHub accepts either.

    Composite actions under `.github/actions/` are deliberately *not* here.
    They carry `uses:` pins and they do run, but Dependabot does not track them
    at this directory, so warning about one would name a dependency it never
    proposes an update for. Adding one means adding a `directories:` entry to
    `dependabot.yml` and a glob here, together.
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


def matches(pattern: str, action: str) -> bool:
    """Dependabot's own matching: a case-insensitive glob, not an exact string."""
    return fnmatch.fnmatch(action.lower(), pattern.lower())


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

    floating = {a for a, versions in used.items() if all(FLOATING_MAJOR.fullmatch(v) for v in versions)}

    config = yaml.safe_load((root / ".github" / "dependabot.yml").read_text()) or {}
    updates = config.get("updates")
    if not isinstance(updates, list):
        print("dependabot.yml has no `updates` list", file=err)
        return 1

    entry = next((u for u in updates if u.get("package-ecosystem") == "github-actions"), None)
    if entry is None:
        print(
            "dependabot.yml has no `github-actions` entry, so nothing keeps the "
            "action pins current -- this check has nothing to enforce",
            file=err,
        )
        return 1

    ignores = entry.get("ignore", [])

    errors: list[str] = []
    warnings: list[str] = []
    covered: set[str] = set()

    for ignore in ignores:
        pattern = ignore["dependency-name"]
        types = set(ignore.get("update-types", []))
        if types != REQUIRED_UPDATE_TYPES:
            missing = REQUIRED_UPDATE_TYPES - types
            extra = types - REQUIRED_UPDATE_TYPES
            errors.append(
                f"'{pattern}' suppresses {sorted(types) or 'every update type'}; it must suppress exactly "
                f"patch and minor (missing {sorted(missing)}, unexpected {sorted(extra)}). Anything broader "
                "blocks the major bumps that do need a commit."
            )

        hit = {a for a in used if matches(pattern, a)}
        if not hit:
            errors.append(f"'{pattern}' matches no action any scanned file uses")
        covered |= hit

        for action in sorted(hit - floating):
            errors.append(
                f"{action} is ignored for patch and minor but pinned to {sorted(used[action])}. "
                "An exact or SHA pin gets no fix at run time, so this freezes it silently."
            )

    for action in sorted(floating - covered):
        warnings.append(
            f"{action} is pinned to a floating major but is not in the ignore list, so Dependabot will "
            "propose patch bumps that change nothing at run time."
        )

    for warning in warnings:
        print(f"::warning::{warning}", file=out)

    if errors:
        print("dependabot.yml disagrees with how the scanned files pin their actions:", file=err)
        for error in errors:
            print(f"  - {error}", file=err)
        return 1

    print(
        f"{len(files)} files, {len(used)} actions, {len(ignores)} ignore entries: "
        "every ignored action is floating-major pinned",
        file=out,
    )
    return 0


def main() -> int:
    return check(pathlib.Path(__file__).resolve().parents[2])


if __name__ == "__main__":
    raise SystemExit(main())
