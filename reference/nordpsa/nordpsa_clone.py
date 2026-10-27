"""Which NordPSA clone a fixture was generated from.

These fixtures are solved answers from NordPSA's own callbacks, carried here because an
export cannot carry them. So the answers are only meaningful against a stated upstream
revision: without one, a difference after a regeneration cannot be told apart from drift in
the port, which is the one thing this directory exists to measure. Every generator records
what `revision` returns under `versions.nordpsa`.

The versions already recorded beside it -- PyPSA, pandas, numpy, Python -- pin the solver and
the array libraries. This pins the model whose constraints are being reproduced, which is the
one that had been left to a reader's assumption.
"""

import subprocess
from pathlib import Path


def revision(root: Path) -> dict:
    """The clone's commit, the date it was committed, and whether its tracked files differ.

    `dirty` deliberately ignores untracked files: a scratch script or a plot left beside a
    clone does not change what the callbacks did, and reporting it would make every fixture
    generated on a working machine look suspect for no reason. A modified *tracked* file does
    change it, and that is worth recording.

    A directory with no git metadata returns `unknown` rather than raising. The fixture is
    still worth writing then -- it simply cannot say what produced it, which is the state
    every one of these files was in before this was added.
    """

    def git(*args: str) -> str:
        done = subprocess.run(
            ("git", "-C", str(root), *args), capture_output=True, text=True, check=True
        )
        return done.stdout.strip()

    try:
        return {
            "commit": git("rev-parse", "HEAD"),
            "committed": git("log", "-1", "--format=%cs"),
            "dirty": bool(git("status", "--porcelain", "--untracked-files=no")),
        }
    except (subprocess.CalledProcessError, OSError):
        return {"commit": "unknown", "committed": "unknown", "dirty": None}
