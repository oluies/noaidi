"""A side-by-side reference from NordPSA's real Nordic network, not a toy.

Drives the same build `nordpsa today` drives -- config, the fetched inputs, snapshots,
`build_network`, `apply_post_build` -- then solves the result twice: once plain, once with
the bid-ladder callback NordPSA would apply. Exports the network so the port can read the
identical thing.

Why this is worth more than the synthetic fixtures beside it: those were built to make one
constraint bind, so they answer "does this formulation reproduce that callback". This one
answers "does this formulation reproduce that callback on the network the model is actually
for" -- real zonal topology, real load, real inflow, real reservoir sizes, and every other
component the port has to get right at the same time.

Coarsened on purpose. Resolution is a parameter because the point is a comparison the port
can run in CI, not a production study: 24-hour steps over one year is ~365 snapshots, which
is two orders of magnitude past the 1-bus fixtures and still small enough to commit.
"""

import json
import sys
from pathlib import Path

import pandas as pd

# The clone with the fetched data, not the throwaway one beside this script: NordPSA
# resolves data/ relative to its own package root, so importing from a clone with an empty
# data/ fails on the first parquet read.
ROOT = Path.home() / "projects" / "NordPSA"
if not (ROOT / "data" / "processed" / "load.parquet").exists():
    raise SystemExit(f"no built inputs under {ROOT}/data/processed -- run the fetch and build first")
sys.path.insert(0, str(ROOT))

pd.options.future.infer_string = False  # PyPSA/xarray do not support Arrow strings

from nordpsa import inputs as inp                      # noqa: E402
from nordpsa import settings, world                     # noqa: E402
from nordpsa.constraints import hydro_bid_ladder        # noqa: E402
from nordpsa.network import build_network               # noqa: E402

RESOLUTION = int(sys.argv[2]) if len(sys.argv) > 2 else 24
YEAR       = 2023
LADDER     = (3, 36.0)   # (tiers, width) -- NordPSA's own example


def build():
    """The network `nordpsa today` builds, at a coarse resolution."""
    # mode "dispatch", world "today", capacities "config" -- exactly what
    # `nordpsa today` resolves. The world only permits that mode, so naming the command
    # here instead is refused.
    s = settings.resolve(
        "dispatch", "today", capacities="config",
        sets=[f"period.resolution_hours={RESOLUTION}", f"period.year={YEAR}"],
    )

    cfg    = inp.load_config()
    extras = world.prepare_config(cfg, s)

    data = inp.load_inputs()
    vre  = s["vre"]
    data["vre_profiles"] = inp.boost_capfac(
        data["vre_profiles"], float(vre["onwind_capfac_increase"]), "wind_onshore")
    data["vre_profiles"] = inp.boost_capfac(
        data["vre_profiles"], float(vre["offwind_capfac_increase"]), "wind_offshore")
    world.scale_continent_prices(cfg, s, data["market_prices"])

    snapshots = inp.make_snapshots(cfg, RESOLUTION, YEAR)
    data      = inp.resample_inputs(data, snapshots, RESOLUTION)

    rh = s["hydro"]["ror_hifreq"] or {"sigma": 0.0, "tau_days": 3.5, "seed": 0}
    n = build_network(
        cfg, snapshots, **data,
        voll=s["voll"],
        batteries=extras["batteries"],
        extra_nuclear=None, synthetic_nuclear=None,
        hydrogen_overrides=extras["hydrogen_overrides"] or None,
        ev_overrides=extras["ev_overrides"] or None,
        hydro_mc_override=None,
        ror_hifreq=float(rh["sigma"]),
        ror_hifreq_seed=int(rh["seed"]),
        ror_hifreq_tau_days=float(rh["tau_days"]),
        battery_invest=extras["battery_invest"],
        syncon=extras["syncon"],
    )
    n_years = len(snapshots) * RESOLUTION / 8760.0
    world.apply_post_build(n, cfg, s, n_years)
    return n, s


def shape(n):
    return {
        "snapshots": int(len(n.snapshots)),
        "resolution_hours": RESOLUTION,
        "year": YEAR,
        "buses": int(len(n.buses)),
        "generators": int(len(n.generators)),
        "storage_units": int(len(n.storage_units)),
        "links": int(len(n.links)),
        "lines": int(len(n.lines)),
        "loads": int(len(n.loads)),
        "stores": int(len(n.stores)),
        "hydro_reservoirs": sorted(
            su for su in n.storage_units.index
            if n.storage_units.at[su, "carrier"] == "hydro"
            and float(n.storage_units.at[su, "p_nom"]) > 0.0
        ),
    }


def measure(n, status):
    out = {"status": status, "objective": float(n.objective)}
    hyd = [su for su in n.storage_units.index
           if n.storage_units.at[su, "carrier"] == "hydro"
           and float(n.storage_units.at[su, "p_nom"]) > 0.0]
    if hyd:
        d = n.storage_units_t.p_dispatch[hyd]
        pn = n.storage_units.loc[hyd, "p_nom"]
        frac = d / pn
        out["hydro"] = {
            "dispatch_twh": float((d.sum().sum() * RESOLUTION) / 1e6),
            "at_ceiling_frac": float((frac > 0.999).to_numpy().mean()),
            "at_floor_frac": float((frac < 0.001).to_numpy().mean()),
            "per_zone_mean_mw": {su: float(d[su].mean()) for su in hyd},
        }
    return out


def solve(n, callback=None, options=None):
    kw = {"solver_name": "highs"}
    if options:
        kw["solver_options"] = options
    if callback is None:
        status, _ = n.optimize(**kw)
    else:
        n.optimize.create_model()
        callback(n, n.snapshots)
        status, _ = n.optimize.solve_model(**kw)
    if status != "ok":
        return {"status": status}
    return measure(n, status)


# An interior-point solve of the SAME problem, no crossover, so the answer is a point in
# the relative interior of the optimal face rather than a vertex of it.
INTERIOR = {"solver": "ipm", "run_crossover": "off"}


def degeneracy(flat):
    """Whether this network's hydro trajectory is the unique optimum, and the evidence.

    It is not, and that has to be measured here rather than assumed on either side,
    because it decides what the port is allowed to assert.

    The bang-bang fractions above are the headline effect the ladder exists to reduce,
    and on THIS network they are a property of the solver and not of the model: the
    continental price generators are perfectly elastic at one price for thousands of MW,
    so moving hydro between two hours that both price against them changes the objective
    by nothing. The optimal face is then enormous, simplex returns a vertex of it --
    bang-bang -- and an interior-point method returns a smooth interior point with the
    same objective to eleven digits.

    So this records both, and `at_ceiling_frac` is fair game for a port's assertion only
    if `unique` comes back true. The synthetic `ladder-week` fixture beside this one is
    built so that it does; see its generator for how.
    """
    ipm = solve(build()[0], options=INTERIOR)
    if ipm.get("status") != "ok":
        return {"probed": False, "reason": ipm.get("status")}
    same_objective = abs(ipm["objective"] - flat["objective"]) <= 1e-6 * abs(flat["objective"])
    pinned = lambda r: r["hydro"]["at_ceiling_frac"] + r["hydro"]["at_floor_frac"]
    return {
        "probed": True,
        "unique": same_objective and abs(pinned(ipm) - pinned(flat)) < 1e-3,
        "same_objective": same_objective,
        "simplex": {"objective": flat["objective"], "pinned_frac": pinned(flat)},
        "interior": {"objective": ipm["objective"], "pinned_frac": pinned(ipm)},
    }


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    nets = out / "networks"
    nets.mkdir(parents=True, exist_ok=True)

    n, _ = build()
    sh = shape(n)
    print(f"  network: {sh['snapshots']} snapshots, {sh['buses']} buses, "
          f"{sh['generators']} generators, {sh['storage_units']} storage units, "
          f"{sh['links']} links", flush=True)
    print(f"  reservoirs: {', '.join(sh['hydro_reservoirs'])}", flush=True)

    # Exported before solving, so what the port reads is the built network and not one
    # carrying a solution.
    n.export_to_csv_folder(str(nets / "nordic-today"))

    flat = solve(build()[0])
    print(f"  flat   obj={flat.get('objective', float('nan')):16.2f} "
          f"ceiling={flat.get('hydro', {}).get('at_ceiling_frac', float('nan')):5.1%} "
          f"floor={flat.get('hydro', {}).get('at_floor_frac', float('nan')):5.1%}", flush=True)

    k, width = LADDER
    lad = solve(build()[0], hydro_bid_ladder(k, width))
    print(f"  ladder obj={lad.get('objective', float('nan')):16.2f} "
          f"ceiling={lad.get('hydro', {}).get('at_ceiling_frac', float('nan')):5.1%} "
          f"floor={lad.get('hydro', {}).get('at_floor_frac', float('nan')):5.1%}", flush=True)

    deg = degeneracy(flat)
    if deg["probed"]:
        print(f"  degeneracy: unique={deg['unique']}  "
              f"simplex pinned={deg['simplex']['pinned_frac']:5.1%} "
              f"interior pinned={deg['interior']['pinned_frac']:5.1%} "
              f"same objective={deg['same_objective']}", flush=True)
    else:
        print(f"  degeneracy: not probed ({deg['reason']})", flush=True)

    (out / "nordic.json").write_text(json.dumps({
        "source": "NordPSA `today` world, driven through build_network",
        "versions": {"pypsa": __import__("pypsa").__version__, "pandas": pd.__version__},
        "shape": sh,
        "ladder": {"tiers": k, "width": width,
                   "offsets": [width * ((i + 0.5) / k - 0.5) for i in range(k)]},
        "cases": {"flat": flat, "ladder": lad},
        "trajectory_degeneracy": deg,
        "network": "nordic-today",
    }, indent=2) + "\n")
    print("\nwrote networks/nordic-today and nordic.json")


if __name__ == "__main__":
    main()
