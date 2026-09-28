"""Reference objectives for the BidLadder port, from NordPSA's own callback.

The ladder is mean-preserving in the margin: its offsets sum to zero, so at FULL output
the cost is unchanged. It only does something where the reservoir runs partially, and
what it is FOR is the side effect -- a flat bid gives bang-bang, all-or-nothing output as
the alternative crosses the single bid by an epsilon, and a rising curve spreads it.

So the fixture has to make the reservoir price-marginal AND make its trajectory the
unique optimum. The second half is the one that is easy to miss, and the first version of
this file missed it: a single competitor at a constant 45 EUR/MWh means displacing hydro
between any two hours where that competitor is on the margin changes the objective by
nothing, so every allocation with the same total is optimal. HiGHS then returns a vertex
of that face, which happens to be bang-bang, while an interior-point or first-order
solver returns something smooth -- both optimal, both with the same objective to 1e-11,
and the bang-bang statistic measures the solver rather than the formulation.

Hence the competitor's marginal cost VARIES over the horizon, and varies with no repeated
value: `MC_MIN + span*sin^2 + drift*t`, all 56 values distinct. The reservoir's water
value is then the one number that clears its cyclic energy balance, and it runs full in
exactly the hours where the competitor is dearer than that -- a unique solution, and one
that really is bang-bang.
"""

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pypsa

sys.path.insert(0, str(Path(__file__).parent / "NordPSA"))
from nordpsa.constraints.bid_ladder import hydro_bid_ladder  # noqa: E402

RES, DAYS, P_NOM = 3, 7, 900.0
UNIT = "Z hydro"
HYDRO_MC = 30.0      # the flat bid the ladder spreads around
INFLOW = 400.0       # 44% of p_nom, which is where NordPSA measures its fleet
LOAD = 1200.0
MC_MIN, MC_SPAN, MC_DRIFT = 16.0, 44.0, 0.13


def competitor_cost(k):
    """A marginal cost that sweeps hydro's bid band and repeats no value.

    The sweep is what makes the reservoir price-marginal; the drift is what makes the
    optimum unique. Without the drift the sine is symmetric about its peak and two hours
    share a cost, which is enough to reopen the degenerate face this fixture exists to
    close.
    """
    return MC_MIN + MC_SPAN * np.sin(k * np.pi / (DAYS * 24 // RES)) ** 2 + MC_DRIFT * k


def build():
    """One zone: a reservoir, a sliver of baseload, and a competitor whose price moves."""
    steps = DAYS * 24 // RES
    sn = pd.date_range("2023-01-02", periods=steps, freq=f"{RES}h")
    n = pypsa.Network()
    n.set_snapshots(sn)
    n.snapshot_weightings.loc[:, :] = float(RES)
    n.add("Bus", "b")
    n.add("Carrier", "hydro")
    n.add("Carrier", "AC")
    n.buses["carrier"] = "AC"
    n.add("Load", "l", bus="b", p_set=pd.Series(LOAD, index=sn))
    n.add(
        "StorageUnit", UNIT, bus="b", carrier="hydro", p_nom=P_NOM, max_hours=100,
        inflow=pd.Series(INFLOW, index=sn), cyclic_state_of_charge=True,
        p_min_pu=0.0, spill_cost=0.1, marginal_cost=HYDRO_MC,
    )
    n.add("Generator", "cheap", bus="b", p_nom=300, marginal_cost=1.0)
    n.add(
        "Generator", "swing", bus="b", p_nom=5000,
        marginal_cost=pd.Series([competitor_cost(k) for k in range(steps)], index=sn),
    )
    return n


def solve(tiers=None, width=None):
    n = build()
    if tiers is None:
        status, _ = n.optimize(solver_name="highs")
    else:
        cb = hydro_bid_ladder(tiers, width)
        n.optimize.create_model()
        cb(n, n.snapshots)
        status, _ = n.optimize.solve_model(solver_name="highs")
    if status != "ok":
        return {"status": status}
    d = n.storage_units_t.p_dispatch[UNIT]
    frac = d / P_NOM
    return {
        "status": status,
        "objective": float(n.objective),
        # The bang-bang measure the ladder is meant to reduce: hours pinned at the
        # ceiling or the floor.
        "at_ceiling_frac": float((frac > 0.999).mean()),
        "at_floor_frac": float((frac < 0.001).mean()),
        "dispatch_mean": float(d.mean()),
        "dispatch_std": float(d.std()),
        "dispatch": [float(x) for x in d],
    }


CASES = {
    "flat": (None, None),
    "k3-w36": (3, 36.0),
    "k3-w72": (3, 72.0),
    "k5-w36": (5, 36.0),
}

REFUSED = {"k1": (1, 36.0), "zero-width": (3, 0.0), "negative-width": (3, -5.0)}


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    nets = out / "networks"
    nets.mkdir(parents=True, exist_ok=True)

    results = {}
    for name, (k, w) in CASES.items():
        r = solve(k, w)
        r["tiers"], r["width"] = k, w
        if k is not None:
            r["offsets"] = [w * ((i + 0.5) / k - 0.5) for i in range(k)]
        results[name] = r
        print(f"  {name:10} obj={r.get('objective', float('nan')):12.2f} "
              f"ceiling={r.get('at_ceiling_frac', float('nan')):5.1%} "
              f"floor={r.get('at_floor_frac', float('nan')):5.1%} "
              f"std={r.get('dispatch_std', float('nan')):7.2f} "
              f"levels={len(set(round(x, 3) for x in r['dispatch']))}", flush=True)

    refused = {}
    for name, (k, w) in REFUSED.items():
        try:
            hydro_bid_ladder(k, w)
            refused[name] = {"refused": False}
            print(f"  {name:14} NOT refused by NordPSA")
        except ValueError as e:
            refused[name] = {"refused": True, "message": str(e)}
            print(f"  {name:14} refused: {str(e)[:52]}")

    flat, k3 = results["flat"], results["k3-w36"]
    pinned = lambda r: r["at_ceiling_frac"] + r["at_floor_frac"]
    spreads = pinned(flat) > pinned(k3) + 1e-9
    cheaper = k3["objective"] < flat["objective"] - 1e-6
    width_moves = max(abs(a - b) for a, b in
                      zip(results["k3-w72"]["dispatch"], k3["dispatch"]))
    tiers_move = max(abs(a - b) for a, b in
                     zip(results["k5-w36"]["dispatch"], k3["dispatch"]))
    print(f"\n  flat is bang-bang ({pinned(flat):.1%} of hours pinned), ladder spreads it: {spreads}")
    print(f"  ladder lowers the objective:          {cheaper}  (expected: partial output)")
    print(f"  a wider ladder moves dispatch by:     {width_moves:.2f} MW")
    print(f"  more tiers move dispatch by:          {tiers_move:.2f} MW")

    build().export_to_csv_folder(str(nets / "ladder-week"))
    (out / "ladder.json").write_text(json.dumps({
        "source": "NordPSA nordpsa/constraints/bid_ladder.py",
        "versions": {"pypsa": pypsa.__version__, "pandas": pd.__version__},
        "shape": {"snapshots": DAYS * 24 // RES, "resolution_hours": RES,
                  "p_nom": P_NOM, "unit": UNIT, "hydro_marginal_cost": HYDRO_MC,
                  "inflow_mw": INFLOW, "load_mw": LOAD},
        "cases": results,
        "refused": refused,
        "flat_is_bang_bang": spreads,
        "flat_pinned_frac": pinned(flat),
        "ladder_lowers_objective": cheaper,
        "width_moves_dispatch_mw": width_moves,
        "tiers_move_dispatch_mw": tiers_move,
        "network": "ladder-week",
    }, indent=2) + "\n")
    print("\nwrote networks/ladder-week and ladder.json")


if __name__ == "__main__":
    main()
