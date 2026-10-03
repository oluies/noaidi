"""Reference answers for the NordPSA families applied to a SECURITY-CONSTRAINED dispatch.

Each family has its own fixture already, and each family's suite asserts one invariant it
cannot itself exercise: that every row the family emits keeps the standard-form index its
original row had. `Sclopf.build` rebuilds the base model row by row and is the only thing
that depends on that -- so until the families could be passed to it, the invariant had three
assertions and no consumer. Two of them, `TerminalValue` and `BidLadder`, emit an equality,
which has to land before the first inequality or every one of its rows is misindexed. That
defect has appeared three times in this code base, once in the commit that removed the
previous instance.

So this fixture exists to make those assertions about something. It is one network, and the
requirement on it is harder than for any single family: the security constraint has to bind,
each family has to bind, AND the two together have to be tighter than either alone. A case
where the family makes the security rows slack proves only that the solver can add rows.

The network is the shape `reference/goldens/networks/sclopf-triangle` uses -- three buses in a
triangle, so every line's removal leaves it connected and all three are credible
contingencies -- with the generator at the cheap bus replaced by a reservoir and wind added,
because the families act on reservoirs and converters and the golden has neither.

PyPSA has no `extra_functionality` hook on `optimize_security_constrained`, so the callback is
run by wrapping `solve_model`: PyPSA builds the dispatch model AND its own security rows, then
the callback adds its rows, then PyPSA solves. Nothing here reimplements the outage factors,
which is the point -- a reimplementation could agree with itself and disagree with PyPSA.
"""

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pypsa

sys.path.insert(0, str(Path(__file__).parent / "NordPSA"))
from nordpsa.analysis.stability import stability_data, stability_tech      # noqa: E402
from nordpsa.constraints import hydro_operation_constraints                # noqa: E402
from nordpsa.constraints.bid_ladder import hydro_bid_ladder                # noqa: E402
from nordpsa.constraints.stability import stability_constraints            # noqa: E402
from nordpsa.constraints.terminal_value import hydro_terminal_value        # noqa: E402

SD = stability_data()
RES, DAYS = 3, 7
STEPS = DAYS * 24 // RES
S_NOM = 150.0
UNIT = "A hydro"
P_NOM, MAX_HOURS = 400.0, 40.0
CAPACITY = P_NOM * MAX_HOURS
PROFILE = [2.0, 1.5, 1.2, 1.0, 0.2]      # NordPSA's default terminal-value profile


def build():
    """A triangle with a reservoir, a merit order and some wind.

    `s_nom` is 150 on purpose and the value was swept for it: at 200 the security rows are
    slack and every case below collapses onto the plain answer, and at 130 the secure problem
    is feasible but so tight that no family can move it. At 150 the plain optimum costs
    336,000 and the secure one 448,034.
    """
    sn = pd.date_range("2023-01-02", periods=STEPS, freq=f"{RES}h")
    n = pypsa.Network()
    n.set_snapshots(sn)
    n.snapshot_weightings.loc[:, :] = float(RES)
    for b in ("A", "B", "C"):
        n.add("Bus", b, carrier="AC", v_nom=380.0)
    for a, b in (("A", "B"), ("B", "C"), ("A", "C")):
        n.add("Line", f"{a}{b}", bus0=a, bus1=b, x=0.1, r=0.0, s_nom=S_NOM)
    for c in ("hydro", "gas", "wind_onshore"):
        n.add("Carrier", c)
    # Non-cyclic and starting 70% full, because `terminal_value` prices the level at T and a
    # cyclic window ties that to the initial one -- the same reason its own fixture is
    # non-cyclic.
    n.add("StorageUnit", UNIT, bus="A", carrier="hydro", p_nom=P_NOM, max_hours=MAX_HOURS,
          state_of_charge_initial=0.7 * CAPACITY, cyclic_state_of_charge=False,
          inflow=pd.Series(100.0, index=sn), p_min_pu=0.0, spill_cost=0.1,
          marginal_cost=10.0)
    n.add("Generator", "B gas", bus="B", carrier="gas", p_nom=300, marginal_cost=40.0)
    n.add("Generator", "C dear", bus="C", carrier="gas", p_nom=300, marginal_cost=90.0)
    cf = 0.3 + 0.5 * np.sin(np.arange(STEPS) * 2 * np.pi / 16) ** 2
    n.add("Generator", "C wind", bus="C", carrier="wind_onshore", p_nom=100,
          p_max_pu=pd.Series(cf, index=sn), marginal_cost=0.0)
    n.add("Load", "lb", bus="B",
          p_set=pd.Series(120 + 25 * np.sin(np.arange(STEPS) * 2 * np.pi / 8), index=sn))
    n.add("Load", "lc", bus="C",
          p_set=pd.Series(110 + 20 * np.cos(np.arange(STEPS) * 2 * np.pi / 8), index=sn))
    return n


# --------------------------------------------------------------------------- the callbacks

def callback_for(config):
    """One case's `extra_functionality`, from the same dict the port reads."""
    parts = []
    if config.get("hydro"):
        parts.append(hydro_operation_constraints(config["hydro"]))
    if config.get("terminal"):
        t = config["terminal"]
        parts.append(hydro_terminal_value({UNIT: t["lambda"]}, {UNIT: CAPACITY},
                                          t.get("profile")))
    if config.get("ladder"):
        parts.append(hydro_bid_ladder(config["ladder"]["tiers"], config["ladder"]["width"]))
    if config.get("stability"):
        st = config["stability"]
        sd = dict(SD)
        if "exempt" in st:
            sd["scr_exempt"] = st["exempt"]
        parts.append(stability_constraints(sd, st.get("ek_system_gws"),
                                           st.get("floors") or {}, st.get("penalty"),
                                           st.get("scr_min")))
    if not parts:
        return None

    def combined(n, snapshots):
        for part in parts:
            part(n, snapshots)
    return combined


INTERIOR = {"solver": "ipm", "run_crossover": "off", "presolve": "off"}


def solve(config, secure, options=None):
    """One case, with or without the security rows.

    The callback is run by wrapping `solve_model` rather than by rebuilding PyPSA's outage
    loop: `optimize_security_constrained` creates the model, adds its own security rows and
    then solves, so a wrapper on the last step gets a model that already has them.
    """
    n = build()
    callback = callback_for(config)
    kw = {"solver_name": "highs"}
    if options:
        kw["solver_options"] = options
    if secure:
        if callback is not None:
            original = n.optimize.solve_model

            def patched(**inner):
                callback(n, n.snapshots)
                return original(**inner)
            n.optimize.solve_model = patched
        status, condition = n.optimize.optimize_security_constrained(**kw)
    elif callback is None:
        status, condition = n.optimize(**kw)
    else:
        n.optimize.create_model()
        callback(n, n.snapshots)
        status, condition = n.optimize.solve_model(**kw)
    return n, measure(n, status, condition)


def measure(n, status, condition):
    out = {"status": status, "condition": str(condition)}
    if status != "ok":
        return out
    out["objective"] = float(n.objective)
    out["dispatch"] = {name: [float(x) for x in n.generators_t.p[name]]
                       for name in n.generators.index}
    out["discharging"] = [float(x) for x in n.storage_units_t.p_dispatch[UNIT]]
    out["state_of_charge"] = [float(x) for x in n.storage_units_t.state_of_charge[UNIT]]
    out["flow"] = {name: [float(x) for x in n.lines_t.p0[name]] for name in n.lines.index}
    return out


def determined(simplex, interior, tol=1e-4):
    """Which series both methods agree on, so the port may assert them."""
    if simplex.get("status") != "ok" or interior.get("status") != "ok":
        return {"probed": False}
    out = {"probed": True,
           "same_objective": abs(simplex["objective"] - interior["objective"])
           <= 1e-6 * max(1.0, abs(simplex["objective"]))}

    def agree(a, b):
        scale = max(1.0, max(abs(x) for x in a) if a else 1.0)
        return all(abs(x - y) <= tol * scale for x, y in zip(a, b))

    for block in ("dispatch", "flow"):
        out[block] = {k: agree(v, (interior.get(block) or {}).get(k, []))
                      for k, v in (simplex.get(block) or {}).items()}
    for block in ("discharging", "state_of_charge"):
        out[block] = agree(simplex[block], interior[block])
    return out


# One entry per case. `hydro`, `terminal`, `ladder` and `stability` are the same dicts the
# port reads back, so neither side restates a configuration.
CASES = {
    # The control: no family. Security alone, which is what every case below is measured
    # against.
    "none": {},
    # A floor under hourly output. Chosen because it is the one HydroOps setting where all
    # three conditions hold at once -- a weekly CEILING tight enough to bind makes the
    # security rows slack, which would prove only that rows can be added.
    "hydro": {"hydro": {"min_hourly_frac": 0.35, "min_daily_frac": 0.0,
                        "max_weekly_frac": 0.0}},
    # A terminal water value, which adds COLUMNS and an equality. Under the plain dispatch it
    # changes nothing -- the reservoir drains to zero anyway, and a value on an empty
    # reservoir is worth nothing -- so this case binds only once security holds water back.
    # That is the interaction worth having.
    "terminal-linear": {"terminal": {"lambda": 20.0, "profile": [1.0]}},
    # 10, not 20: at 20 the concave curve holds so much water that the security rows go slack
    # and plain and secure return the same number, which is a case about neither constraint.
    "terminal-concave": {"terminal": {"lambda": 10.0, "profile": PROFILE}},
    # A bid ladder: columns and an equality per snapshot, which is the heaviest of the three
    # for the row copy.
    "ladder": {"ladder": {"tiers": 3, "width": 36.0}},
    # Grid strength, which adds columns and inequalities and reaches the wind.
    "stability-scr": {"stability": {"scr_min": 1.0, "exempt": []}},
    # Rotational energy, which forces synchronous plant online.
    "stability-ek": {"stability": {"ek_system_gws": 2.0}},
    # Two families at once on top of security, which is the arrangement where a row copy that
    # got one family's ordering right and another's wrong shows up.
    "hydro-and-stability": {"hydro": {"min_hourly_frac": 0.35, "min_daily_frac": 0.0,
                                      "max_weekly_frac": 0.0},
                            "stability": {"scr_min": 1.0, "exempt": []}},
    # And three, including both families that emit an equality.
    "all-three": {"hydro": {"min_hourly_frac": 0.35, "min_daily_frac": 0.0,
                            "max_weekly_frac": 0.0},
                  "terminal": {"lambda": 20.0, "profile": [1.0]},
                  "ladder": {"tiers": 3, "width": 36.0}},
    # All four. `all-three` leaves `stability` out, so until this case existed the ONE
    # arrangement nothing covered was every family on at once -- and it is the arrangement
    # where the row-ordering defect has the most room, because `Stability` is the family
    # emitted LAST in `Lopf.build` and emits inequalities only. A copy that got the three
    # equality-emitting families right and put stability's rows on the wrong side of the
    # split would pass `all-three` and every single-family case.
    #
    # `scr_min` rather than `ek_system_gws`: grid strength reaches the wind, which is what
    # the reservoir displaces here, so it interacts with the other three rather than
    # constraining a disjoint part of the network. Rotational energy forces synchronous
    # plant online, which on this fixture is the reservoir itself -- so it would partly
    # duplicate `hydro`'s hourly floor and the case would be weaker than its name.
    "all-four": {"hydro": {"min_hourly_frac": 0.35, "min_daily_frac": 0.0,
                           "max_weekly_frac": 0.0},
                 "terminal": {"lambda": 20.0, "profile": [1.0]},
                 "ladder": {"tiers": 3, "width": 36.0},
                 "stability": {"scr_min": 1.0, "exempt": []}},
}


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    nets = out / "networks"
    nets.mkdir(parents=True, exist_ok=True)
    build().export_to_csv_folder(str(nets / "sclopf-families"))

    cases = {}
    for name, config in CASES.items():
        entry = {"config": config}
        for secure in (False, True):
            key = "secure" if secure else "plain"
            _, r = solve(config, secure)
            _, ipm = solve(config, secure, INTERIOR)
            r["determined"] = determined(r, ipm)
            entry[key] = r
        cases[name] = entry
        p, s = entry["plain"], entry["secure"]
        print(f"  {name:22} plain={p.get('objective', float('nan')):12.2f} "
              f"secure={s.get('objective', float('nan')):12.2f}", flush=True)

    ref = cases["none"]

    def obj(name, key):
        return cases[name][key].get("objective", float("nan"))

    controls = {
        # Without this the whole fixture is about an unconstrained dispatch.
        "security_binds": abs(obj("none", "secure") - obj("none", "plain")) > 1.0,
        # And for each family: it has to change the secure answer, or the case says nothing
        # about that family reaching the security-constrained model.
        "family_changes_the_secure_answer": {
            name: abs(obj(name, "secure") - obj("none", "secure")) > 1.0
            for name in CASES if name != "none"
        },
        # And security has to still bind with the family on. A family that made the security
        # rows slack would leave this fixture proving only that rows can be added.
        "security_still_binds_with_family": {
            name: abs(obj(name, "secure") - obj(name, "plain")) > 1.0
            for name in CASES if name != "none"
        },
        # The combination differs from BOTH of its halves, which is the property a fixture
        # like this is easy to get wrong: several settings swept for these cases made one of
        # the two constraints redundant. Stated as "differs" and not "is tighter", because
        # two of the families are rewards -- a terminal water value and a bid ladder both
        # LOWER the objective -- so a one-sided comparison would be false for them by
        # construction rather than by evidence.
        "combination_differs_from_either": {
            name: abs(obj(name, "secure") - obj(name, "plain")) > 1.0
            and abs(obj(name, "secure") - obj("none", "secure")) > 1.0
            for name in CASES if name != "none"
        },
    }
    print()
    print(f"  security binds: {controls['security_binds']}")
    for key in ("family_changes_the_secure_answer", "security_still_binds_with_family",
                "combination_differs_from_either"):
        bad = [n for n, ok in controls[key].items() if not ok]
        print(f"  {key:36} {'all' if not bad else 'NOT: ' + ', '.join(bad)}")

    (out / "sclopf.json").write_text(json.dumps({
        "source": "PyPSA optimize_security_constrained with NordPSA's callbacks on top",
        "versions": {"pypsa": pypsa.__version__, "pandas": pd.__version__},
        "shape": {"snapshots": STEPS, "resolution_hours": RES, "s_nom": S_NOM,
                  "unit": UNIT, "p_nom": P_NOM, "max_hours": MAX_HOURS,
                  "capacity_mwh": CAPACITY, "lines": ["AB", "BC", "AC"]},
        "terminal_profile": PROFILE,
        "zone_data": {"x_t": SD["x_t"], "sync_weight": SD.get("sync_weight") or {},
                      "scr_exempt": SD.get("scr_exempt") or [],
                      "mapping": SD["mapping"],
                      "name_overrides": SD.get("name_overrides") or {}},
        "tech": {name: {c: (row[c] if isinstance(row[c], str)
                            else None if pd.isna(row[c]) else float(row[c]))
                        for c in ["mode", "H", "cos_phi", "xd2", "m_min", "avail", "ibr_w",
                                  "sk_pu", "e_coef", "s_coef"]}
                 for name, row in stability_tech(SD).iterrows()},
        "cases": cases,
        "controls": controls,
        "network": "sclopf-families",
    }, indent=2) + "\n")
    print("\nwrote networks/sclopf-families and sclopf.json")


if __name__ == "__main__":
    main()
