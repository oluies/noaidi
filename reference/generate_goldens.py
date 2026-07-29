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

warnings.filterwarnings("ignore")

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
    """Render a value so the JSON is stable across runs and platforms."""
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating, float)):
        # NaN and infinities are meaningful here (unset bounds), and JSON has
        # no literal for them, so they are tagged rather than silently dropped.
        if np.isnan(value):
            return {"$nan": True}
        if np.isinf(value):
            return {"$inf": "+" if value > 0 else "-"}
        return float(value)
    if isinstance(value, (np.bool_, bool)):
        return bool(value)
    if isinstance(value, (pd.Timestamp,)):
        return value.isoformat()
    if value is None or isinstance(value, str):
        return value
    return str(value)


def frame_to_json(df: pd.DataFrame) -> dict:
    return {
        "index": [jsonable(i) for i in df.index],
        "columns": [str(c) for c in df.columns],
        "values": [[jsonable(v) for v in row] for row in df.to_numpy()],
    }


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
            "category": str(ctype.category),
            "description": str(ctype.description),
            "attributes": {
                str(name): {
                    "type": str(row.get("type", "")),
                    "unit": str(row.get("unit", "")),
                    "default": jsonable(row.get("default")),
                    "status": str(row.get("status", "")),
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
        summary["components"][component.name] = {
            "count": int(len(component.static)),
            "static_columns": [str(c) for c in component.static.columns],
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
        m.optimize(solver_name="highs")
        results["optimize"] = {
            "objective": jsonable(m.objective),
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

    for name, build in NETWORKS.items():
        manifest["networks"][name] = capture_network(name, build)

    reference = capture_reference_schema()
    (OUT / "schema.json").write_text(json.dumps(reference, indent=1, sort_keys=True))
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True))

    print(f"\nwrote goldens to {OUT}")
    print(f"pypsa {pypsa.__version__}, pandas {pd.__version__}")
    return 0


def capture_reference_schema() -> dict:
    return component_schema()


if __name__ == "__main__":
    raise SystemExit(main())
