"""HiGHS on an exported horizon, so the Scala ladder has a counterpart on the same LP.

`HorizonScaling` in `network-lopf`'s test sources measures what a long horizon costs
Prima, and NOTES records an external anchor beside it: NordPSA's `run630` solves three
years at two-hourly resolution in 62,000 s. Those two numbers were about to be compared,
and they are not comparable -- `run630` is `expand` mode at IPM 1e-6 with crossover on
sixteen threads, against a dispatch model at 1e-4 on one. Four differences, every one of
them in the same direction.

This closes that gap from the Python side. `HorizonScaling --export <dir>` writes each
rung as a PyPSA CSV directory through the same `CsvWriter` whose round-trip is asserted
byte-for-byte against pandas; this script reads one back with `pypsa.Network` and solves
it with HiGHS at a stated method, tolerance and thread count. Same LP, two
implementations, settings written down. It is the only arrangement in which a difference
between the two numbers is a statement about the solvers.

The flag names are HiGHS's own, and deliberately the same keys NordPSA's `config/zones.yaml`
carries under `solver:` -- `solver`, `run_crossover`, `ipm_optimality_tolerance`, `threads`
-- so a run can be made at exactly a NordPSA run's settings without translating anything.

What this does not measure, and must not be read as measuring:

  - **The stability requirements.** They live in an `extra_functionality` callback,
    PyPSA stores none of it on the network, and a reader therefore gets a strictly easier
    problem. That is the whole argument of `docs/pypsa-and-noaidi.md` and the reason the
    fixtures in this directory keep networks and answers in separate files. An exported
    rung compares the *dispatch* halves of the two implementations honestly and says
    nothing at all about the requirement rows.
  - **Iterations.** An interior-point iteration and a PDHG iteration are not the same unit
    and their counts should never be put in one column. Wall clock is the only common axis,
    which is why it is the only one reported on both sides.
  - **Model construction.** Build and solve are timed separately, because PyPSA's build
    goes through linopy and at 13,152 snapshots that is a real share of the total -- while
    the Scala side's `seconds` column times `Pdhg.solve` alone. Comparing one against the
    sum would hand the Scala side a head start it has not earned.

The `objective` column is the number each solver was handed and minimised, which is the
one that can be compared across the two implementations. It is **not** the system cost: on
a single-period network PyPSA 1.3.0 keeps the capital of the capacity the network came with
in `n.objective_constant`, outside `objective`, and the system cost is the sum. That
constant is printed beside the objective rather than folded into it, and the footer gives
the total for each row that has one. A row whose sunk column is zero is a dispatch problem;
a row where it is not carries a capital term that another network's plain objective does
not.

The variable and constraint counts are reported so the two formulations can be checked
against each other rather than assumed equal. They will not match exactly: the two models
bound a non-extendable dispatch and express a storage balance in their own ways. A *large*
divergence is a finding about the export or about one of the builds, and it would
invalidate any timing comparison made over it, so it is printed before the timings.

Run it on whatever `--export` wrote:

    .venv/bin/python horizon_highs.py <dir>/nordic-today-3x2h --threads 1

    # at run630's settings
    .venv/bin/python horizon_highs.py <dir>/* --solver ipm --crossover on \
        --tolerance 1e-6 --threads 16 --out highs-horizon.txt
"""

import argparse
import platform
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd
import pypsa

HEADER = (
    f"{'horizon':<22} {'snaps':>7} {'vars':>10} {'cons':>10} {'status':<12} "
    f"{'objective':>18} {'sunk capital':>16} {'build s':>9} {'solve s':>9}"
)


class Sink:
    """Prints a line and, when asked, flushes it to a file.

    The same device as the Scala side's, and for the reason `MilpReport.Sink` gives there
    at length: a solve of this size gets killed, a pipe is block-buffered, and a report
    that exists only in a buffer is a report nobody reads. One row per directory, written
    as that directory finishes.
    """

    def __init__(self, path: Path | None) -> None:
        self.handle = path.open("w") if path else None

    def line(self, text: str) -> None:
        print(text, flush=True)
        if self.handle:
            self.handle.write(text + "\n")
            self.handle.flush()

    def close(self) -> None:
        if self.handle:
            self.handle.close()


def versions() -> str:
    """The environment, stamped the way every fixture in this directory stamps it.

    A timing is a property of the solver build as much as of the model, and HiGHS arrives
    through `highspy` rather than through PyPSA's own pin -- so it is named here even
    though no fixture records it.
    """
    try:
        import highspy

        highs = getattr(highspy, "__version__", "present")
    except ImportError:  # pragma: no cover - the solver is a hard dependency of a run
        highs = "missing"
    return (
        f"versions: pypsa {pypsa.__version__}, pandas {pd.__version__}, "
        f"numpy {np.__version__}, python {sys.version.split()[0]}, highspy {highs}"
    )


def solve(directory: Path, options: dict, sink: Sink) -> dict:
    """Read one exported network, build it, solve it, and return the row.

    Reading is not timed. It is file parsing rather than either implementation's work, it
    is a large share of the total at 13,152 snapshots, and neither side's reported figure
    includes it.
    """
    print(f"reading {directory.name}", file=sys.stderr, flush=True)
    n = pypsa.Network()
    n.import_from_csv_folder(str(directory))

    print(f"building {directory.name}", file=sys.stderr, flush=True)
    started = time.monotonic()
    model = n.optimize.create_model()
    built = time.monotonic() - started

    print(f"solving {directory.name}", file=sys.stderr, flush=True)
    started = time.monotonic()
    status, condition = n.optimize.solve_model(
        solver_name="highs", solver_options=options
    )
    solved = time.monotonic() - started

    # `nvars`/`ncons` come off the linopy model rather than being counted from the network:
    # the point of the column is to compare what was *solved*, and a count derived from the
    # component tables would be this script's own arithmetic rather than the model's.
    # Two numbers, because `n.objective` is not the system cost and the system cost is not
    # what the other side reports.
    #
    # On a single-period network PyPSA 1.3.0 keeps the capital of the capacity the network
    # came with -- `capital_cost` times `p_nom_min` over the extendable assets -- in
    # `n.objective_constant`, outside `objective`. The system cost is the sum. But the
    # column that has to line up with the Scala side is `objective`, because that is what
    # each solver was handed and minimised, and measuring says they do: on this network's
    # wind expansion, -4,067,083,156.57 here against -4,067,103,384.10 from Prima, which is
    # 5.0e-6 apart at a 1e-4 tolerance.
    #
    # So `objective` is the comparable column and `sunk` is printed beside it rather than
    # folded in. A row whose sunk column is zero is a dispatch problem; a row where it is
    # not carries a capital term the plain objective of another network does not, and the
    # footer gives the total for whoever wants the system cost.
    constant = float(getattr(n, "objective_constant", 0.0) or 0.0)
    row = {
        "horizon": directory.name,
        "snapshots": len(n.snapshots),
        "vars": getattr(model, "nvars", -1),
        "cons": getattr(model, "ncons", -1),
        "status": f"{status}/{condition}",
        "objective": n.objective if status == "ok" else float("nan"),
        "constant": constant if status == "ok" else float("nan"),
        "total": n.objective + constant if status == "ok" else float("nan"),
        "build": built,
        "solve": solved,
    }
    sink.line(
        f"{row['horizon']:<22} {row['snapshots']:>7d} {row['vars']:>10d} "
        f"{row['cons']:>10d} {row['status']:<12} {row['objective']:>18.2f} "
        f"{row['constant']:>16.2f} {row['build']:>9.1f} {row['solve']:>9.1f}"
    )
    return row


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("directories", nargs="+", type=Path,
                        help="exported PyPSA CSV directories, as --export wrote them")
    # HiGHS's own option names, matching NordPSA's `solver:` block key for key.
    parser.add_argument("--solver", default="ipm", choices=("ipm", "simplex", "choose"),
                        help="HiGHS `solver`; ipm is NordPSA's expansion baseline")
    parser.add_argument("--crossover", default="on", choices=("on", "off"),
                        help="HiGHS `run_crossover`; on is what a vertex solution needs")
    parser.add_argument("--tolerance", type=float, default=1e-6,
                        help="HiGHS `ipm_optimality_tolerance`; simplex ignores it")
    parser.add_argument("--threads", type=int, default=1,
                        help="HiGHS `threads`; 1 to compare against a single-threaded run")
    parser.add_argument("--out", type=Path, default=None,
                        help="a file to flush each row into as it finishes")
    args = parser.parse_args()

    missing = [d for d in args.directories if not (d / "snapshots.csv").exists()]
    if missing:
        # Loudly, and before any solve. A directory that is not an export reads as an empty
        # network, solves in no time and reports an objective of zero, which looks like a
        # measurement.
        parser.error(
            "these are not exported networks -- no snapshots.csv in: "
            + ", ".join(str(d) for d in missing)
        )

    options = {
        "solver": args.solver,
        "run_crossover": args.crossover,
        "ipm_optimality_tolerance": args.tolerance,
        "threads": args.threads,
    }

    sink = Sink(args.out)
    try:
        sink.line(
            f"host: {platform.system()} {platform.machine()}, "
            f"python {platform.python_version()}"
        )
        sink.line(versions())
        sink.line(
            f"solver: highs {args.solver}, crossover {args.crossover}, "
            f"ipm_optimality_tolerance {args.tolerance:.1e}, threads {args.threads}"
        )
        sink.line("stability: absent -- an export cannot carry the callback, so this is "
                  "the dispatch half only")
        sink.line("")
        sink.line(HEADER)
        rows = [solve(d, options, sink) for d in args.directories]
        sink.line("")
        spent = sum(r["build"] + r["solve"] for r in rows)
        sink.line(
            f"total: {spent:.0f} s over {len(rows)} network(s), build included. Compare "
            "the solve column against the Scala side's seconds, and read neither as a "
            "solver verdict unless the vars and cons columns agree."
        )
        for r in rows:
            if r["constant"]:
                sink.line(
                    f"{r['horizon']}: system cost {r['total']:.2f} "
                    f"= objective {r['objective']:.2f} + sunk capital {r['constant']:.2f}"
                )
    finally:
        sink.close()


if __name__ == "__main__":
    main()
