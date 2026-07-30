"""Generate golden files from a pinned PyPSA, for the Scala port to check against.

Everything from L1 upwards is gated on matching these. The point is not that
PyPSA is authoritative about power systems — it is that the port must agree with
*this* implementation, including where it is idiosyncratic, or the outputs are
not interchangeable.

Two kinds of artefact are written:

  networks/<name>/     PyPSA's own CSV directory export, which the L1 reader and
                       writer must round-trip. This is the format, not a
                       convenience dump.
  results/<name>.json  Numerical outputs — power flow and optimisation — that
                       L2 must reproduce within documented tolerances.
  schema.json          Component metadata: every attribute, its dtype, default
                       and whether it may vary by snapshot. This is what the
                       typed store is built from, rather than from reading docs.

Run with `reference/.venv/bin/python reference/generate_goldens.py`.
"""

from __future__ import annotations

import json
import shutil
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
import pypsa

# Only the noise. DeprecationWarning and FutureWarning from PyPSA and pandas are
# the earliest signal of the API drift this whole pinning exercise exists to
# catch, so they are left to print.
warnings.filterwarnings("ignore", category=UserWarning)
warnings.filterwarnings("ignore", category=RuntimeWarning)

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "goldens"

# Small first, so a failure is diagnosable. `ac_dc_meshed` is the brief's
# starting point: meshed AC and DC in one network, which exercises the parts of
# the model a purely-AC example would not.
NETWORKS = {
    "ac-dc-meshed": pypsa.examples.ac_dc_meshed,
    "storage-hvdc": pypsa.examples.storage_hvdc,
}


def jsonable(value):
    """Render a value so the JSON is stable across runs and platforms.

    Order matters. `bool` subclasses `int`, so it is tested first; and plain
    Python `int` has to be handled explicitly rather than falling through to the
    catch-all, because PyPSA coerces int-typed defaults to `int` and stringifying
    them would encode the same concept two different ways depending on the
    declared type — `build_year` as `"0"` next to `p_nom` as `0.0`.
    """
    if value is None:
        return None
    if isinstance(value, (np.bool_, bool)):
        return bool(value)
    if isinstance(value, (np.integer, int)):
        return int(value)
    if isinstance(value, (np.floating, float)):
        # NaN and infinities are meaningful here (unset bounds), and JSON has
        # no literal for them, so they are tagged rather than silently dropped.
        if np.isnan(value):
            return {"$nan": True}
        if np.isinf(value):
            return {"$inf": "+" if value > 0 else "-"}
        return float(value)
    if isinstance(value, (pd.Timestamp,)):
        return value.isoformat()
    if isinstance(value, str):
        return value
    # Deliberately loud: a new dtype arriving with a version bump should stop the
    # run rather than land in the goldens as somebody's `str()`.
    raise TypeError(f"jsonable: unhandled {type(value).__name__}: {value!r}")


def frame_to_json(df: pd.DataFrame) -> dict:
    return {
        "index": [jsonable(i) for i in df.index],
        "columns": [str(c) for c in df.columns],
        "values": [[jsonable(v) for v in row] for row in df.to_numpy()],
    }


def text_or_none(value) -> str | None:
    """Optional text, with absence rendered as null rather than "nan".

    `str(float("nan"))` is `"nan"`, which reads as an ordinary string and is
    indistinguishable from a unit actually called that. Absence is spelled one
    way across this file.
    """
    if value is None or (isinstance(value, float) and np.isnan(value)):
        return None
    text = str(value).strip()
    return text or None


def component_schema() -> dict:
    """Attribute metadata for every component type PyPSA knows.

    Read from the type registry rather than from a Network instance: an
    instance only carries the components it actually has, so an empty network
    yields just the two whose standard types ship pre-populated. The store needs
    all sixteen.

    Generated from the pinned install rather than from the documentation,
    because the documentation describes a version and this describes the one in
    use.
    """
    from pypsa.components.types import all_components, get as get_type

    schema = {}
    for list_name in sorted(all_components):
        ctype = get_type(list_name)
        attrs = ctype.defaults
        schema[ctype.name] = {
            "list_name": ctype.list_name,
            "category": text_or_none(ctype.category),
            "description": text_or_none(ctype.description),
            "attributes": {
                str(name): {
                    "type": str(row.get("type", "")),
                    "unit": text_or_none(row.get("unit")),
                    "default": jsonable(row.get("default")),
                    "status": text_or_none(row.get("status")),
                    # Whether PyPSA permits this attribute to vary by snapshot.
                    # This is the split the store has to model: a static column,
                    # or a static default plus per-entity overrides.
                    "varying": bool(row.get("varying", False)),
                }
                for name, row in attrs.iterrows()
            },
        }
    return schema


def capture_network(name: str, build) -> dict:
    print(f"  {name}: building")
    n = build()

    target = OUT / "networks" / name
    if target.exists():
        shutil.rmtree(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    n.export_to_csv_folder(str(target))

    summary = {
        "name": name,
        "snapshots": [jsonable(s) for s in n.snapshots],
        "components": {},
    }
    for component in n.components:
        if len(component.static) == 0:
            continue
        # Only what the export actually wrote. A loaded network also carries
        # PyPSA's standard line and transformer types -- 59 and 14 rows of
        # library data -- which `export_to_csv_folder` correctly omits because
        # they are not network data. Reporting them here would describe the
        # in-memory object rather than the files, and a reader checked against
        # this manifest would be asked to invent them.
        if not (target / f"{component.list_name}.csv").exists():
            continue
        exported = target / f"{component.list_name}.csv"
        with exported.open() as handle:
            exported_header = handle.readline().rstrip("\n").split(",")

        summary["components"][component.name] = {
            "count": int(len(component.static)),
            # The columns actually in the file, which is what a reader and writer
            # can be held to. The in-memory frame carries more -- Bus has 14
            # columns loaded against 6 exported -- so recording that instead
            # would give the writer an unmeetable target.
            "exported_columns": exported_header,
            "in_memory_columns": [str(c) for c in component.static.columns],
            # Only the entities that actually vary get a time series; the rest
            # fall back to the static value. Reproducing that is the difference
            # between matching PyPSA's files and merely being equivalent.
            "varying": {
                str(attr): [str(c) for c in frame.columns]
                for attr, frame in component.dynamic.items()
                if frame.shape[1] > 0
            },
        }

    results = {}

    print(f"  {name}: linear power flow")
    try:
        n.lpf()
        results["lpf"] = {
            # Link flows are undefined under lpf unless p_set is set, so link_p0
            # is NaN throughout by construction. Recorded so a reviewer of a
            # future regeneration can tell a deliberate NaN block from a
            # regression.
            "link_p0_note": "NaN by construction: lpf does not determine link flow without p_set",
            "bus_v_ang": frame_to_json(n.buses_t.v_ang),
            "line_p0": frame_to_json(n.lines_t.p0),
            "link_p0": frame_to_json(n.links_t.p0),
            "generator_p": frame_to_json(n.generators_t.p),
        }
    except Exception as exc:  # noqa: BLE001 - recorded, not swallowed
        results["lpf"] = {"error": f"{type(exc).__name__}: {exc}"}

    print(f"  {name}: optimisation")
    try:
        m = build()  # fresh, so the LPF result does not seed the optimisation
        # `optimize` returns (status, condition) and does NOT raise on a
        # non-optimal termination, so an infeasible or truncated solve would
        # otherwise be written out as though it had converged -- and committed,
        # since regenerating after a version bump is the documented workflow.
        status, condition = m.optimize(solver_name="highs")
        if status != "ok" or condition != "optimal":
            raise RuntimeError(f"solve did not converge: status={status} condition={condition}")

        # `objective` is the model's objective value, not the system cost:
        # PyPSA defines total cost as objective + objective_constant, which is
        # why this reads negative on ac-dc-meshed. All three are captured so an
        # L2 comparison does not have to guess which one it is looking at.
        results["optimize"] = {
            "status": status,
            "condition": condition,
            "objective": jsonable(m.objective),
            "objective_constant": jsonable(m.objective_constant),
            "total_system_cost": jsonable(m.objective + m.objective_constant),
            "generator_p": frame_to_json(m.generators_t.p),
            "line_p0": frame_to_json(m.lines_t.p0),
            "link_p0": frame_to_json(m.links_t.p0),
            "bus_marginal_price": frame_to_json(m.buses_t.marginal_price),
        }
    except Exception as exc:  # noqa: BLE001
        results["optimize"] = {"error": f"{type(exc).__name__}: {exc}"}

    (OUT / "results").mkdir(parents=True, exist_ok=True)
    (OUT / "results" / f"{name}.json").write_text(json.dumps(results, indent=1, sort_keys=True))
    return summary


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)

    manifest = {
        "versions": {
            "pypsa": pypsa.__version__,
            "pandas": pd.__version__,
            "numpy": np.__version__,
            "python": sys.version.split()[0],
        },
        "networks": {},
    }

    failures: list[str] = []
    for name, build in NETWORKS.items():
        manifest["networks"][name] = capture_network(name, build)
        recorded = json.loads((OUT / "results" / f"{name}.json").read_text())
        failures += [f"{name}/{stage}: {body['error']}" for stage, body in recorded.items()
                     if isinstance(body, dict) and "error" in body]

    reference = capture_reference_schema()
    (OUT / "schema.json").write_text(json.dumps(reference, indent=1, sort_keys=True))
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True))

    print(f"\nwrote goldens to {OUT}")
    print(f"pypsa {pypsa.__version__}, pandas {pd.__version__}")

    if failures:
        # Recorded in the JSON *and* signalled here: a regeneration where a solve
        # failed should not exit successfully and leave the failure to be found
        # by reading the committed file.
        print(f"\n{len(failures)} stage(s) failed:", file=sys.stderr)
        for f in failures:
            print(f"  {f}", file=sys.stderr)
        return 1
    return 0


def capture_reference_schema() -> dict:
    return component_schema()


if __name__ == "__main__":
    raise SystemExit(main())
