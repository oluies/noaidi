#!/usr/bin/env python3
"""Tests for `drift_versions.py`.

Ahead of the check itself, for the reason the other three pairs in this directory give:
the failure mode of a reporting script is reporting something plausible and wrong, which
nothing downstream notices. The case that matters most here is the one that motivated the
script -- every version equal except one -- because a table that fails to mark it leaves
the reader exactly where they were before the script existed.
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("drift_versions.py")
sys.path.insert(0, str(SCRIPT.parent))

from drift_versions import collect, table  # noqa: E402

failures = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if not condition:
        failures.append(f"{name}: {detail}" if detail else name)


# The motivating case: PyPSA unchanged, one dependency moved.
rendered = table(
    {"pypsa": "1.4.0", "pandas": "3.0.6", "numpy": "2.5.4", "python": "3.14.8"},
    {"pypsa": "1.4.0", "pandas": "3.1.0", "numpy": "2.5.4", "python": "3.13.17"},
)
check("pypsa row is unmarked", "| pypsa | `1.4.0` | `1.4.0` |  |" in rendered, rendered)
check("the moved dependency is marked",
      "| pandas | `3.0.6` | `3.1.0` | **moved** |" in rendered, rendered)
check("an equal dependency is not marked",
      "| numpy | `2.5.4` | `2.5.4` |  |" in rendered, rendered)
check("a moved interpreter is marked too",
      "| python | `3.14.8` | `3.13.17` | **moved** |" in rendered, rendered)

# A package the manifest does not pin, and one the venv does not have.
rendered = table({"pypsa": "1.4.0"}, {"pypsa": "1.4.0", "linopy": "0.5.6"})
check("unpinned is named", "| linopy | `—` | `0.5.6` | not pinned |" in rendered, rendered)
rendered = table({"pypsa": "1.4.0", "scipy": "1.16.0"}, {"pypsa": "1.4.0"})
check("uninstalled is named", "| scipy | `1.16.0` | `—` | not installed |" in rendered, rendered)

# A package in neither emits no row at all, rather than a row of two dashes.
check("absent on both sides is omitted", "xarray" not in table({"pypsa": "1.4.0"}, {}),
      table({"pypsa": "1.4.0"}, {}))

# The header is load-bearing: the summary renders it as a table or as four lines of pipes.
check("header is present", table({}, {}).startswith("| | pinned | latest | |\n| --- |"),
      table({}, {}))

# `collect` reports this interpreter, which is the one real end-to-end assertion available
# without installing anything: python is always present and its version is knowable here.
found = collect()
check("collect reports python", found.get("python", "").startswith(
    ".".join(str(n) for n in sys.version_info[:2])), str(found))
check("collect omits what is absent", all(v for v in found.values()), str(found))

# And the CLI, since the workflow calls it rather than importing it.
with tempfile.TemporaryDirectory() as tmp:
    manifest = Path(tmp) / "manifest.json"
    manifest.write_text(json.dumps({"versions": {"pypsa": "1.4.0", "pandas": "3.0.6"}}))
    latest = Path(tmp) / "latest.json"
    latest.write_text(json.dumps({"pypsa": "1.4.0", "pandas": "3.1.0"}))
    done = subprocess.run(
        [sys.executable, str(SCRIPT), "table", "--manifest", str(manifest),
         "--latest", str(latest)],
        capture_output=True, text=True, check=True,
    )
    check("cli marks the moved package", "**moved**" in done.stdout, done.stdout)

    # The report step runs `if: always()`, so a missing latest file must not crash it.
    done = subprocess.run(
        [sys.executable, str(SCRIPT), "table", "--manifest", str(manifest),
         "--latest", str(Path(tmp) / "absent.json")],
        capture_output=True, text=True, check=True,
    )
    check("a missing latest file reads as not installed",
          "not installed" in done.stdout, done.stdout)

    done = subprocess.run([sys.executable, str(SCRIPT), "collect"],
                          capture_output=True, text=True, check=True)
    check("collect emits json", "python" in json.loads(done.stdout), done.stdout)

if failures:
    print(f"{len(failures)} failure(s):")
    for failure in failures:
        print(f"  - {failure}")
    raise SystemExit(1)
print("drift_versions: 13 checks pass")
