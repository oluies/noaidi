"""Reference objectives for the TerminalValue port, from NordPSA's own callback.

The window is deliberately NON-cyclic, with a starting level. That is not incidental:
with `cyclic_state_of_charge` the terminal level is tied to the initial one, so the
draining a terminal value exists to prevent cannot happen and every profile would
price the same water. A rolling-horizon window is non-cyclic, which is the case this
is about.

Cases pair with `TerminalValueSuite` one for one. `none` is the control -- what the
window does when nothing prices the water at its end -- and the whole point is that
it differs.
"""

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pypsa

sys.path.insert(0, str(Path(__file__).parent / "NordPSA"))
from nordpsa.constraints.terminal_value import (  # noqa: E402
    DEFAULT_TERMINAL_PROFILE,
    hydro_terminal_value,
)

RES, DAYS, P_NOM, MAX_HOURS = 3, 7, 1000.0, 40.0
CAP = P_NOM * MAX_HOURS          # 40 000 MWh -- deliberately scarce, see below
# The water has to be SCARCE for lambda to compete with anything. Sized so the week's
# residual demand exceeds the starting level plus inflow: every MWh held back at T
# then displaces nothing and costs (40 - 1) = 39 EUR/MWh of the expensive generator,
# which is the opportunity cost each segment's lambda_k is measured against. At
# 200 max_hours the reservoir had more water than the week could use, every profile
# ended at the same level, and the comparison was vacuous.
START = 0.70 * CAP               # the hydro_soc_initial anchor
UNIT = "Z hydro"
# Two lambdas, because one is not enough to show the curve is concave rather than
# merely scaled. At 25 the whole linear curve sits under the 39 opportunity cost and
# holds nothing, while the concave curve's first segment (2.0 x 25 = 50) clears it and
# holds exactly one fifth. At 45 both hold, and the concave one holds LESS because its
# last segment (0.2 x 45 = 9) is not worth keeping.
LAMBDA_HOLDS_NOTHING_LINEAR = 25.0
LAMBDA_HOLDS_BOTH = 45.0
LAMBDA = LAMBDA_HOLDS_NOTHING_LINEAR


def build(inflow_mw=50.0):
    """A one-zone reservoir over a non-cyclic week, starting 70% full."""
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
        "StorageUnit", UNIT, bus="b", carrier="hydro", p_nom=P_NOM, max_hours=MAX_HOURS,
        inflow=pd.Series(float(inflow_mw), index=sn),
        # Non-cyclic, with a level to start from: the shape a rolling window has.
        cyclic_state_of_charge=False, state_of_charge_initial=START,
        p_min_pu=0.0, spill_cost=0.1, marginal_cost=1.0,
    )
    n.add("Generator", "g", bus="b", p_nom=5000, marginal_cost=40)
    n.add("Generator", "cheap", bus="b", p_nom=600, marginal_cost=0.5)
    return n


def solve(profile, lam=None):
    """Solve with NordPSA's terminal-value callback, or without one when None."""
    lam = LAMBDA if lam is None else lam
    n = build()
    if profile is None:
        status, _ = n.optimize(solver_name="highs")
    else:
        cb = hydro_terminal_value({UNIT: lam}, {UNIT: CAP}, profile)
        n.optimize.create_model()
        cb(n, n.snapshots)
        status, _ = n.optimize.solve_model(solver_name="highs")
    if status != "ok":
        return {"status": status}
    soc = n.storage_units_t.state_of_charge[UNIT]
    return {
        "status": status,
        # n.objective carries the terminal term, since the callback adds it to the
        # model's objective rather than to a report.
        "objective": float(n.objective),
        "soc_last": float(soc.iloc[-1]),
        "soc_last_frac": float(soc.iloc[-1] / CAP),
        "soc_min": float(soc.min()),
    }


# (name, lambda, profile). Each pairs with a case in the Scala suite.
CASES = {
    # No terminal term: the window drains the reservoir dry, which is the failure the
    # whole family exists to stop.
    "none": (None, None),
    # One segment, so linear -- NordPSA's own `[1.0]` shortcut. At this lambda it holds
    # nothing, exactly like the control, which is what makes the concave case below
    # evidence of the curve rather than of the reward.
    "linear-25": (LAMBDA_HOLDS_NOTHING_LINEAR, [1.0]),
    "concave-25": (LAMBDA_HOLDS_NOTHING_LINEAR, list(DEFAULT_TERMINAL_PROFILE)),
    # At the higher lambda both hold, and the concave one holds LESS: its cheapest
    # segment is not worth keeping. Two comparisons in opposite directions.
    "linear-45": (LAMBDA_HOLDS_BOTH, [1.0]),
    "concave-45": (LAMBDA_HOLDS_BOTH, list(DEFAULT_TERMINAL_PROFILE)),
    # Flat but multi-segment: concave to within the 1e-9 tolerance, so accepted.
    "flat-two-45": (LAMBDA_HOLDS_BOTH, [1.0, 1.0]),
}

# Refused rather than solved, by both sides.
REFUSED = {
    "rising": [1.0, 2.0],
    "empty": [],
}


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    nets = out / "networks"
    nets.mkdir(parents=True, exist_ok=True)

    results = {}
    for name, (lam, profile) in CASES.items():
        r = solve(profile, lam)
        r["profile"] = profile
        r["lambda"] = lam
        results[name] = r
        print(f"  {name:10} obj={r.get('objective', float('nan')):12.2f} "
              f"soc[-1]={r.get('soc_last', float('nan')):10.1f} "
              f"({r.get('soc_last_frac', float('nan')):5.1%})", flush=True)

    refused = {}
    for name, profile in REFUSED.items():
        try:
            hydro_terminal_value({UNIT: LAMBDA}, {UNIT: CAP}, profile)
            refused[name] = {"refused": False}
            print(f"  {name:10} NOT refused by NordPSA", flush=True)
        except ValueError as e:
            refused[name] = {"refused": True, "message": str(e)}
            print(f"  {name:10} refused: {str(e)[:60]}", flush=True)

    # The control's terminal level, as a fraction, is what says the cases are not
    # all pricing the same water.
    drains = results["none"]["soc_last_frac"] < 0.01
    linear_holds_nothing = results["linear-25"]["soc_last_frac"] < 0.01
    concave_holds_a_segment = results["concave-25"]["soc_last"] > results["none"]["soc_last"] + 1.0
    concave_holds_less_at_45 = (
        results["concave-45"]["soc_last"] < results["linear-45"]["soc_last"] - 1.0
    )
    print(f"\n  control drains the reservoir:              {drains}")
    print(f"  linear holds nothing at lambda 25:        {linear_holds_nothing}")
    print(f"  concave holds a segment at lambda 25:     {concave_holds_a_segment}")
    print(f"  concave holds LESS than linear at 45:     {concave_holds_less_at_45}")

    build().export_to_csv_folder(str(nets / "terminal-week"))
    (out / "terminal.json").write_text(json.dumps({
        "source": "NordPSA nordpsa/constraints/terminal_value.py",
        "versions": {"pypsa": pypsa.__version__, "pandas": pd.__version__},
        "shape": {
            "snapshots": DAYS * 24 // RES, "resolution_hours": RES, "days": DAYS,
            "p_nom": P_NOM, "max_hours": MAX_HOURS, "capacity_mwh": CAP,
            "state_of_charge_initial": START, "cyclic": False, "unit": UNIT,
        },
        "default_profile": list(DEFAULT_TERMINAL_PROFILE),
        "cases": results,
        "refused": refused,
        "control_drains": drains,
        "linear_holds_nothing_at_25": linear_holds_nothing,
        "concave_holds_a_segment_at_25": concave_holds_a_segment,
        "concave_holds_less_than_linear_at_45": concave_holds_less_at_45,
        "network": "terminal-week",
    }, indent=2) + "\n")
    print("\nwrote networks/terminal-week and terminal.json")


if __name__ == "__main__":
    main()
