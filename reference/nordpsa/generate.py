"""Reference objectives for the HydroOps port, generated from NordPSA's own network.

Builds the network `NordPSA/tests/unit/test_hydro_ops.py::build` uses -- one zone,
21 days at 3-hourly resolution, a reservoir with inflow and spill_cost against a
sinusoidal load -- exports each variant to netCDF, then solves it under the same
`hydro_operation_constraints` callback NordPSA applies in production.

The netCDF carries the network; this script's JSON carries what the callback did to
it. That split is the whole point of the port: the file alone cannot tell you, so a
reader that only round-trips the file solves a strictly easier problem.

Scenarios and parameters are NordPSA's, taken from its own tests rather than chosen
here. That matters: with cyclic state-of-charge the annual production equals the
inflow, so most parameters do not bind at all -- a floor below what the water
already forces, or a ceiling above it, changes nothing and would make a test that
passes while proving nothing. NordPSA varies `inflow` and `cheap_mw` per scenario
precisely to put each limit on the binding side.
"""

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pypsa

sys.path.insert(0, str(Path(__file__).parent / "NordPSA"))
from nordpsa.constraints import hydro_operation_constraints  # noqa: E402

RES, DAYS, P_NOM = 3, 21, 1000.0

# NordPSA's baseline operational config, from tests/unit/test_hydro_ops.py.
OC = {"min_hourly_frac": 0.10, "min_daily_frac": 0.20, "max_weekly_frac": 0.77}


def build(inflow_mw, cheap_mw=600, cheap_cost=0.5):
    """NordPSA's toy hydro network, verbatim from its test module."""
    sn = pd.date_range("2023-01-02", periods=DAYS * 24 // RES, freq=f"{RES}h")
    n = pypsa.Network()
    n.set_snapshots(sn)
    n.snapshot_weightings.loc[:, :] = float(RES)
    n.add("Bus", "b")
    n.add("Carrier", "hydro")
    n.add("Carrier", "AC")
    n.buses["carrier"] = "AC"
    load = 400 + 900 * np.sin(np.arange(len(sn)) * 2 * np.pi / (24 / RES)) ** 2
    n.add("Load", "l", bus="b", p_set=pd.Series(load, index=sn))
    n.add(
        "StorageUnit", "Z hydro", bus="b", carrier="hydro", p_nom=P_NOM, max_hours=500,
        inflow=pd.Series(float(inflow_mw), index=sn), cyclic_state_of_charge=True,
        p_min_pu=0.0, spill_cost=0.1, marginal_cost=1.0,
    )
    n.add("Generator", "g", bus="b", p_nom=5000, marginal_cost=40)
    n.add("Generator", "cheap", bus="b", p_nom=cheap_mw, marginal_cost=cheap_cost)
    return n


def measure(n, status):
    d = n.storage_units_t.p_dispatch["Z hydro"]
    w = n.snapshot_weightings.stores
    day = d.index.normalize()
    week = d.index.to_period("W-SUN").start_time
    spill_series = n.storage_units_t.spill
    spill = float((spill_series["Z hydro"] * w).sum()) if "Z hydro" in spill_series else 0.0
    return {
        "status": status,
        "objective": float(n.objective),
        "hydro_min_mw": float(d.min()),
        # The same normalised ratios NordPSA asserts on, so a disagreement reads as
        # "which window" rather than only "the number moved".
        "daily_min_frac": float(((d * w).groupby(day).sum() / (P_NOM * w.groupby(day).sum())).min()),
        "weekly_max_frac": float(((d * w).groupby(week).sum() / (P_NOM * w.groupby(week).sum())).max()),
        "spill_mwh": spill,
    }


def solve(inflow, cheap_mw, oc):
    n = build(inflow, cheap_mw=cheap_mw)
    if oc:
        cb = hydro_operation_constraints(oc)
        n.optimize.create_model()
        cb(n, n.snapshots)
        status, cond = n.optimize.solve_model(solver_name="highs")
    else:
        status, cond = n.optimize(solver_name="highs")
    if status != "ok":
        return {"status": status, "condition": str(cond)}
    return measure(n, status)


# (name, inflow, cheap_mw, config). Each has a Scala counterpart asserting the same
# numbers off the matching netCDF. The `*-ref` rows are the callback-free reference
# each binding case is measured against -- and are also what a reader of the netCDF
# alone would compute, which is why they are carried explicitly rather than implied.
CASES = [
    # Floors bind and cost more (NordPSA: test_floors_bind_and_cost_more)
    ("floors-ref",        450, 600, None),
    ("floors",            450, 600, OC),
    # A tighter daily floor costs more still (test_tighter_daily_floor_binds...)
    ("daily-45",          450, 600, {**OC, "min_daily_frac": 0.45}),
    # Weekly cap binds and forces spill (test_weekly_cap_binds_and_forces_spill)
    ("weekly-cap-ref",    900,   0, None),
    ("weekly-cap-40",     900,   0, {**OC, "max_weekly_frac": 0.40}),
    # Per-zone override beats the global ceiling. NordPSA's own case uses Z = 0.35,
    # which on this network lands at wmax = 0.325 -- under its own ceiling, so it
    # would pass just as well against code that ignored the override and applied the
    # global 0.77. Z = 0.25 binds instead (wmax pinned at 0.250, objective 5.5x the
    # global-only case), and `zone-override-ref` is the global-only reference that
    # makes the difference measurable rather than asserted.
    ("zone-override-ref", 900, 600, {**OC, "max_weekly_frac": 0.77}),
    ("zone-override-25",  900, 600, {**OC, "max_weekly_frac": 0.77,
                                     "max_weekly_frac_by_zone": {"Z": 0.25}}),
    # The bypass hinge, week by week (test_bypass_spill_hinge_holds_week_by_week)
    ("bypass-hinge",      600,   0, {**OC, "max_weekly_frac": 0.60,
                                     "bypass_spill": {"active": True,
                                                      "threshold_below_max": 0.10,
                                                      "coefficient": 0.15}}),
    # A floor the water cannot supply: cyclic SoC makes mean output = inflow/p_nom,
    # so 0.45 against 300 MW of inflow has no feasible schedule. Carried so the
    # Scala side can show it refuses rather than returning a number.
    ("infeasible-floor",  300, 600, {**OC, "min_daily_frac": 0.45}),
]


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    out.mkdir(parents=True, exist_ok=True)

    # One CSV folder per distinct network, not per case: the config is not in the
    # network, which is the fact this whole exercise turns on. CSV rather than
    # netCDF because that is the form `reference/goldens/networks` already uses and
    # `CsvReader` already reads -- the Scala side needs no new dependency to load it.
    # PyPSA's exporter calls `path.mkdir()` without parents, so this has to exist.
    nets = out / "networks"
    nets.mkdir(parents=True, exist_ok=True)
    networks = {}
    for _, inflow, cheap, _ in CASES:
        key = f"inflow{int(inflow)}-cheap{int(cheap)}"
        if key not in networks:
            n = build(inflow, cheap_mw=cheap)
            n.export_to_csv_folder(str(nets / key))
            networks[key] = key

    results = {}
    for name, inflow, cheap, oc in CASES:
        r = solve(inflow, cheap, oc)
        r["network"] = f"inflow{int(inflow)}-cheap{int(cheap)}"
        r["config"] = oc
        results[name] = r
        flag = "" if r["status"] == "ok" else "   <-- not ok"
        obj = f"{r['objective']:11.2f}" if "objective" in r else "          -"
        print(f"  {name:18} {obj}{flag}", flush=True)

    manifest = {
        "source": "NordPSA tests/unit/test_hydro_ops.py",
        "versions": {
            "pypsa": pypsa.__version__, "pandas": pd.__version__,
            "numpy": np.__version__, "python": sys.version.split()[0],
        },
        "shape": {
            "snapshots": DAYS * 24 // RES, "resolution_hours": RES, "days": DAYS,
            "p_nom": P_NOM, "first_snapshot": "2023-01-02 00:00:00",
            "stores_weighting": float(RES),
        },
        "networks": networks,
        "cases": results,
    }
    (out / "hydro.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"\nwrote {len(networks)} network(s) and hydro.json")


if __name__ == "__main__":
    main()
