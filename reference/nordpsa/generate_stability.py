"""Reference answers for the Stability port, from NordPSA's own callback.

The family is `stability_constraints`, and the first thing to say about it is that it is
**not** MILP. `p_online` is a continuous variable in `[0, p_max_pu·P]`, tied to dispatch by

    p − u          <= 0
    p − m_min · u  >= 0

which is the textbook LP relaxation of unit commitment -- "linjariserad inkoppling" in the
module's own first line. Nothing here needs an integer, so the whole family fits the LP
layer. `reference/nordpsa/README.md` said otherwise for three families' worth of commits.

What makes inertia cost anything, when switching a machine on is free? The second row:
`p >= m_min · u`. More capacity online forces more *production*, which displaces cheaper
generation or spends water that has a positive value. `m_min` therefore sets the entire
price of inertia, and a tech class with `m_min = 0` would hand it out for nothing -- which
is why the callback refuses one.

The networks are NordPSA's own, from `tests/unit/test_stability.py`: a two-zone dispatch
toy where wind covers the load so nothing synchronous runs unless a requirement makes it,
and a one-zone expansion toy where grid strength can be built instead of dispatched. Both
are chosen so the requirement *binds* -- on a network where the machines were running
anyway, every configuration returns the same answer and the comparison proves nothing.

The tech table and the zone data travel in the JSON as well. They live in NordPSA's
`config/zones.yaml`, not on the network, so an export cannot carry them and a port that
restated them in Scala would drift from the run it claims to reproduce.
"""

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pypsa

sys.path.insert(0, str(Path(__file__).parent / "NordPSA"))
from nordpsa.analysis.stability import stability_data, unit_table       # noqa: E402
from nordpsa.constraints.stability import (                             # noqa: E402
    scr_joint, stability_constraints, stability_results)

SD = stability_data()


# --------------------------------------------------------------- NordPSA's own networks

def toy():
    """Two zones. Wind covers the load, so without a requirement nothing synchronous runs.

    Verbatim from `tests/unit/test_stability.py`. The reservoir is the cheap way to make
    inertia (20 EUR/MWh at m_min 0.3) and the gas the dear one (100 at 0.4), so which one
    a requirement reaches for is itself an assertion.
    """
    sn = pd.date_range("2023-01-02", periods=6, freq="h")
    n = pypsa.Network()
    n.set_snapshots(sn)
    for b in ("A", "B"):
        n.add("Bus", b, carrier="AC")
    n.add("Link", "A-B", bus0="A", bus1="B", carrier="AC", p_nom=2000, p_min_pu=-1)
    n.add("StorageUnit", "A hydro", bus="A", carrier="hydro", p_nom=1000, max_hours=100,
          state_of_charge_initial=50000, marginal_cost=20)
    n.add("Generator", "A wind", bus="A", carrier="wind_onshore", p_nom=2000, p_max_pu=0.9)
    n.add("Generator", "B wind", bus="B", carrier="wind_onshore", p_nom=1000, p_max_pu=0.9)
    n.add("Generator", "B gas", bus="B", carrier="gas", p_nom=500, marginal_cost=100)
    n.add("Load", "A load", bus="A", p_set=800)
    n.add("Load", "B load", bus="B", p_set=300)
    return n


def toy_tight(wind_cf=0.1):
    """The same two zones with the wind becalmed, so the machines must run for the load.

    Not upstream's: added because a mutation survived without it. In `toy()` the wind covers
    everything, so a machine is only ever online because a requirement asked for it -- which
    means `u` is always pushed UP, and `p <= u` is slack in every case. Deleting that row
    changed no objective and no reported `u`, and twenty-three assertions stayed green.

    Here the wind delivers 300 MW against 1100 MW of load, so the reservoir has to produce
    whether or not anything asks it to. Give it a requirement small enough to be met by a
    fraction of that output and `p <= u` becomes the row that decides `u`: the machine is
    online because it is producing, not because the floor wanted it.
    """
    n = toy()
    n.generators.loc[["A wind", "B wind"], "p_max_pu"] = float(wind_cf)
    return n


def toy_mustrun():
    """`toy()` plus the two kinds of unit that contribute as a CONSTANT.

    Also not upstream's, and also added because a mutation survived. `K(z,t)` -- the
    must-run plant and the fixed condenser capacity -- is the term that moves a
    requirement's right-hand side, and in `toy()` there is nothing of either kind, so the
    term is zero in every case. Deleting it from the zonal floor changed no answer.

    `A ror` is must-run the way upstream recognises it: `p_min_pu == p_max_pu`, so its
    commitment is decided by the data and it gets no online variable. It maps to
    `hydro_ror`, whose `m_min` is 0 -- legal precisely because it is must-run, which is the
    distinction the free-inertia refusal turns on.

    `A syncon` is a fixed synchronous condenser: 100 MW of capacity contributing stiffness
    whatever the market does, and drawing 1 MW to do it.

    Built on `toy()` and not on `toy_tight()`, which was the first attempt and did not
    discriminate either: on the becalmed network the reservoir is already online for 700 MW
    of load, so a floor of 1 GWs is slack whether or not the constant is subtracted from it.
    The requirement has to be the ONLY thing bringing the machine online for the constant to
    change the answer.
    """
    n = toy()
    n.add("Generator", "A ror", bus="A", carrier="hydro", p_nom=200,
          p_min_pu=0.5, p_max_pu=0.5, marginal_cost=0.0)
    n.add("Generator", "A syncon", bus="A", carrier="syncon", p_nom=100,
          p_min_pu=-0.01, p_max_pu=-0.01)
    # A combined-heat-and-power link, which is the third shape and the one whose
    # coefficients carry an efficiency. Its p_nom is a FUEL rating on bus0 and its
    # synchronous machine sits at the other end, so `e` and `s` are scaled by 0.25 -- a
    # 400 MW link is a 100 MW machine. Without one in the fixture that scaling is untested,
    # and a mutation that dropped it stayed green.
    #
    # Cheap fuel on purpose: at 2 EUR/MWh the link costs 0.68 EUR per MWs of inertia against
    # the reservoir's 1.8, so a requirement reaches for it first and its coefficient is what
    # decides the answer.
    n.add("Bus", "A chp fuel", carrier="chp fuel")
    n.add("Generator", "A chp fuel supply", bus="A chp fuel", carrier="chp fuel",
          p_nom=2000, marginal_cost=2.0)
    n.add("Link", "A chp", bus0="A chp fuel", bus1="A", carrier="heat chp", p_nom=400,
          efficiency=0.25)
    return n


def toy_weighted(hours=3.0):
    """`toy()` at a coarser resolution, so the snapshot weighting is not 1.

    The fourth fixture added for a surviving mutation. Upstream prices the slack with
    `snapshot_weightings.objective`, as it prices every other cost, and every network above
    has that weighting at 1.0 -- so dropping it from the slack's objective coefficient
    changed nothing at all. Here it is 3, and the priced shortfall costs three times as much.

    The requirement rows themselves are per-snapshot and unweighted, which is why the slack
    QUANTITY is unchanged and only the objective moves: that is the assertion.
    """
    n = toy()
    n.snapshot_weightings.loc[:, :] = float(hours)
    return n


def toy_derated(availability=0.8):
    """`toy()` with the gas derated, so its online ceiling is not simply its p_nom.

    Every committable unit in the other fixtures sits at `p_max_pu = 1`, which makes
    `p_max_pu x p_nom` and `p_nom` the same number and the multiplication invisible. Here the
    gas can hold only 400 of its 500 MW online, and zone B's floor is set on both sides of
    what that allows: 2.0 GWs is met, 2.6 GWs is not.
    """
    n = toy()
    n.generators.loc["B gas", "p_max_pu"] = float(availability)
    return n


def toy_joint():
    """Two zones where the EXEMPT one has a machine online for reasons of its own.

    The third fixture added for a surviving mutation, and the most specific. In `toy()` the
    folded-in zone's gas never runs -- the host's reservoir is cheaper per MVA even at half
    credit, and curtailing wind is cheaper still -- so the share multiplies a stiffness of
    zero and dropping it changes nothing.

    Here B is nearly islanded: a 100 MW link against a 900 MW load and 540 MW of local wind,
    so its gas has to produce about 260 MW whatever the requirement says. Folded into A's row
    at half credit that is a real number, and halving it or not decides how much of A's
    reservoir comes online.
    """
    sn = pd.date_range("2023-01-02", periods=4, freq="h")
    n = pypsa.Network()
    n.set_snapshots(sn)
    for b in ("A", "B"):
        n.add("Bus", b, carrier="AC")
    n.add("Link", "A-B", bus0="A", bus1="B", carrier="AC", p_nom=100, p_min_pu=-1)
    n.add("StorageUnit", "A hydro", bus="A", carrier="hydro", p_nom=1000, max_hours=100,
          state_of_charge_initial=50000, marginal_cost=20)
    n.add("Generator", "A wind", bus="A", carrier="wind_onshore", p_nom=2000, p_max_pu=0.9)
    n.add("Generator", "B wind", bus="B", carrier="wind_onshore", p_nom=600, p_max_pu=0.9)
    n.add("Generator", "B gas", bus="B", carrier="gas", p_nom=500, marginal_cost=100)
    # And a must-run machine in B, so the folded-in zone contributes as a CONSTANT as well
    # as through its online capacity. Those are two different terms in the row and each
    # carries the share separately; without this one, dropping the share from the constant
    # changed nothing.
    n.add("Generator", "B chp", bus="B", carrier="thermal", p_nom=100,
          p_min_pu=1.0, p_max_pu=1.0, marginal_cost=30)
    n.add("Load", "A load", bus="A", p_set=800)
    n.add("Load", "B load", bus="B", p_set=900)
    return n


def toy_expansion(syncon_cost=None, gfm_cost=None, gas_ext=False):
    """One zone where grid strength can be built. Also NordPSA's own.

    Wind at CF 0.8 covers the load on its own, and spilling it needs the dear gas, so the
    cheap way to meet an SCR floor is to build a machine rather than to curtail.
    """
    sn = pd.date_range("2023-01-02", periods=4, freq="h")
    n = pypsa.Network()
    n.set_snapshots(sn)
    n.add("Bus", "Z", carrier="AC")
    n.add("Load", "Z load", bus="Z", p_set=500)
    n.add("Generator", "Z wind", bus="Z", carrier="wind_onshore", p_nom=1000, p_max_pu=0.8)
    n.add("Generator", "Z gas", bus="Z", carrier="gas", p_nom=0 if gas_ext else 1000,
          p_nom_extendable=gas_ext, capital_cost=1.0, marginal_cost=100)
    if syncon_cost is not None:
        n.add("Generator", "Z syncon", bus="Z", carrier="syncon", p_nom_extendable=True,
              p_min_pu=-0.01, p_max_pu=-0.01, capital_cost=syncon_cost)
    if gfm_cost is not None:
        n.add("StorageUnit", "Z battery gfm", bus="Z", carrier="battery_gfm",
              p_nom_extendable=True, max_hours=4, capital_cost=gfm_cost,
              cyclic_state_of_charge=True)
    return n


# ------------------------------------------------------------------------ what to record

_P = {"Generator": ("generators_t", "p"), "StorageUnit": ("storage_units_t", "p_dispatch"),
      "Link": ("links_t", "p0")}


def measure(n, status, res, condition=None):
    """Everything a port has to reproduce, per unit and per snapshot."""
    out = {"status": status}
    if condition is not None:
        out["condition"] = str(condition)
    if status != "ok":
        return out
    out["objective"] = float(n.objective)
    dispatch = {}
    for c, (attr, col) in _P.items():
        df = getattr(n, attr, None)
        if df is None:
            continue
        frame = getattr(n, attr)[col]
        for name in frame.columns:
            dispatch[name] = [float(x) for x in frame[name]]
    out["dispatch"] = dispatch
    if "stability_online" in res:
        out["online"] = {k: [float(x) for x in v]
                         for k, v in res["stability_online"].items()}
    if "stability_slack" in res:
        out["slack"] = {k: [float(x) for x in v]
                        for k, v in res["stability_slack"].items()}
    if "stability_dual" in res:
        out["dual"] = {k: [float(x) for x in v] for k, v in res["stability_dual"].items()}
    caps = {}
    for c in ("Generator", "StorageUnit", "Link"):
        df = n.components[c].static
        if df.empty:
            continue
        for name in df.index[df.p_nom_extendable.astype(bool)]:
            caps[name] = float(df.at[name, "p_nom_opt"])
    if caps:
        out["capacity"] = caps
    return out


# An interior-point solve of the same problem, no crossover, so the answer is a point in
# the relative interior of the optimal face rather than a vertex of it. Two methods that
# disagree about a quantity while agreeing about the cost are reporting a tie-break, and
# nothing a port asserts may rest on one.
#
# `presolve: off` is load-bearing, not caution. With presolve on, HiGHS solved these toys
# outright and returned the simplex answer, so the probe reported every case determined --
# including the control, whose two zero-cost wind farms can split one load any way at all.
# With presolve off the same call returns 998/265 where simplex returns 200/900, on an
# objective of zero either way.
INTERIOR = {"solver": "ipm", "run_crossover": "off", "presolve": "off"}


def solve(n, sdata, sys_gws=None, floors=None, penalty=None, scr=None, scr_penalty=None,
          options=None):
    cb = (stability_constraints(sdata, sys_gws, floors or {}, penalty, scr, scr_penalty)
          if (sys_gws or floors or scr) else None)
    kw = {"solver_name": "highs", "extra_functionality": cb}
    if options:
        kw["solver_options"] = options
    status, condition = n.optimize(**kw)
    res = stability_results(n) if status == "ok" else {}
    return measure(n, status, res, condition)


def determined(simplex, interior, tol=1e-4):
    """Which recorded series both methods agree on, so the port may assert them.

    `objective` is always determined when both solves reach optimality -- that is what
    optimality means. Everything else has to be checked, and on these toys some of it
    genuinely is not: two zero-cost wind farms feeding one load through an unconstrained
    link can split the load any way at all, so `dispatch` is a tie-break in the cases
    where no requirement prices that split.
    """
    if simplex.get("status") != "ok" or interior.get("status") != "ok":
        return {"probed": False}
    out = {"probed": True,
           "same_objective": abs(simplex["objective"] - interior["objective"])
           <= 1e-6 * max(1.0, abs(simplex["objective"]))}
    for block in ("dispatch", "online", "slack", "capacity"):
        a, b = simplex.get(block) or {}, interior.get(block) or {}
        if not a:
            continue
        agree = {}
        for key, series in a.items():
            other = b.get(key)
            if other is None:
                agree[key] = False
            elif isinstance(series, list):
                scale = max(1.0, max(abs(x) for x in series) if series else 1.0)
                agree[key] = all(abs(x - y) <= tol * scale for x, y in zip(series, other))
            else:
                agree[key] = abs(series - other) <= tol * max(1.0, abs(series))
        out[block] = agree
    return out


def sdata_for(weights=None, exempt=None, joint=None, tech=None):
    sd = stability_data(sync_weight=weights or {}, tech=tech or {})
    if exempt is not None:
        sd["scr_exempt"] = exempt
    if joint is not None:
        sd["scr_joint"] = joint
    return sd


# One entry per case: which network, which sdata overrides, which requirement.
DISPATCH_CASES = {
    # The control. No requirement at all: nothing synchronous runs, so the model has no
    # rotational energy whatsoever and every assertion below has somewhere to fall from.
    "none":            dict(),
    # The system requirement, and which machine it reaches for. The reservoir is cheaper
    # per MWs than the gas, so a port that priced inertia through the wrong unit fails.
    "ek-system":       dict(sys_gws=2.0),
    # sync_weight 0 takes B out of the SYSTEM sum without taking it out of its own zone
    # value, so the whole requirement lands on A.
    "ek-system-w0":    dict(sys_gws=2.0, weights={"B": 0.0}),
    # And the other direction, which is the one that discriminates: weighting A to zero
    # takes the cheap reservoir out of the SYSTEM sum, so the dear gas in B has to carry
    # the whole requirement. Weighting B to zero changes nothing on this network, because
    # B's gas was not running anyway -- a case that looks like a test of sync_weight and
    # is not.
    "ek-system-wa0":   dict(sys_gws=1.0, weights={"A": 0.0}),
    # A zonal floor is local: A's reservoir cannot help B, so B's gas has to run.
    "ek-zone-b":       dict(floors={"B": 1.0}),
    # Both at once, which is the case the commitment bounds are checked on.
    "ek-both":         dict(sys_gws=2.5, floors={"B": 0.5}),
    # Above what A can physically deliver. Hard: infeasible. That is the answer.
    "ek-hard":         dict(sys_gws=5.0, weights={"B": 0.0}),
    # The same requirement with a penalty: feasible, and the shortfall is priced rather
    # than the run failing.
    "ek-soft":         dict(sys_gws=5.0, weights={"B": 0.0}, penalty=100.0),
    # Short-circuit ratio against the converters' actual infeed, both zones.
    "scr":             dict(scr=1.5, exempt=[]),
    # An exempt zone gets no row at all.
    "scr-exempt-a":    dict(scr=1.5, exempt=["A"]),
    # A share of an exempt zone folded into its host's row, with both its stiffness and
    # its converter infeed -- the Oresund case.
    "scr-joint":       dict(scr=1.5, exempt=["B"], joint={"A": {"B": 0.5}}),
    # Both families together, so a port that emitted one set of rows into the other's
    # right-hand side shows up.
    "ek-and-scr":      dict(sys_gws=2.0, scr=1.5, exempt=[]),
    # On the becalmed network, with a requirement far below what the load already forces.
    # This is the only case where `p <= u` is the row that decides the answer: the machines
    # run for the energy balance, and their online capacity follows their output rather
    # than the floor.
    "ek-slack-online": dict(sys_gws=0.5, network="tight"),
    # The constant term, on both families. A zonal floor of 1 GWs against a must-run unit
    # already delivering 0.28 of it: drop the constant from the right-hand side and the
    # reservoir is asked for 1000 MWs instead of 722, which is a different dispatch and a
    # different cost.
    "ek-mustrun":      dict(floors={"A": 1.0}, network="mustrun"),
    # And the same for grid strength, where the fixed condenser supplies 370 MVA of the
    # floor before any machine is online.
    "scr-mustrun":     dict(scr=1.5, exempt=[], network="mustrun"),
    # A FRACTIONAL weight, which is the only case that tests the multiplication. A weight of
    # zero is handled by dropping the zone from the sum altogether -- that is what NordPSA's
    # `if w[z] > 0` does and what Denmark's 0.35 is not. On the must-run network, so the
    # weight has both a variable term and a constant to scale.
    "ek-system-half":  dict(sys_gws=1.0, weights={"A": 0.5}, network="mustrun"),
    # The joint share over a member zone whose machine is online for its own reasons, which
    # is the only case where the share multiplies something non-zero.
    "scr-joint-online": dict(scr=1.5, exempt=["B"], joint={"A": {"B": 0.5}},
                             network="joint"),
    # The same unmeetable requirement on a network whose snapshot weighting is 3, so the
    # priced shortfall costs three times as much for the same shortfall.
    "ek-soft-weighted": dict(sys_gws=5.0, weights={"B": 0.0}, penalty=100.0,
                             network="weighted"),
    # 30% of the wind grid-forming, which is upstream's own `gfm_share` case. Every class
    # ships `ibr_w` at 1.0, so without an override the weight is a no-op and a port that
    # ignored it entirely agrees on every other case.
    "scr-gfm-share":   dict(scr=1.5, exempt=[],
                            tech={"ibr_wind": {"ibr_w": 0.7}}),
    # A committable unit whose availability is below 1, so the online ceiling is
    # `p_max_pu x p_nom` and not `p_nom`. Asked for more than the derated machine can hold:
    # feasible at full availability, infeasible at 0.8, so the ceiling decides the verdict.
    "ek-derated-hard": dict(floors={"B": 2.6}, network="derated"),
    # And the feasible side of the same pair, so the case above is not the only evidence.
    "ek-derated":      dict(floors={"B": 2.0}, network="derated"),
}

EXPANSION_CASES = {
    # Grid strength is built rather than dispatched: a synchronous condenser is cheaper
    # than curtailing the wind, and it draws a little auxiliary power for its trouble.
    "exp-syncon": (dict(syncon_cost=1.0), dict(scr=1.5, exempt=[])),
    # A grid-forming battery contributes sk_pu x avail per MW of capacity whatever it is
    # doing, and is not a load in the SCR row.
    "exp-gfm":    (dict(gfm_cost=1.0), dict(scr=1.5, exempt=[])),
    # No condenser on offer, so the gas gets built for its stiffness -- and its online
    # capacity is capped by what was built, which is a row of its own.
    "exp-gas":    (dict(gas_ext=True), dict(scr=1.5, exempt=[])),
}

REFUSED_JOINT = {
    "unknown-zone":  {"A": {"C": 0.3}},
    "host-exempt":   {"B": {"B": 0.3}},
    "member-not-exempt": {"B": {"A": 0.3}},
    "share-zero":    {"A": {"B": 0.0}},
    "share-above-1": {"A": {"B": 1.5}},
}


def tech_block():
    """The tech table as coefficients, so the port reads the numbers and not the yaml."""
    from nordpsa.analysis.stability import stability_tech
    t = stability_tech(SD)
    cols = ["mode", "H", "cos_phi", "xd2", "m_min", "avail", "ibr_w", "i_ibr", "sk_pu",
            "e_coef", "s_coef"]
    out = {}
    for name, row in t[cols].iterrows():
        out[name] = {c: (row[c] if isinstance(row[c], str)
                         else (None if pd.isna(row[c]) else float(row[c]))) for c in cols}
    return out


def unit_block(n, sdata):
    """The classification and coefficients the port has to reproduce, per unit."""
    u = unit_table(n, sdata)
    cols = ["component", "carrier", "tech", "zone", "extendable", "p_nom", "eff", "fixed",
            "cap", "mode", "m_min", "avail", "ibr_w", "e_coef", "s_coef"]
    out = {}
    for name, row in u[cols].iterrows():
        out[name] = {c: (bool(row[c]) if c in ("extendable", "fixed")
                         else row[c] if isinstance(row[c], str)
                         else float(row[c])) for c in cols}
    return out


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    nets = out / "networks"
    nets.mkdir(parents=True, exist_ok=True)

    toy().export_to_csv_folder(str(nets / "stability-toy"))

    cases = {}
    toy_tight().export_to_csv_folder(str(nets / "stability-tight"))
    toy_mustrun().export_to_csv_folder(str(nets / "stability-mustrun"))
    toy_joint().export_to_csv_folder(str(nets / "stability-joint"))
    toy_weighted().export_to_csv_folder(str(nets / "stability-weighted"))
    toy_derated().export_to_csv_folder(str(nets / "stability-derated"))

    for name, kw in DISPATCH_CASES.items():
        net = {"tight": toy_tight, "mustrun": toy_mustrun, "joint": toy_joint,
               "weighted": toy_weighted,
               "derated": toy_derated}.get(kw.get("network"), toy)
        sd = sdata_for(kw.get("weights"), kw.get("exempt"), kw.get("joint"),
                       kw.get("tech"))
        r = solve(net(), sd,
                  sys_gws=kw.get("sys_gws"), floors=kw.get("floors"),
                  penalty=kw.get("penalty"), scr=kw.get("scr"),
                  scr_penalty=kw.get("penalty"))
        r["network"] = f"stability-{kw['network']}" if kw.get("network") else "stability-toy"
        r["config"] = {k: v for k, v in kw.items() if k != "network"}
        ipm = solve(net(), sd,
                    sys_gws=kw.get("sys_gws"), floors=kw.get("floors"),
                    penalty=kw.get("penalty"), scr=kw.get("scr"),
                    scr_penalty=kw.get("penalty"), options=INTERIOR)
        r["determined"] = determined(r, ipm)
        cases[name] = r
        print(f"  {name:14} {r['status']:10} obj="
              f"{r.get('objective', float('nan')):12.2f}", flush=True)

    for name, (build_kw, kw) in EXPANSION_CASES.items():
        key = f"stability-{name}"
        toy_expansion(**build_kw).export_to_csv_folder(str(nets / key))
        sd = sdata_for(kw.get("weights"), kw.get("exempt"), kw.get("joint"))
        r = solve(toy_expansion(**build_kw), sd, scr=kw.get("scr"),
                  sys_gws=kw.get("sys_gws"), floors=kw.get("floors"))
        r["network"] = key
        r["config"] = {k: v for k, v in kw.items()}
        r["build"] = {k: (v if not isinstance(v, bool) else bool(v))
                      for k, v in build_kw.items()}
        ipm = solve(toy_expansion(**build_kw), sd, scr=kw.get("scr"),
                    sys_gws=kw.get("sys_gws"), floors=kw.get("floors"), options=INTERIOR)
        r["determined"] = determined(r, ipm)
        cases[name] = r
        print(f"  {name:14} {r['status']:10} obj="
              f"{r.get('objective', float('nan')):12.2f} caps={r.get('capacity')}",
              flush=True)

    # The refusals, so both sides reject the same configurations.
    refused = {}
    for name, bad in REFUSED_JOINT.items():
        try:
            scr_joint(dict(SD, scr_exempt=["B"], scr_joint=bad), ["A", "B"])
            refused[name] = {"refused": False}
            print(f"  joint {name:20} NOT refused by NordPSA")
        except ValueError as e:
            refused[name] = {"refused": True, "message": str(e)}
            print(f"  joint {name:20} refused")
    # m_min = 0 on a non-must-run class hands out free inertia, and NordPSA refuses it.
    try:
        sd = stability_data(tech={"gas": {"m_min": 0.0}})
        stability_constraints(sd, 2.0, {}, None)(toy_solved_model(), None)
        refused["free-inertia"] = {"refused": False}
    except Exception as e:                                        # noqa: BLE001
        refused["free-inertia"] = {"refused": True, "message": str(e)[:200]}
    print(f"  free-inertia refused: {refused['free-inertia']['refused']}")

    # Controls: the assertions the fixture itself has to satisfy, or the comparison is
    # about a network where the requirement never bound.
    ref = cases["none"]
    idle = max(max(map(abs, ref["dispatch"]["A hydro"])),
               max(map(abs, ref["dispatch"]["B gas"])))
    controls = {
        "reference_is_idle": idle < 1e-6,
        "reference_max_synchronous_mw": idle,
        "system_costs_more": cases["ek-system"]["objective"] > ref["objective"] + 1.0,
        "zone_floor_runs_local_gas":
            min(cases["ek-zone-b"]["dispatch"]["B gas"]) > 1e-6,
        "hard_is_infeasible": cases["ek-hard"]["status"] != "ok",
        "soft_is_feasible": cases["ek-soft"]["status"] == "ok",
        "scr_costs_more": cases["scr"]["objective"] > ref["objective"] + 1.0,
        "sync_weight_changes_the_answer":
            abs(cases["ek-system-wa0"]["objective"]
                - cases["ek-system"]["objective"]) > 1.0,
        "weighting_b_to_zero_changes_nothing":
            abs(cases["ek-system-w0"]["objective"]
                - cases["ek-system"]["objective"]) < 1e-6,
        "tight_runs_for_the_load":
            min(cases["ek-slack-online"]["dispatch"]["A hydro"]) > 100.0,
        "tight_requirement_is_below_what_the_load_forces":
            min(cases["ek-slack-online"]["online"]["A hydro"]) >
            1e3 * 0.5 / (SD["tech"]["hydro_res"]["H"] / SD["tech"]["hydro_res"]["cos_phi"]) + 1.0,
        "mustrun_constant_is_a_real_share":
            0.05 < (SD["tech"]["hydro_ror"]["H"] / SD["tech"]["hydro_ror"]["cos_phi"]
                    * 0.5 * 200) / 1e3 < 0.95,
        "syncon_constant_is_a_real_share":
            (100.0 / ((SD["tech"]["syncon"]["xd2"] + SD["x_t"])
                      * SD["tech"]["syncon"]["cos_phi"])) > 100.0,
        "gfm_share_changes_the_answer":
            abs(cases["scr-gfm-share"]["objective"] - cases["scr"]["objective"]) > 1.0,
        "derated_ceiling_decides_feasibility":
            cases["ek-derated-hard"]["status"] != "ok" and cases["ek-derated"]["status"] == "ok",
        "weighting_prices_the_slack_higher":
            abs(cases["ek-soft-weighted"]["objective"]
                - cases["ek-soft"]["objective"]) > 1.0,
        "weighting_leaves_the_shortfall_alone":
            max(abs(a - b) for a, b in zip(cases["ek-soft-weighted"]["slack"]["SYSTEM"],
                                           cases["ek-soft"]["slack"]["SYSTEM"])) < 1e-6,
        "joint_member_has_stiffness_online":
            min(cases["scr-joint-online"]["online"]["B gas"]) > 10.0,
        "joint_member_has_a_constant":
            (1.0 / ((SD["tech"]["thermal"]["xd2"] + SD["x_t"])
                    * SD["tech"]["thermal"]["cos_phi"])) * 100.0 > 100.0,
        "chp_link_carries_the_requirement":
            min(cases["ek-mustrun"]["online"]["A chp"]) > 1.0,
        "fractional_weight_changes_the_answer":
            abs(cases["ek-system-half"]["online"]["A hydro"][0]
                - cases["ek-mustrun"]["online"]["A hydro"][0]) > 1.0,
        "reference_has_wind": min(
            a + b for a, b in zip(ref["dispatch"]["A wind"], ref["dispatch"]["B wind"])) > 0,
    }
    for k, v in controls.items():
        print(f"  control {k:32} {v}")

    (out / "stability.json").write_text(json.dumps({
        "source": "NordPSA nordpsa/constraints/stability.py, networks from its own tests",
        "versions": {"pypsa": pypsa.__version__, "pandas": pd.__version__},
        "is_lp": True,
        "zone_data": {"x_t": SD["x_t"], "sync_weight": SD.get("sync_weight") or {},
                      "scr_exempt": SD.get("scr_exempt") or [],
                      "mapping": SD["mapping"],
                      "name_overrides": SD.get("name_overrides") or {}},
        "tech": tech_block(),
        "units": {"stability-toy": unit_block(toy(), SD),
                  "stability-tight": unit_block(toy_tight(), SD),
                  "stability-mustrun": unit_block(toy_mustrun(), SD),
                  "stability-joint": unit_block(toy_joint(), SD),
                  "stability-weighted": unit_block(toy_weighted(), SD),
                  "stability-derated": unit_block(toy_derated(), SD)},
        "cases": cases,
        "refused": refused,
        "controls": controls,
    }, indent=2) + "\n")
    print("\nwrote networks/ and stability.json")


def toy_solved_model():
    """A network whose model exists, so the callback can be invoked for its refusal."""
    n = toy()
    n.optimize.create_model()
    return n


if __name__ == "__main__":
    main()
