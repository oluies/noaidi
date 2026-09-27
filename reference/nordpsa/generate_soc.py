"""Is NordPSA's hydro_soc_initial a new constraint family, or an existing attribute?

NordPSA pins the reservoir's state of charge at the first snapshot with an
`extra_functionality` callback: `soc[t0] == frac * p_nom * max_hours`. PyPSA already
has an attribute that does exactly that -- `state_of_charge_set` -- and unlike the
callback it lives *on the network*, so it survives an export.

If the two are the same LP, then this family needs no new code in the port: it is a
config-to-network mapping, not a constraint to implement. This script tests that
rather than assuming it, three ways:

  A  callback      plain network + NordPSA's hydro_soc_initial_constraint
  B  attribute     network carrying state_of_charge_set at t0, no callback
  C  neither       the same network with the anchor removed, as a control

A == B is the equivalence. C != A is what stops the comparison being vacuous: if the
anchor does not bind, A and B agree for a reason that has nothing to do with either.
"""

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pypsa

sys.path.insert(0, str(Path(__file__).parent / "NordPSA"))
from nordpsa.constraints.soc import hydro_soc_initial_constraint  # noqa: E402

RES, DAYS, P_NOM, MAX_HOURS = 3, 21, 1000.0, 500.0
FRAC = 0.70  # NordPSA's config anchor, hydro_soc_initial
TARGET = FRAC * P_NOM * MAX_HOURS


def build(inflow_mw=450.0, anchor=False):
    """The hydro network from the HydroOps fixtures, optionally carrying the anchor."""
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
        "StorageUnit", "Z hydro", bus="b", carrier="hydro", p_nom=P_NOM,
        max_hours=MAX_HOURS, inflow=pd.Series(float(inflow_mw), index=sn),
        cyclic_state_of_charge=True, p_min_pu=0.0, spill_cost=0.1, marginal_cost=1.0,
    )
    n.add("Generator", "g", bus="b", p_nom=5000, marginal_cost=40)
    n.add("Generator", "cheap", bus="b", p_nom=600, marginal_cost=0.5)

    if anchor:
        # The attribute form: sparse, NaN everywhere except t0. This is what an export
        # can carry and what the callback cannot.
        soc_set = pd.DataFrame(np.nan, index=sn, columns=["Z hydro"])
        soc_set.iloc[0, 0] = TARGET
        n.storage_units_t.state_of_charge_set = soc_set
    return n


def solve(n, callback=None):
    if callback is not None:
        n.optimize.create_model()
        callback(n, n.snapshots)
        status, _ = n.optimize.solve_model(solver_name="highs")
    else:
        status, _ = n.optimize(solver_name="highs")
    if status != "ok":
        return {"status": status}
    return {
        "status": status,
        "objective": float(n.objective),
        "soc_first": float(n.storage_units_t.state_of_charge["Z hydro"].iloc[0]),
        "soc_last": float(n.storage_units_t.state_of_charge["Z hydro"].iloc[-1]),
    }


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    nets = out / "networks"
    nets.mkdir(parents=True, exist_ok=True)

    # NordPSA drives the callback from zones.yaml; this is the same shape inline.
    cfg = {"zones": {"Z": {"hydro_soc_initial": FRAC,
                           "hydro_p_nom_mw": P_NOM,
                           "hydro_max_hours": MAX_HOURS}}}

    a = solve(build(anchor=False), hydro_soc_initial_constraint(cfg))
    b = solve(build(anchor=True))
    c = solve(build(anchor=False))

    for name, r in (("A callback", a), ("B attribute", b), ("C control", c)):
        print(f"  {name:14} obj={r.get('objective', float('nan')):12.2f} "
              f"soc[0]={r.get('soc_first', float('nan')):10.1f} "
              f"soc[-1]={r.get('soc_last', float('nan')):10.1f}", flush=True)

    # Equivalence is asserted on the state of charge, not on the objective, and that
    # is a property of the constraint rather than a weakness of the fixture. Under
    # cyclic state-of-charge the anchor fixes the *level* while leaving the horizon's
    # water *balance* untouched -- total dispatch still equals inflow minus spill -- so
    # it cannot move cost in a single window. Every (max_hours, fraction) pair tried
    # returns the same objective to the cent. What the anchor exists for is the seam
    # between rolling-horizon windows, where one window's terminal level is the next
    # one's initial level and `terminal_value` prices it.
    equivalent = (
        abs(a["objective"] - b["objective"]) <= 1e-6 * max(1.0, abs(a["objective"]))
        and abs(a["soc_first"] - b["soc_first"]) <= 1e-6 * max(1.0, abs(a["soc_first"]))
        and abs(a["soc_last"] - b["soc_last"]) <= 1e-6 * max(1.0, abs(a["soc_last"]))
    )
    binds = abs(c["soc_first"] - a["soc_first"]) > 1.0
    cost_neutral = abs(c["objective"] - a["objective"]) <= 1e-6 * max(1.0, abs(a["objective"]))
    print(f"\n  A == B on objective and trajectory:  {equivalent}")
    print(f"  C != A on soc[0] (anchor binds):     {binds}")
    print(f"  C == A on objective (cost-neutral):  {cost_neutral}")

    # Only the attribute network is exported: it is the one a port can read.
    build(anchor=True).export_to_csv_folder(str(nets / "soc-anchor"))

    (out / "soc.json").write_text(json.dumps({
        "source": "NordPSA nordpsa/constraints/soc.py",
        "question": "is hydro_soc_initial expressible as state_of_charge_set?",
        "versions": {"pypsa": pypsa.__version__, "pandas": pd.__version__},
        "anchor": {"fraction": FRAC, "p_nom": P_NOM, "max_hours": MAX_HOURS,
                   "target_mwh": TARGET},
        "cases": {"callback": a, "attribute": b, "control": c},
        "equivalent": equivalent,
        "anchor_binds_on_soc": binds,
        "cost_neutral_in_one_cyclic_window": cost_neutral,
        "network": "soc-anchor",
    }, indent=2) + "\n")
    print("\nwrote networks/soc-anchor and soc.json")


if __name__ == "__main__":
    main()
