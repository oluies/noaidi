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
def ac_dc_dispatch():
    """`ac_dc_meshed` with capacity fixed, so the LP is pure dispatch.

    The stock example is a capacity *expansion* problem -- lines and generators
    carry `s_nom_extendable`/`p_nom_extendable` -- which is why its objective is
    negative and its `objective_constant` large. Reproducing that needs
    investment variables and capital costs, so this variant exists to give the
    dispatch formulation a target it can actually be checked against before that
    lands.
    """
    n = pypsa.examples.ac_dc_meshed()

    # Fixing capacity at the *stock* p_nom is infeasible -- there is not enough
    # generation to serve the load, because the example is posed so that capacity
    # is chosen rather than given. So the expansion problem is solved first and
    # its optimal capacities become the fixed ones, which guarantees the dispatch
    # problem is feasible and gives it a meaningful optimum.
    status, condition = n.optimize(solver_name="highs")
    if status != "ok" or condition != "optimal":
        raise RuntimeError(f"sizing solve failed: status={status} condition={condition}")

    n.generators["p_nom"] = n.generators["p_nom_opt"]
    n.lines["s_nom"] = n.lines["s_nom_opt"]
    n.links["p_nom"] = n.links["p_nom_opt"]
    n.generators["p_nom_extendable"] = False
    n.lines["s_nom_extendable"] = False
    n.links["p_nom_extendable"] = False
    return n


def ac_dc_co2():
    """`ac-dc-dispatch` with a CO2 cap that actually restricts the dispatch.

    `ac-dc-dispatch` cannot test the constraint at all, which is not obvious and
    took a solved LP to establish. Its emissions land on exactly 1000 -- the cap
    -- but the multiplier is 0 and *every* tighter cap is infeasible. The reason
    is structural: gas is both the dirty carrier and the expensive one (4.09-5.89
    against wind's 0.09-0.11), so minimising cost already minimises emissions and
    the cap is merely touched on the way to an optimum it never influenced. An
    implementation that omits the constraint entirely reproduces that objective
    exactly.

    Making the cap bind therefore requires the dirty plant to be the *cheap* one,
    so the cap has to displace something it would otherwise be economic to run.
    Pricing gas below wind does that: unconstrained the network emits 6702 t at a
    cost of 2819.52, and at a 2000 t cap it costs 3178.55 with a multiplier of
    -0.0879. The 12.7% spread between those objectives is what a solver that
    silently drops the constraint gets wrong -- and it is why this fixture exists
    rather than an assertion bolted onto the existing one.

    The cap sits mid-range deliberately: the multiplier is -0.0879 for every cap
    from 1200 to 3000, so the fixture is not balanced on a knife edge where a
    solver tolerance could tip it into a different basis.
    """
    n = ac_dc_dispatch()
    gas = n.generators.carrier == "gas"
    n.generators.loc[gas, "marginal_cost"] = 0.05
    n.global_constraints.loc["co2_limit", "constant"] = 2000.0
    return n


def ac_pf_pv():
    """A three-bus AC network covering all three Newton-Raphson bus types.

    Built by hand because no stock example has a PV bus. `storage-hvdc` is the
    only example whose AC power flow converges at all here -- `ac-dc-meshed`
    fails inside PyPSA with `'SubNetwork' object has no attribute 'Y'` -- and its
    generators are all Slack or PQ. Without a PV bus the half of the algorithm
    that holds voltage fixed and solves for reactive power would be unvalidated,
    and it is the half that makes AC power flow more than a nonlinear DC one.

    So: a slack bus, a PV bus whose generator pins |V| at 1.0 pu while its Q is
    solved (24.47 and 32.65 MVAr at the two snapshots), and a PQ bus whose
    voltage is solved (0.9921 pu). Lines carry non-zero `r`, so there are real
    losses -- the slack picks up 60.41 MW against 60.0 MW of PV generation and
    100 MW of load -- and non-zero `b`, so the pi-model shunt admittance is
    exercised rather than left at zero where a missing term would not show.
    """
    n = pypsa.Network()
    n.set_snapshots(range(2))
    n.add("Bus", "slack", v_nom=110.0)
    n.add("Bus", "pv", v_nom=110.0)
    n.add("Bus", "pq", v_nom=110.0)
    n.add("Line", "l1", bus0="slack", bus1="pv", r=0.5, x=2.0, b=1e-4, s_nom=500)
    n.add("Line", "l2", bus0="pv", bus1="pq", r=0.8, x=3.0, b=2e-4, s_nom=500)
    n.add("Line", "l3", bus0="slack", bus1="pq", r=1.0, x=4.0, b=0.0, s_nom=500)
    # Capacity and costs are irrelevant to the power flow -- which reads `p_set`
    # and `control` -- but without them `optimize` cannot build an objective, and
    # every fixture is put through every stage so that a failure is a real signal
    # rather than a known exception someone has to remember.
    # No `p_set` on the slack generator, and that is not an omission. Its active
    # power is the *unknown* a power flow solves for, so the value is ignored --
    # verified: setting it changes v_mag, v_ang and Q by nothing at ten decimal
    # places. It does change the optimisation, because PyPSA pins a generator
    # carrying a `p_set` to exactly that value rather than leaving it free between
    # p_min_pu and p_max_pu. With both generators pinned this network cannot serve
    # its load and `optimize` comes back infeasible.
    n.add("Generator", "g_slack", bus="slack", control="Slack", p_nom=500.0, marginal_cost=50.0)
    n.add(
        "Generator", "g_pv", bus="pv", control="PV", p_set=[60.0, 80.0],
        p_nom=200.0, marginal_cost=10.0,
    )
    n.add("Load", "d", bus="pq", p_set=[100.0, 120.0], q_set=[30.0, 40.0])
    n.add("Load", "d2", bus="pv", p_set=[20.0, 25.0], q_set=[5.0, 8.0])
    return n


# Stages a fixture is *known* not to support, with the reason.
#
# Expressed as data rather than tolerated silently, so the exit code keeps its
# meaning: an unexpected failure still fails the run, and a stage listed here that
# starts succeeding is reported too -- which is how a PyPSA upgrade that fixed the
# limitation would announce itself instead of going unnoticed.
KNOWN_UNSUPPORTED = {
    ("ac-dc-meshed", "pf"): "PyPSA 1.2.4 raises AttributeError inside its own sub-network handling",
    ("ac-dc-dispatch", "pf"): "same as ac-dc-meshed, from which it is derived",
    ("ac-dc-co2", "pf"): "same as ac-dc-meshed, from which it is derived",
}

def unit_commitment():
    """A single-bus network whose optimum is a commitment schedule.

    Purpose-built, because no stock example commits anything and `ac-dc-dispatch`
    cannot be adapted: two of its three gas units came out of the expansion
    optimum at `p_nom = 0`, and forcing a `p_min_pu` onto wind whose `p_max_pu`
    varies below it is infeasible on its face.

    Three units and an eight-snapshot load, shaped so the interesting constraints
    actually bite rather than merely being present:

      * `base` is cheap and inflexible. It runs throughout, so its `min_up_time`
        of 3 never binds -- said here rather than left for a reader to assume.
      * `mid` is the unit that gets committed and de-committed: off at snapshots
        3-5, on elsewhere. That matters because an all-ones status frame would be
        matched by an implementation that never switches anything off.
      * `min_down_time` on `mid` BINDS. At 0 the optimum cycles it off through the
        single-snapshot dip at t=1 for 16700; at 2 it cannot afford the
        two-snapshot outage that would force, and pays 17000 instead. The 300
        difference is attributable to that constraint alone.
      * `peak` is expensive and NOT committable. It exists so a non-committable
        generator sits in the same model as committable ones, since the two take
        different treatment -- ordinary bounds against a status variable. It is
        INERT at the optimum: it produces nothing at any snapshot, and deleting
        it leaves the objective at 17000. Recorded here so its presence is not
        mistaken for load-bearing; it exercises a code path, not the economics.

    PyPSA's status frame covers every generator, so it reports 0 for `peak` at
    every snapshot. That is a placeholder rather than a commitment decision --
    `peak` has no status to report -- which is why the Scala comparison is
    restricted to the committable units.
    """
    n = pypsa.Network()
    n.set_snapshots(range(8))
    n.add("Bus", "bus", v_nom=110.0)
    n.add(
        "Generator", "base", bus="bus", p_nom=150.0, marginal_cost=10.0,
        committable=True, p_min_pu=0.4, start_up_cost=500.0, shut_down_cost=200.0,
        min_up_time=3, min_down_time=2,
    )
    n.add(
        "Generator", "mid", bus="bus", p_nom=100.0, marginal_cost=30.0,
        committable=True, p_min_pu=0.3, start_up_cost=200.0, shut_down_cost=100.0,
        min_up_time=1, min_down_time=2,
    )
    n.add("Generator", "peak", bus="bus", p_nom=80.0, marginal_cost=80.0)
    n.add("Load", "d", bus="bus", p_set=[200.0, 100.0, 200.0, 120.0, 90.0, 100.0, 190.0, 210.0])
    return n


NETWORKS = {
    "ac-dc-meshed": pypsa.examples.ac_dc_meshed,
    "unit-commitment": unit_commitment,
    "ac-pf-pv": ac_pf_pv,
    "ac-dc-dispatch": ac_dc_dispatch,
    "ac-dc-co2": ac_dc_co2,
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


def series_columns(target: Path, component) -> dict:
    """Time-series columns as the CSV export actually wrote them.

    One file per varying attribute, named `<list_name>-<attr>.csv`, whose header
    is an empty cell for the snapshot index followed by the entities that vary.
    """
    found = {}
    for attr in component.dynamic:
        path = target / f"{component.list_name}-{attr}.csv"
        if not path.exists():
            continue
        with path.open() as handle:
            header = handle.readline().rstrip("\n").split(",")
        # Drop the leading index column, which holds the snapshot label.
        entities = header[1:]
        if entities:
            found[str(attr)] = entities
    return found


def capture_network(name: str, build) -> dict:
    print(f"  {name}: building")
    n = build()

    # Before the export, not after. `buses.csv` carries a `sub_network` column
    # naming the island each bus belongs to, and `sub_networks.csv` carries the
    # islands themselves. Exporting first wrote a `buses.csv` whose `sub_network`
    # values came from whatever the loaded network happened to hold -- on
    # ac-dc-meshed, Norway pointing at sub_network 3 while sub_networks.csv listed
    # only 0-2. That is a dangling reference committed inside a fixture, which is
    # the one place it can quietly become the expected answer.
    print(f"  {name}: topology")
    n.determine_network_topology()

    target = OUT / "networks" / name
    if target.exists():
        shutil.rmtree(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    n.export_to_csv_folder(str(target))

    summary = {
        "name": name,
        # Stringified, because a snapshot is a *label* and that is how the CSV
        # carries it. PyPSA does not require timestamps -- `ac-pf-pv` uses plain
        # integers -- and `jsonable` would render those as JSON numbers while
        # snapshots.csv writes "0" and "1", so the manifest would describe the
        # in-memory index rather than the file it is supposed to be checked
        # against.
        "snapshots": [str(s) for s in n.snapshots],
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
            #
            # Read from the exported header for the same reason `exported_columns`
            # is, and it is not a formality: the omit-defaults rule applies to
            # *series* columns too, which the static-column comment above does not
            # imply and which nothing in the documentation states. On
            # ac-dc-dispatch the in-memory `buses_t.v_ang` carries all nine buses
            # while the file carries eight -- Norway is dropped, being a
            # single-bus island whose angle is 0.0 at every snapshot and therefore
            # entirely default. Recording the in-memory frame would demand the
            # reader invent a column PyPSA did not write.
            "varying": series_columns(target, component),
        }

    # Sub-network decomposition. PyPSA forms these from *passive* branches only
    # -- lines and transformers -- grouped by carrier, which is why a meshed
    # AC/DC network has separate AC and DC islands joined by links. Links are
    # controllable and do not merge sub-networks. The component `category` field
    # in schema.json encodes that distinction, so a reader can derive it rather
    # than hardcoding a component list.
    # Computed on `n`, the same object the LPF results below come from, so the two
    # artefacts in one manifest entry describe the same graph. `lpf()` calls this
    # internally anyway; calling it here makes the dependency explicit and avoids
    # building the example a third time.
    summary["sub_networks"] = [
        {
            "carrier": str(row.carrier),
            "slack_bus": str(row.slack_bus),
            "buses": sorted(n.sub_networks.obj[sn].buses_i().tolist()),
        }
        for sn, row in n.sub_networks.iterrows()
    ]

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

    # Newton-Raphson AC power flow, on a fresh network so the linear solve above
    # cannot seed it. Recorded with its per-sub-network convergence flags: PyPSA
    # returns those rather than raising, so a non-converged snapshot would
    # otherwise be written out as though it were an answer.
    print(f"  {name}: non-linear power flow")
    try:
        a = build()
        result = a.pf()
        converged = result.converged if hasattr(result, "converged") else result
        results["pf"] = {
            "converged": frame_to_json(converged.astype(float)),
            "bus_v_mag_pu": frame_to_json(a.buses_t.v_mag_pu),
            "bus_v_ang": frame_to_json(a.buses_t.v_ang),
            "bus_p": frame_to_json(a.buses_t.p),
            "bus_q": frame_to_json(a.buses_t.q),
            "line_p0": frame_to_json(a.lines_t.p0),
            "line_q0": frame_to_json(a.lines_t.q0),
            "line_p1": frame_to_json(a.lines_t.p1),
            "line_q1": frame_to_json(a.lines_t.q1),
            "generator_p": frame_to_json(a.generators_t.p),
            "generator_q": frame_to_json(a.generators_t.q),
        }
    except Exception as exc:  # noqa: BLE001 - recorded, not swallowed
        # ac-dc-meshed lands here: PyPSA 1.2.4 raises AttributeError inside its
        # own sub-network handling. Recorded so the absence is visibly PyPSA's
        # rather than an oversight in this script.
        results["pf"] = {"error": f"{type(exc).__name__}: {exc}"}

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
            # Commitment decisions. Empty for every fixture with no committable
            # generator, so it is recorded rather than assumed present -- and it
            # is the frame that says whether an implementation reproduced the
            # *schedule* or merely landed on the same cost.
            "generator_status": frame_to_json(m.generators_t.status),
            "line_p0": frame_to_json(m.lines_t.p0),
            "link_p0": frame_to_json(m.links_t.p0),
            "bus_marginal_price": frame_to_json(m.buses_t.marginal_price),
            # Shadow price of each global constraint *as solved*. The exported
            # network CSVs carry a `mu` column too, but on ac-dc-dispatch that is
            # a stale dual left over from the sizing solve (-2178.29) while the
            # dispatch problem's own multiplier is 0 -- the CO2 cap there is tight
            # (emissions land on exactly 1000) yet weakly binding. Confusing the
            # two makes a redundant constraint look like the explanation for a
            # nodal-price discrepancy, so the solved value is recorded here where
            # it cannot be mistaken for the exported one.
            "global_constraint_mu": {
                str(k): jsonable(v) for k, v in m.global_constraints.get("mu", {}).items()
            },
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
    unexpected_successes: list[str] = []
    for name, build in NETWORKS.items():
        manifest["networks"][name] = capture_network(name, build)
        recorded = json.loads((OUT / "results" / f"{name}.json").read_text())
        for stage, body in recorded.items():
            if not isinstance(body, dict):
                continue
            known = KNOWN_UNSUPPORTED.get((name, stage))
            if "error" in body:
                if known is None:
                    failures.append(f"{name}/{stage}: {body['error']}")
                else:
                    print(f"  {name}: {stage} unsupported as expected ({known})")
            elif known is not None:
                unexpected_successes.append(f"{name}/{stage} succeeded, but is listed in KNOWN_UNSUPPORTED")

    reference = capture_reference_schema()
    (OUT / "schema.json").write_text(json.dumps(reference, indent=1, sort_keys=True))
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True))

    print(f"\nwrote goldens to {OUT}")
    print(f"pypsa {pypsa.__version__}, pandas {pd.__version__}")

    if unexpected_successes:
        # Not a failure, but it does mean KNOWN_UNSUPPORTED is now lying -- most
        # likely because a PyPSA upgrade fixed something.
        print("\nno longer unsupported:", file=sys.stderr)
        for u in unexpected_successes:
            print(f"  {u}", file=sys.stderr)

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
