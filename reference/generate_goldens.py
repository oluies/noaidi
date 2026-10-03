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


# `ac-dc-meshed` ships every line at `length = 0`, so a volume limit over it is the row
# `0 <= constant` -- satisfied by anything, and indistinguishable from no row at all. These
# lengths exist so the constraint has a left-hand side; the values are arbitrary but fixed,
# because a limit measured against a random length is a fixture nobody can reason about.
TX_LENGTHS = [120.0, 80.0, 200.0, 150.0, 60.0, 90.0, 110.0]

# What `ac-dc-meshed` builds when nothing limits it, measured and recorded here so the
# constants below can be read as what they are: fractions and multiples of a free optimum.
#   sum(length * s_nom_opt)       = 633_340.16
#   sum(capital_cost * s_nom_opt) =     609.63
TX_FREE_VOLUME = 633_340.16
TX_FREE_COST = 609.63


def _ac_dc_tx(kind, sense, constant):
    """`ac-dc-meshed` with a transmission expansion limit that binds."""
    n = pypsa.examples.ac_dc_meshed()
    n.lines["length"] = TX_LENGTHS
    n.add("GlobalConstraint", "tx_limit", type=kind, carrier_attribute="AC",
          sense=sense, constant=constant)
    return n


def ac_dc_txvolume():
    """A transmission *volume* limit at 60% of what the network would build freely.

    Volume is `sum(length * s_nom_opt)` over the extendable branches of the named carrier,
    which is why the lengths above had to be invented -- PyPSA's example ships zeros.

    The cap costs a great deal: the objective moves from -3,474,256.04 to -1,522,164.75, a
    56% spread. That is deliberate and is the same argument `ac-dc-co2` makes. A limit that
    merely touched the optimum would be reproduced exactly by an implementation that never
    built the row.
    """
    return _ac_dc_tx("transmission_volume_expansion_limit", "<=", 380_000.0)


def ac_dc_txvolume_exact():
    """The same limit at `==`, set ABOVE the free optimum so the sense is what decides.

    This is the fixture that separates `==` from `<=`, and the constant has to be above the
    free optimum for it to do so. Below it the two senses agree -- a binding cap sits exactly
    on its constant either way -- and a port that read `==` as `<=` would pass.

    At the constant below -- 1,266,000, rounded down from twice the free volume's
    1,266,680.32 -- they disagree completely: `==` builds 1,266,000 and `<=` builds 633,340,
    because `<=` is simply slack there. The objectives differ by only 8e-6 relative,
    so it is the *capacities* that carry this one, which is why the suite asserts them.
    """
    return _ac_dc_tx("transmission_volume_expansion_limit", "==", 1_266_000.0)


def ac_dc_txcost():
    """A transmission *cost* limit at `>=`: build at least this much, by capital cost.

    The other left-hand side -- `sum(capital_cost * s_nom_opt)` rather than length-weighted --
    and the other sense. A floor rather than a cap is a real policy instrument, and it is
    also the only way to make `>=` bind on this network.

    It moves the objective by 1.2e-3 relative, which is above the 1e-6 the suites compare at
    but far from the volume cap's 56%. The reason is structural: forcing more transmission
    costs only `capital_cost` per MW and those are 0.009-0.2, against an objective dominated
    by a -3.47M sunk-capital term.

    ⚠️ The per-line capacities here are DEGENERATE and a port must not assert them. The
    constraint weights by the same `capital_cost` the objective charges, so every line trades
    one unit of cost for one unit of constraint and the LP is indifferent to which it builds:
    HiGHS simplex puts 21,982 on line 6, its interior point puts 76,382 on line 2, and the
    two agree on the objective to 1e-8 and on the cost-weighted total to four decimals. What
    is pinned is the objective and that total, which is what `GlobalConstraintSuite` asserts.
    """
    return _ac_dc_tx("transmission_expansion_cost_limit", ">=", 4_800.0)


def storage_cyclic_co2():
    """`storage-hvdc` with an EMITTING battery carrier and every storage unit cyclic.

    The fixture for a claim the port makes and could not previously check. PyPSA charges a
    `primary_energy` cap against non-cyclic storage only -- it filters
    `not cyclic_state_of_charge` and `not e_cyclic`, because a cyclic unit returns to its
    starting level and has no net primary energy to charge. So where every emitting unit is
    cyclic, PyPSA's row is generators-only, which is the only row this port knows how to
    build, and the two should agree exactly.

    Without this, the port admitted such a network on the strength of reading upstream's
    source, and nothing would have noticed if that filter ever differed: the answer would
    quietly be a cheaper one computed from a different row. Every other golden gives its
    battery carrier 0.0 emissions, so none of them can tell.

    The cap binds -- mu is -417.44 -- which is what makes the agreement worth asserting
    rather than a comparison of two unconstrained optima.
    """
    n = pypsa.examples.storage_hvdc()
    n.storage_units["cyclic_state_of_charge"] = True
    n.carriers.loc["battery", "co2_emissions"] = 0.3
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
    ("ac-dc-meshed", "pf"): "PyPSA raises AttributeError inside its own sub-network handling",
    ("ac-dc-dispatch", "pf"): "same as ac-dc-meshed, from which it is derived",
    ("ac-dc-co2", "pf"): "same as ac-dc-meshed, from which it is derived",
    ("ac-dc-txvolume", "pf"): "same as ac-dc-meshed, from which it is derived",
    ("ac-dc-txvolume-exact", "pf"): "same as ac-dc-meshed, from which it is derived",
    ("ac-dc-txcost", "pf"): "same as ac-dc-meshed, from which it is derived",
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


def transformer_levels():
    """Two voltage levels joined by transformers, with a cycle spanning them.

    The transformer golden every L2 module was refusing transformers for want of.
    A transformer's per-unit base is its own rating, `x / s_nom`, where a line's
    is voltage, `x / v_nom^2` -- and the difference is not small: on `scigrid-de`
    it is a factor of five, and on a 380 kV unit rated 500 MVA it is six orders of
    magnitude. Reusing the line formula gives a feasible LP with wrong flows,
    which is why they were refused rather than approximated.

    Two 380 kV buses and two 110 kV buses, a line at each level and a transformer
    at each end, so the network has a genuine cycle that crosses both
    transformers. That is what makes the impedance matter: in a radial network
    the flows are fixed by topology and any per-unit base would reproduce them.

    Nominal taps and no shunt, deliberately. `tap_ratio` folds into the linear
    models as a plain multiplier, but an off-nominal tap makes the AC admittance
    asymmetric and the T model needs a wye-delta conversion before Y is built --
    neither is written without a golden, so this validates the nominal conversion
    first.
    """
    n = pypsa.Network()
    n.set_snapshots(range(3))
    n.add("Bus", "hv1", v_nom=380.0)
    n.add("Bus", "hv2", v_nom=380.0)
    n.add("Bus", "lv1", v_nom=110.0)
    n.add("Bus", "lv2", v_nom=110.0)
    n.add("Line", "hv", bus0="hv1", bus1="hv2", x=0.5, r=0.0, s_nom=600.0)
    n.add("Line", "lv", bus0="lv1", bus1="lv2", x=0.3, r=0.0, s_nom=400.0)
    n.add("Transformer", "t1", bus0="hv1", bus1="lv1", x=0.10, r=0.0, s_nom=500.0, model="pi")
    n.add("Transformer", "t2", bus0="hv2", bus1="lv2", x=0.12, r=0.0, s_nom=400.0, model="pi")
    n.add("Generator", "g", bus="hv1", control="Slack", p_nom=900.0, marginal_cost=10.0)
    n.add("Generator", "gl", bus="lv2", p_nom=300.0, marginal_cost=60.0)
    n.add("Load", "d1", bus="lv1", p_set=[150.0, 120.0, 180.0])
    n.add("Load", "d2", bus="lv2", p_set=[100.0, 140.0, 90.0])
    return n


def transformer_taps():
    """Off-nominal taps and the T model, which the AC path used to refuse.

    `transformer-levels` deliberately holds every tap nominal and every shunt at
    zero, so it validates the per-unit conversion and nothing around it. This is
    the rest, and each transformer isolates one thing:

      - `thv` taps on the **high-voltage side** (`tap_side = 0`, PyPSA's
        default), so `Y00` is divided by `tau^2` and `Y11` is not.
      - `tlv` taps on the **low-voltage side** (`tap_side = 1`), the mirror. The
        two sides are not interchangeable and a model that ignored `tap_side`
        would reproduce one and not the other, which is why both are here.
      - `tt` uses the **T model with a non-zero shunt**, so it exercises the
        wye-delta conversion PyPSA applies before `Y` is built. Its `g` and `b`
        are deliberately large enough to move the answer: with a negligible shunt
        the T and pi models coincide and the conversion would be untested.
      - `tshift` combines an off-nominal tap **with** a phase shift, which is the
        case the two features could each get right alone and still get wrong
        together -- `Y01` and `Y10` then differ by both a magnitude and a
        conjugated argument.

    The topology is `transformer-levels`': two voltage levels, a line at each and
    a transformer at each end, so there is a cycle crossing the transformers.
    Radial flows are fixed by topology and would hide an impedance error.
    """
    n = pypsa.Network()
    n.set_snapshots(range(3))
    n.add("Bus", "hv1", v_nom=380.0)
    n.add("Bus", "hv2", v_nom=380.0)
    n.add("Bus", "lv1", v_nom=110.0)
    n.add("Bus", "lv2", v_nom=110.0)
    n.add("Line", "hv", bus0="hv1", bus1="hv2", x=0.5, r=0.02, s_nom=600.0)
    n.add("Line", "lv", bus0="lv1", bus1="lv2", x=0.3, r=0.01, s_nom=400.0)

    n.add("Transformer", "thv", bus0="hv1", bus1="lv1", x=0.10, r=0.004,
          s_nom=500.0, model="pi", tap_ratio=1.05, tap_side=0)
    n.add("Transformer", "tlv", bus0="hv2", bus1="lv2", x=0.12, r=0.005,
          s_nom=400.0, model="pi", tap_ratio=0.94, tap_side=1)
    n.add("Transformer", "tt", bus0="hv1", bus1="lv2", x=0.15, r=0.006,
          s_nom=300.0, model="t", g=0.004, b=0.02)
    # 8 degrees, not the 12 this carried until PyPSA 1.3.0. The shift entered the
    # LOPF's Kirchhoff row in that release, and 12 forces a circulating flow the
    # network cannot carry -- the linear flow puts -431 MW through `tshift`,
    # which is rated 350, and the optimisation has nowhere else to put it. The
    # network is infeasible from 10.007 degrees up, so the fixture stopped having
    # a dispatch to compare at all. At 8 the shift still reverses `tshift` --
    # +57 MW at zero against -268 here -- with 82 MW of headroom on the rating.
    n.add("Transformer", "tshift", bus0="hv2", bus1="lv1", x=0.11, r=0.005,
          s_nom=350.0, model="pi", tap_ratio=1.03, tap_side=0, phase_shift=8.0)

    n.add("Generator", "g", bus="hv1", control="Slack", p_nom=900.0, marginal_cost=10.0)
    n.add("Generator", "gl", bus="lv2", p_nom=300.0, marginal_cost=60.0)
    n.add("Load", "d1", bus="lv1", p_set=[150.0, 120.0, 180.0])
    n.add("Load", "d2", bus="lv2", p_set=[100.0, 140.0, 90.0])
    return n


def storage_cycle():
    """Storage that has to be arbitraged, with every flag `scigrid-de` leaves off.

    `scigrid-de` is the network storage is being implemented for -- it is the
    only thing standing between that network and LOPF -- but it exercises one
    path. Its 38 pumped-hydro units are all non-cyclic with no inflow, no
    standing loss, a zero initial state of charge, and snapshot weightings of
    exactly 1. So it validates the plain energy balance and nothing around it.

    This one is built for the rest:

      - `battery` is non-cyclic with a **non-zero initial state of charge** and a
        standing loss, so the `(1 - standing_loss)^elapsed_hours` factor and the
        `+ soc_initial` on the first snapshot both bind.
      - `cyclic` wraps: its first snapshot's previous state is its *last*
        snapshot's, which is a different constraint matrix rather than a
        different number, and is the case an implementation is most likely to
        get by writing `soc(-1) = 0`.
      - `hydro` has inflow, and at the second snapshot 180 MWh arrives into a
        unit already at its 100 MWh cap which can discharge at most 153 MWh, so
        **spilling is forced** -- at least 8.98 MW of it. A model without a spill
        variable is infeasible there rather than merely different, which is the
        strongest form this check can take. The first attempt at this fixture had
        inflow that the unit could simply discharge, and every spill came out
        zero.

    The weightings are deliberately three different numbers. PyPSA scales the
    energy balance by `snapshot_weightings.stores` and the objective by
    `snapshot_weightings.objective`, and every other fixture in this repository
    holds both at 1.0 -- where confusing the two is exactly invisible. Here
    stores is 3 and objective is 2, so reading the wrong column changes the
    answer.

    Efficiencies are distinct and none is 1: charging and discharging enter the
    balance on opposite sides (`* eff_store` against `/ eff_dispatch`), and equal
    values would let a transposed pair pass.
    """
    n = pypsa.Network()
    n.set_snapshots(range(6))
    # Three different numbers, on purpose -- see the docstring.
    n.snapshot_weightings.loc[:, "stores"] = 3.0
    n.snapshot_weightings.loc[:, "objective"] = 2.0
    n.snapshot_weightings.loc[:, "generators"] = 1.0

    n.add("Bus", "b", v_nom=110.0)
    # Cheap capacity well under peak demand, so the expensive unit sets the price
    # unless storage moves energy into the peak. That is what makes the state of
    # charge load-bearing rather than free.
    n.add("Generator", "cheap", bus="b", p_nom=120.0,
          marginal_cost=[10.0, 11.0, 17.0, 15.0, 13.0, 19.0])
    n.add("Generator", "peak", bus="b", p_nom=400.0, marginal_cost=200.0)
    n.add("Load", "d", bus="b", p_set=[240.0, 60.0, 90.0, 200.0, 70.0, 65.0])

    n.add("StorageUnit", "battery", bus="b", p_nom=60.0, max_hours=4.0,
          efficiency_store=0.90, efficiency_dispatch=0.95,
          state_of_charge_initial=50.0, standing_loss=0.02, marginal_cost=1.0)
    n.add("StorageUnit", "cyclic", bus="b", p_nom=40.0, max_hours=3.0,
          cyclic_state_of_charge=True,
          efficiency_store=0.95, efficiency_dispatch=0.85, marginal_cost=4.0)
    n.add("StorageUnit", "hydro", bus="b", p_nom=50.0, max_hours=2.0,
          efficiency_store=0.80, efficiency_dispatch=0.98,
          inflow=[30.0, 200.0, 0.0, 40.0, 0.0, 0.0], marginal_cost=2.0)
    return n


def inactive_components():
    """A network with components switched off, which PyPSA removes from the model.

    `active` is a boolean on every physical component and it does not mean "runs
    at zero" -- PyPSA drops the component from the model entirely. That reaches
    further than dispatch: an inactive branch is excluded from
    `determine_network_topology`, so it can split a sub-network in two and change
    which bus is slack.

    `standard-types` with two things off:

    `standard-types` with `hv23` switched off -- one of the three 380 kV lines,
    so the triangle becomes a path and the loop flow that fixture was built
    around disappears. Every remaining flow changes: `hv31` carries -300 MW
    where the two used to share it, and the optimum costs **68,408** against
    19,800.

    Deactivating a *generator* was the obvious choice and is the wrong one here.
    `peak` does not run at the optimum anyway, so switching it off changes
    nothing at all -- a fixture built on it would pass against an implementation
    that ignored `active` entirely, which is exactly the non-result the first
    attempt at this produced. The line was chosen because ignoring it gives a
    different answer rather than the same one.
    """
    n = standard_types_network()
    n.lines.loc["hv23", "active"] = False
    return n


def inactive_removed():
    """`inactive`'s network with the line genuinely deleted rather than switched off.

    The physically correct target for `inactive`, and the reason it has to exist
    as its own fixture: PyPSA's own two answers for "this line is not there"
    disagree, so one of them cannot be a golden.

    Deactivating `hv23` gives an objective of 68,407.58 with `peak` running at
    272 MW while cheap capacity sits idle. Deleting it gives 19,800 with `slack`
    serving all 435 MW -- which is the obvious merit order, and the answer this
    port produces.

    The cause is inside PyPSA. `cycle_matrix` still finds the loop
    hv12-hv23-hv31 when `hv23` is inactive, so `define_kirchhoff_voltage_
    constraints` emits a row for it -- but it selects the flow variables over
    active branches only, so `hv23`'s term drops out and the row collapses to
    `x12 f12 + x31 f31 = 0`. That is a voltage law for a loop that is not closed,
    and it pins `hv31` to a multiple of `hv12`: 27.6 MW on a branch rated 1700.
    Removing the line drops the cycle count from 2 to 1 and the phantom row with
    it.

    PyPSA is inconsistent with itself here rather than merely idiosyncratic: its
    *linear flow* excludes inactive branches from topology properly -- a two-bus
    network joined by one inactive line has two sub-networks -- so `n.lpf()` and
    `n.optimize()` model the same network differently. This port follows the
    linear flow, and the `inactive` fixture's `optimize` block is therefore
    evidence rather than a target.
    """
    n = standard_types_network()
    n.remove("Line", "hv23")
    return n


def piecewise_cost():
    """A generator whose marginal cost is a piecewise curve, new in PyPSA 1.3.0.

    Exported under `unsupported/` rather than `networks/`: `CsvReader` refuses a
    network carrying breakpoints, so this is a fixture for a *refusal* to read,
    not a network any L1 suite can load.

    It exists for its file name and its shape, both of which were guessed wrong
    the first time. The refusal originally matched a `_piecewise` suffix taken
    from PyPSA's *Excel* sheet-name table, which maps long sheet names to short
    ones for Excel's 31-character limit and describes neither end of the CSV
    path. `_CSVExporter.save_piecewise` writes ``f"{list_name}-{attr}-pw.csv"``,
    so the real file is `generators-marginal_cost-pw.csv` -- and it carries a
    **two-row header**, `name` over `attribute`, because the breakpoints are a
    MultiIndex-columned frame indexed by breakpoint rather than by snapshot:

        name,curved,curved
        attribute,p_pu,marginal_cost
        breakpoint,,
        0,0.0,0.0
        1,0.5,300.0
        2,1.0,1200.0

    A hand-written fixture agreed with the misreading and passed. This one comes
    from PyPSA, and `n.optimize()` is run on it here so the frame is known to be
    one PyPSA accepts rather than merely one it will write back out.

    `x` is `p_pu` and `y` is `marginal_cost`, per `piecewise_attrs("Generator")`.
    `curved` is cheaper than `flat` on its static `marginal_cost` and dearer over
    most of the curve, so a model that dropped the breakpoints and priced the
    static column would dispatch it and come out below the truth.
    """
    n = pypsa.Network()
    n.set_snapshots(range(3))
    n.add("Bus", "b")
    n.add("Load", "d", bus="b", p_set=[100.0, 140.0, 90.0])
    n.add("Generator", "flat", bus="b", control="Slack", p_nom=200.0, marginal_cost=40.0)
    n.add("Generator", "curved", bus="b", p_nom=200.0, marginal_cost=5.0)

    columns = pd.MultiIndex.from_product(
        [["curved"], ["p_pu", "marginal_cost"]], names=["name", "attribute"]
    )
    breakpoints = pd.DataFrame(
        [[0.0, 0.0], [0.5, 300.0], [1.0, 1200.0]], columns=columns
    )
    breakpoints.index.name = "breakpoint"
    n.c.Generator.piecewise["marginal_cost"] = breakpoints

    # Solved here and nowhere else. This fixture records no results -- the port
    # refuses the network -- so the only check that the frame is well formed is
    # that PyPSA's own optimiser accepts it. Built wrong, it raises a KeyError
    # about an index level and still exports a file that looks plausible.
    n.optimize(solver_name="highs")
    return n


def phase_shift():
    """A phase-shifting transformer, which both L2 models honour.

    `transformer-levels` with 9 degrees on one of its two transformers, and the
    cycle that fixture was built around is what makes the shift observable: it
    enters the flow as a constant injection, so a radial network would absorb it
    into the slack and show nothing.

    ==This fixture used to pin a disagreement, and no longer can==

    Until PyPSA 1.3.0 the two L2 models differed, and the difference was the
    point of the fixture. `n.lpf()` applied the shift -- `calculate_B_H` builds
    `p_branch_shift = -b * phase_shift` in radians and solves
    `B theta = p - K p_branch_shift`. `n.optimize()` did not: its Kirchhoff row
    was `sum(x_l s_l) == 0` with no shift term, so the optimised flows were
    *identical* at 0 and 30 degrees, and the LOPF golden here matched the
    unshifted one exactly.

    1.3.0 closed that gap. `define_kirchhoff_voltage_constraints` now builds
    `sum_l C_lk (x_l s_l + phase_shift_l) = 0` with the shift in radians, so the
    optimisation honours it too. What this fixture pins is that the shift is in
    the row at all: dropping the term leaves a model that balances every bus and
    returns `Optimal` on a dispatch that no phase shifter would produce.

    The two models are still not interchangeable, and at 9 degrees this fixture
    shows why. They agree exactly at the first snapshot. At the other two the
    linear flow puts 402.4 and 403.8 MW through `t2`, which is rated 400, and the
    LOPF -- which has the rating and a choice -- turns on the 60/MWh generator at
    `lv2` rather than overload it. That is the difference between an optimisation
    and a flow calculation, not a disagreement about phase shifters.

    ==Why 9 degrees and not 30==

    30 is now infeasible. The shift forces a circulating flow around the cycle,
    and past 10.044 degrees that flow exceeds `t2`'s 400 MW rating with nowhere
    else for it to go -- so the fixture would have had no dispatch to compare,
    only a status.

    10 is feasible and 9 is used anyway: 10 sits 0.04 degrees from the edge,
    which is a fixture that a change in nothing more than solver tolerance could
    turn into an `Infeasible` nobody asked for. 9 leaves a whole degree.

    It is a better fixture than 30 was even setting feasibility aside. At 9 the
    rating on `t2` binds, so the shift can no longer be absorbed by re-routing
    alone and the *objective* moves with it -- 8,524.43 against 7,800 unshifted.
    At 8 degrees and below nothing binds and the shift is pure re-routing at
    constant cost -- 7,800 at 0, 2, 4, 6, 7 and 8 alike -- which an
    objective-only comparison cannot see. `t1` reverses
    as well, +150.66 MW at zero against -146.70 here.
    """
    n = transformer_levels()
    n.transformers.loc["t1", "phase_shift"] = 9.0
    return n


def ramp_limits():
    """Ramp limits, which this port read nowhere until now.

    `ramp_limit_up` and `ramp_limit_down` default to NaN, are declared on
    Generator, Link and Process, and were named in this port only inside unit
    commitment's refusal. So a plain LOPF over a ramp-limited network solved the
    unconstrained problem and returned `Optimal`. No golden could see it: not one
    of the other fixtures sets a ramp limit, `scigrid-de` included.

    Every path through PyPSA's ramp builder gets an entity here, and each was
    checked by removing it and confirming the objective moves. Against the
    50,240 optimum:

      - `base` -- plain static limits on a fixed generator, and a **`p_init`** of
        60, so the first snapshot is bounded against a given rather than against
        `p(-1)`. Its `p(0)` lands on exactly `60 + 0.2*300 = 120`. Without the
        limits: 29,238. Without `p_init` alone: 42,300 -- PyPSA masks the first
        snapshot's rows out where `p_init` is NaN, which is its *default*, so
        reading NaN as zero is a wrong answer available for free.
      - `flex` -- a **time-varying** `ramp_limit_up`. Both up and down limits are
        `static or series` in PyPSA, and a series with no static counterpart is
        invisible to a `static.contains` check. Without it: 47,735.
      - `cold` -- `up_time_before = 0`, so it was down before the horizon. PyPSA
        then reads `p_init` as 0 rather than NaN *and* scales the up row by a
        prior status of zero, which pins `p(0)` to 0 exactly. Without it: 48,640.
        This is the only attribute here that changes the answer by changing
        whether a row exists at all.
      - `new` -- **extendable** and ramp-limited, so its limits multiply the
        capacity *variable* rather than a number in the file. Built to 200 MW,
        and its dispatch moves 115 -> 55 -> 115, hitting the +-60 band exactly at
        both ends.
      - `tie` -- a **Link**, because ramp limits are not a generator attribute.
        Its flow steps 10 -> 60, exactly `0.25 * 200`. Without it: 37,798.

    `peak` never runs. It is a safety valve: the ramp limits are tight enough
    that without an unconstrained unit somewhere the network cannot follow the
    load at all, and an infeasible fixture pins nothing.
    """
    n = pypsa.Network()
    n.set_snapshots(range(6))

    n.add("Bus", "a", v_nom=110.0)
    n.add("Bus", "b", v_nom=110.0)

    n.add("Link", "tie", bus0="a", bus1="b", p_nom=200.0, p_min_pu=-1.0,
          marginal_cost=0.5, ramp_limit_up=0.25, ramp_limit_down=0.25)

    n.add("Generator", "base", bus="a", p_nom=300.0, marginal_cost=10.0,
          ramp_limit_up=0.2, ramp_limit_down=0.3, p_init=60.0)
    n.add("Generator", "flex", bus="a", p_nom=150.0, marginal_cost=40.0,
          ramp_limit_up=[0.1, 0.2, 0.9, 0.15, 0.4, 0.25], ramp_limit_down=0.5)
    n.add("Generator", "cold", bus="b", p_nom=120.0, marginal_cost=20.0,
          ramp_limit_up=0.5, ramp_limit_down=0.5, up_time_before=0)
    n.add("Generator", "new", bus="b", p_nom=0.0, p_nom_extendable=True,
          p_nom_max=400.0, capital_cost=60.0, marginal_cost=25.0,
          ramp_limit_up=0.3, ramp_limit_down=0.3)
    n.add("Generator", "peak", bus="b", p_nom=600.0, marginal_cost=300.0)

    n.add("Load", "da", bus="a", p_set=[200.0, 90.0, 260.0, 130.0, 300.0, 110.0])
    n.add("Load", "db", bus="b", p_set=[140.0, 220.0, 80.0, 250.0, 120.0, 200.0])
    return n


def energy_budget():
    """`e_sum_max` and `e_sum_min`, which this port mentioned nowhere at all.

    A budget on a generator's energy across the whole horizon:

        e_sum_min  <=  sum_t weighting(t) * p(t)  <=  e_sum_max

    Their defaults are `+inf` and `-inf`, so an unset budget is invisible and a
    set one was simply dropped -- the LP stayed feasible and spent a fuel budget
    the network does not have. It is the largest discrepancy the schema sweep
    turned up: 30,600 here against 7,320 with both dropped.

    Each half is checked on its own. Without `e_sum_max`: 11,760. Without
    `e_sum_min`: 28,340. `cheap` is capped at exactly the 200 MWh it would
    otherwise beat every other unit to, and `must` is floored at exactly the 120
    MWh merit order would never give it, so both rows are tight at the optimum
    rather than merely present.

    ==The weighting column is the trap==

    PyPSA sums `p` against `snapshot_weightings.generators` -- the column the
    emissions cap uses, and *not* the `objective` column already in scope
    wherever this row gets built. Every other fixture in this repository holds
    all three weightings at 1.0, which is exactly where the confusion is
    invisible. Here `generators` is 2.0 against an `objective` of 1.0, so reading
    the wrong column gives 28,660 instead of 30,600.

    ==All three prices vary, and it took both rounds to see why==

    For the reason `store-bank` and `storage-cycle` vary theirs. At a flat price
    a budget fixes *how much* a generator produces and leaves *when* entirely
    free, so the first attempt matched the objective exactly and disagreed with
    PyPSA on every cell of the dispatch -- useless as a golden.

    Varying `cheap` and `mid` was not enough, and the reason is worth keeping.
    With `must` flat, its floor could slide between snapshots at exactly
    compensating cost: shifting 26.4 MW of `cheap` from the third snapshot to the
    first, letting `must` cover the gap there and give the same amount back at the
    fourth, came to 30,500.00 either way. Two genuinely tied vertices, and the
    port found the other one. Pricing *when* `must` runs closes it -- nudging any
    of the twelve costs by 1e-4 now moves the dispatch by exactly zero.
    """
    n = pypsa.Network()
    n.set_snapshots(range(4))
    n.snapshot_weightings.loc[:, "generators"] = 2.0
    n.snapshot_weightings.loc[:, "objective"] = 1.0
    n.snapshot_weightings.loc[:, "stores"] = 1.0

    n.add("Bus", "b", v_nom=110.0)
    n.add("Generator", "cheap", bus="b", p_nom=200.0,
          marginal_cost=[8.0, 14.0, 10.0, 12.0], e_sum_max=200.0)
    n.add("Generator", "mid", bus="b", p_nom=200.0,
          marginal_cost=[50.0, 46.0, 58.0, 52.0])
    n.add("Generator", "must", bus="b", p_nom=100.0,
          marginal_cost=[92.0, 88.0, 95.0, 90.0], e_sum_min=120.0)
    n.add("Load", "d", bus="b", p_set=[150.0, 180.0, 120.0, 200.0])
    return n


def investment_periods():
    """Two build years, which this port read as one period.

    `investment_periods.csv` sat in the reader's set of non-component files and
    nothing else read it, so a multi-period network arrived at the builder
    indistinguishable from an ordinary one. `new` has `build_year = 2040` and is
    inactive through 2030, which PyPSA enforces and this port did not: the
    expensive unit carries the whole first period at 80/MWh, costing **17,000**,
    against the **2,000** the port returned by running `new` ten years before it
    was built. Reported `Optimal`.

    Deliberately minimal. Nothing here is extendable and there is no discount
    rate, so the fixture isolates the one thing that matters — that periods
    change which assets exist — rather than mixing it with annuitised capital
    costs the port also does not model. That keeps the refusal's evidence to a
    single cause.

    The export is also what showed the reader misreads such a network: with
    `period` and `timestep` columns and no `snapshot` column, the label is taken
    from the first column after the index, so the four snapshots come back as
    `2030, 2030, 2040, 2040` — two pairs of duplicates — and `timestep` is parsed
    as a weighting.
    """
    n = pypsa.Network()
    snapshots = pd.MultiIndex.from_product(
        [[2030, 2040], range(2)], names=["period", "timestep"]
    )
    n.set_snapshots(snapshots)
    n.investment_periods = [2030, 2040]
    n.investment_period_weightings["objective"] = [1.0, 1.0]
    n.investment_period_weightings["years"] = [10, 10]

    n.add("Bus", "b", v_nom=110.0)
    n.add("Generator", "new", bus="b", p_nom=200.0, marginal_cost=5.0,
          build_year=2040, lifetime=30)
    n.add("Generator", "old", bus="b", p_nom=200.0, marginal_cost=80.0)
    n.add("Load", "d", bus="b", p_set=[100.0] * 4)
    return n


def investment_periods_discounted():
    """The two per-period weightings, on a fixture where they are not 1.0.

    `investment_periods.csv` carries `objective` and `years` beside the label and
    they do different jobs: `objective` discounts every cost in the period, while
    `years` says how many years the period stands for and scales a primary-energy
    sum. `investment-periods` holds both at 1.0, so reading either, swapping them
    or ignoring both gives the identical answer there -- which is the weak-fixture
    shape this directory keeps rediscovering.

    Worse, neither weighting is applied at all unless the solve is told to apply
    it. PyPSA gates them on `n._multi_invest`, which is set by
    `optimize(multi_investment_periods=True)` and nothing else; the *activity
    window* is gated separately, on whether the snapshot index has periods. So a
    multi-period network solved without the flag masks assets by `build_year` and
    still charges every period undiscounted. This script passed no flag anywhere
    until this fixture, so `investment-periods` was generated in that mode -- and
    its unit weights hid it.

    Here `objective` is `[1.0, 0.6]` and `years` is `[10, 5]`, and both bind:

      - `gas` is the cheap unit at every snapshot, so an undiscounted,
        uncapped solve runs it flat out for **9,200**.
      - The CO2 cap is a primary-energy constraint, so its sum carries `years`.
        Weighted, emissions come to 3,000 against a cap of 2,500; unweighted they
        come to 400 and the cap is slack. That alone separates the two modes.
      - Abatement is cheapest where `(VOLL - mc) * objective / years` is
        smallest, which is 2030's second snapshot at 7.8 -- *not* 2040, even
        though 2040 is discounted, because 2030's `years` is twice as large. A
        reading that swapped the two columns would abate in the other period.

    PyPSA pays **11,100** with 50 MW of `clean` at one snapshot, and the cap's
    shadow price is **-7.8**. Deliberately interior: the abatement is 50 of a
    possible 100 MW at a single snapshot, so the vertex is unique and simplex and
    interior-point agree to the digit -- unlike a cap that happens to retire a
    whole block, where the dual sits between two adjacent merit-order steps.

    Nothing is extendable and no asset has a build year, which keeps this about
    the weightings alone; `investment-periods` is the fixture for the activity
    window.
    """
    n = pypsa.Network()
    snapshots = pd.MultiIndex.from_product(
        [[2030, 2040], range(2)], names=["period", "timestep"]
    )
    n.set_snapshots(snapshots)
    n.investment_periods = [2030, 2040]
    n.investment_period_weightings["objective"] = [1.0, 0.6]
    n.investment_period_weightings["years"] = [10, 5]

    n.add("Bus", "b", v_nom=110.0)
    n.add("Carrier", "gas", co2_emissions=0.5)
    # `efficiency` 0.5 against `co2_emissions` 0.5 makes a tonne per MWh of
    # electricity, so the cap can be read off the dispatch without dividing.
    # Marginal cost rises across the horizon so that no two snapshots are
    # equally cheap to abate at: on a flat cost the 100 MWh of abatement splits
    # arbitrarily between 2030's two snapshots and the dispatch is a face rather
    # than a point.
    n.add("Generator", "gas", bus="b", p_nom=200.0, carrier="gas", efficiency=0.5,
          marginal_cost=[20.0, 22.0, 24.0, 26.0])
    n.add("Generator", "clean", bus="b", p_nom=200.0, marginal_cost=100.0)
    n.add("Load", "d", bus="b", p_set=[100.0] * 4)
    n.add("GlobalConstraint", "co2", type="primary_energy",
          carrier_attribute="co2_emissions", sense="<=", constant=2500.0)
    return n


def investment_periods_expansion():
    """Capacity as a decision on a multi-period network, which was refused.

    The refusal said capacity expansion across investment periods is "a different
    model, not this one with an extra factor", on the grounds that PyPSA gives each
    build year its own asset. It does not. There is **one** capacity variable per
    asset; `build_year` is an input on the static frame saying which periods the
    asset exists in, and `define_objective` charges its capital once per such
    period:

        cost_weight = (active.groupby("period").any("snapshot") * period_weight).sum("period")

    So `wind`, active in both periods, is charged `120 * (1.0 + 0.5) = 180` per MW,
    and `late`, built in 2040, is charged `60 * 0.5 = 30`. Reading either as the
    unweighted `capital_cost` or giving `late` both periods' weight is a different
    objective, and the fixture separates all three.

    Three other things it pins, each of which was wrong or unreachable before:

      - **The activity window on an extendable asset.** An extendable column is
        declared free and its limits live in two rows against the capacity
        variable, so `activeBounds` never touched it -- which means `late`, at a
        marginal cost of 5 against `old`'s 100, would have run through 2030 as
        well. Cheaper than the truth, reporting `Optimal`.
      - **Those rows have to be masked too, not just the column.** The lower row
        is `p - p_min_pu * p_nom >= 0`; with `p` pinned to zero and a positive
        `p_min_pu` it reads `-p_min_pu * p_nom >= 0` and forces the *capacity* to
        zero over the whole horizon. PyPSA masks both rows with `active`.
      - **`objective_constant` is 0.0 under `multi_investment_periods`.**
        `define_objective` computes the sunk-capital term in both branches and
        appends it only in the single-period one, so the same network reports a
        constant of 3,000 without the flag and 0.0 with it. Reproduced rather than
        corrected: the objective is what a port is compared on.

    `p_nom_max = 60` on `wind` is what makes both assets bind -- uncapped, wind
    alone would serve the whole load in every period and `late` would be built to
    zero, which is a fixture that cannot see `late`'s weighting at all.

    PyPSA pays **20,175** with `wind` at 60 MW and `late` at 40, and prices
    [100, 100, 65, 5]: 65 is `5 + 30 / 0.5`, `late`'s bid plus its capital
    coefficient divided back out by the period discount, at the one snapshot where
    its capacity binds. The 2040 load drops to 90 at the last snapshot precisely so
    that it does *not* bind there -- with a flat load both 2040 prices are a dual
    face, and simplex and interior-point disagreed on them (60/0 against 30/30)
    while agreeing on everything else. As written the two solvers agree on the
    objective, the capacities, the dispatch and all four prices.
    """
    n = pypsa.Network()
    snapshots = pd.MultiIndex.from_product(
        [[2030, 2040], range(2)], names=["period", "timestep"]
    )
    n.set_snapshots(snapshots)
    n.investment_periods = [2030, 2040]
    n.investment_period_weightings["objective"] = [1.0, 0.5]
    n.investment_period_weightings["years"] = [10, 10]

    n.add("Bus", "b", v_nom=110.0)
    # The backstop, so no period can be infeasible and every capacity decision is
    # a cost comparison rather than a feasibility one.
    n.add("Generator", "old", bus="b", p_nom=100.0, marginal_cost=100.0)
    # Both extendables carry a non-zero `p_nom`, which is what the objective
    # constant is computed from: 120 * 20 + 60 * 10 = 3,000. It is inert in the LP
    # -- `p_nom_min` defaults to 0 -- and exists only so that subtracting the
    # constant is visibly wrong rather than invisibly so.
    n.add("Generator", "wind", bus="b", p_nom=20.0, p_nom_extendable=True,
          p_nom_max=60.0, capital_cost=120.0, marginal_cost=0.0)
    n.add("Generator", "late", bus="b", p_nom=10.0, p_nom_extendable=True,
          capital_cost=60.0, marginal_cost=5.0, build_year=2040, lifetime=30)
    n.add("Load", "d", bus="b", p_set=[100.0, 100.0, 100.0, 90.0])
    return n


def operational_limit():
    """A cap on a carrier's energy, which is a different row from a cap on its emissions.

    `operational_limit` was the type the global-constraint refusal named as its own
    justification: assuming `type` would "take an `operational_limit` capping a carrier's
    *energy* and build it as an emissions-weighted sum over every emitting generator -- a
    different constraint wearing the same right-hand side, returning Optimal". It is built
    now, and this is the fixture that says what it is.

    `define_operational_limit`'s left-hand side has three parts, and the fixture makes all
    three observable at once:

      - **Generation of the named carrier, weighted by `snapshot_weightings.generators`.**
        This is the first fixture anywhere here whose three snapshot weightings are not all
        equal: `generators` is 3.0 while `objective` and `stores` stay 1.0. Every other
        golden holds them equal, so until now no comparison could tell which column a sum
        read. With `objective` in its place the cap's left-hand side comes to 360 against a
        limit of 680 and stops binding altogether.
      - **The net depletion of a non-cyclic storage unit, as one variable plus a constant.**
        `res` starts at 500 and ends at 300, so it contributes `-soc(last) + 500 = 200`. Read
        as `sum_t p_dispatch` instead it would be the same number here only by accident; the
        mutation that lets `res` refill makes the two differ.
      - **Nothing at all for a cyclic unit.** `pump` carries the same `hydro` carrier and is
        cyclic, so PyPSA filters it out, and `marginal_cost_storage = 0.01` is what makes that
        checkable. Not by tightening the cap: a port that included `pump` would add
        `-soc(pump, last) + 20`, and the LP can drive that to zero by holding the unit charged
        at the last snapshot. What that costs is the storage price, so the objective comes out
        2,800.8 rather than 2,800. At the attribute's default of zero the lever is free, the
        term goes to exactly zero, and including a cyclic unit is invisible. The 0.01 also
        pins the level, which is why the dispatch is determinate at all -- a cyclic unit with
        no flow and no storage price has a state of charge free to sit anywhere in its band.

    And `gas` runs, 40 MW at the first snapshot, so a port that summed every generator
    instead of the named carrier's would reach 800 rather than 680.

    PyPSA pays **2,800** with `ror` at [60, 50, 40, 10] against a profile allowing
    [60, 50, 40, 30]. The cap is met by cutting `ror` at the snapshot where replacing it is
    cheapest and by letting `res` carry more, and it is *partial* at the last snapshot --
    10 of an available 30 -- which is what makes the shadow price of -3.0 a point rather
    than an interval. The cap was swept for that: at 720 and at 660 the answer sits on a
    corner and simplex and interior-point disagree about the dispatch, while at 680 they
    agree on the objective, every dispatch, the state of charge and all four nodal prices.
    """
    n = pypsa.Network()
    n.set_snapshots(range(4))
    # Explicit on all three, and not only on the one that matters. Leaving `objective` and
    # `stores` implicit would make the fixture's point -- that the three are different
    # columns -- depend on a default rather than state it.
    n.snapshot_weightings.loc[:, "objective"] = 1.0
    n.snapshot_weightings.loc[:, "stores"] = 1.0
    n.snapshot_weightings.loc[:, "generators"] = 3.0

    n.add("Bus", "b", v_nom=110.0)
    n.add("Carrier", "hydro")
    n.add("Carrier", "gas")
    # Run-of-river: a rising marginal cost and a falling availability, so each snapshot is a
    # distinct place to cut and the cheapest cut is unique.
    n.add("Generator", "ror", bus="b", carrier="hydro", p_nom=100.0,
          marginal_cost=[1.0, 2.0, 3.0, 4.0], p_max_pu=[0.6, 0.5, 0.4, 0.3])
    # 12 at the first snapshot and far above `res` afterwards, so gas runs under the cap and
    # only there. A gas price above `res`'s everywhere would leave gas off and the carrier
    # filter unobservable.
    n.add("Generator", "gas", bus="b", carrier="gas", p_nom=300.0,
          marginal_cost=[12.0, 20.0, 22.0, 24.0])
    n.add("StorageUnit", "res", bus="b", carrier="hydro", p_nom=120.0, max_hours=10.0,
          state_of_charge_initial=500.0, marginal_cost=10.0)
    n.add("StorageUnit", "pump", bus="b", carrier="hydro", p_nom=20.0, max_hours=1.0,
          cyclic_state_of_charge=True, state_of_charge_initial=20.0,
          marginal_cost=30.0, marginal_cost_storage=0.01)
    n.add("Load", "d", bus="b", p_set=[100.0] * 4)
    n.add("GlobalConstraint", "hydro_budget", type="operational_limit",
          carrier_attribute="hydro", sense="<=", constant=680.0)
    return n


def tech_capacity_limit():
    """A cap on buildable capacity per carrier, and per carrier at one bus.

    `tech_capacity_expansion_limit` is the fifth and last of the types
    `global_constraints.py` dispatches on, and it was the last one left here rather than the
    hardest: its left-hand side is one coefficient of 1.0 per capacity column, with no
    weighting and no snapshot index. What it needed was capacity to be a decision at all.

    Three things about the columns, and the first is a trap:

      - `carrier_attribute` names a **carrier** on this type, not a column of
        `carriers.csv`. The same field means `co2_emissions` on `primary_energy` and `hydro`
        on `operational_limit`, and PyPSA reads it both ways from one field.
      - `bus` is optional and is a bus name. PyPSA treats an empty string as absent
        (`glc.get("bus") or None`), which matters because a CSV round-trip writes an unset
        `bus` as `""` rather than omitting the column.
      - A branch counts at its `bus0` only, so a cap at one bus counts a line leaving it and
        not the same line arriving. Asymmetric, and upstream's.

    And despite the name it caps **capacity**, not expansion: the left-hand side is the same
    column the objective charges `capital_cost` on, which for an extendable asset is the
    whole optimal capacity. An asset arriving with `p_nom = 100` counts its full `p_nom_opt`,
    so a cap of 100 permits no addition rather than 100 of it. Both extendables here start at
    `p_nom = 0` so that reading is not what the fixture is about; the docstring says it
    because the name invites the other one.

    The structure is deliberate. Every renewable here has zero marginal cost and `gas` sits
    at 80, and the caps keep renewable output below the load at both snapshots -- so each MW
    of capacity displaces gas at a **constant** rate and its net value is constant too:
    `windA` +20/MW, `windB` +30/MW, `solarA` +20/MW. That makes the problem linear in the
    capacities with nothing but the caps to bound them, which is the only arrangement where
    both caps bind at once and the answer is still a point:

      - `windB` is worth most per MW, so it fills its bus cap of 40 first
      - `windA` takes the remaining 80 of the network-wide 120
      - `solarA` is a different carrier, so neither cap touches it and it builds to its
        `p_nom_max` of 50 -- which is also what makes the carrier filter observable, since a
        port that counted every extendable would have 120 to share between three assets

    PyPSA pays **19,400** with shadow prices of -20 on the total and -10 at the bus, and
    simplex and interior-point agree on the objective, all three capacities, every dispatch
    and the line flow. `p_nom_max` on `solarA` is load-bearing in a way the caps are not: at
    a constant +20/MW with no cap and no limit its capacity is unbounded.
    """
    n = pypsa.Network()
    n.set_snapshots(range(2))

    n.add("Bus", "A", v_nom=110.0)
    n.add("Bus", "B", v_nom=110.0)
    for carrier in ("AC", "wind", "solar", "gas"):
        n.add("Carrier", carrier)
    n.buses["carrier"] = "AC"
    # Fixed, and rated far above anything that crosses it. An extendable line would bring the
    # two transmission limit types into a fixture that is about neither.
    n.add("Line", "AB", bus0="A", bus1="B", x=0.1, r=0.01, s_nom=500.0, carrier="AC")

    n.add("Generator", "gas", bus="A", carrier="gas", p_nom=300.0, marginal_cost=80.0)
    # Complementary profiles, so neither wind site is simply better than the other at every
    # snapshot and the line carries something in both directions of the merit order.
    n.add("Generator", "windA", bus="A", carrier="wind", p_nom=0.0, p_nom_extendable=True,
          capital_cost=100.0, marginal_cost=0.0, p_max_pu=[1.0, 0.5])
    n.add("Generator", "windB", bus="B", carrier="wind", p_nom=0.0, p_nom_extendable=True,
          capital_cost=90.0, marginal_cost=0.0, p_max_pu=[0.5, 1.0])
    n.add("Generator", "solarA", bus="A", carrier="solar", p_nom=0.0, p_nom_extendable=True,
          p_nom_max=50.0, capital_cost=60.0, marginal_cost=0.0, p_max_pu=[0.8, 0.2])

    n.add("Load", "dA", bus="A", p_set=[100.0, 60.0])
    n.add("Load", "dB", bus="B", p_set=[50.0, 80.0])

    n.add("GlobalConstraint", "wind_total", type="tech_capacity_expansion_limit",
          carrier_attribute="wind", sense="<=", constant=120.0)
    n.add("GlobalConstraint", "wind_at_b", type="tech_capacity_expansion_limit",
          carrier_attribute="wind", bus="B", sense="<=", constant=40.0)
    return n


def period_scoped_constraints():
    """A CO2 budget per investment period, which was refused as having no defensible sign.

    `investment_period` on a global constraint restricts it to one period. It was refused
    here, and the refusal's reasoning was right as far as it went: read as horizon-wide, a
    cap meant for 2040 alone is **tighter** than the network states and makes the answer
    dearer, while read as a cap per period rather than in total it is looser. Neither
    direction is defensible, so refusing beat guessing.

    What was missing was that there is nothing to guess. PyPSA does three separate things
    with the column, and none of them is the same as the others:

      - `primary_energy` and `operational_limit` sum over **that period's snapshots alone**
        (`period_sns = sns[period_of == period]`)
      - the three capacity types filter their **assets** by activity in that period, and
        their *unscoped* cases disagree with each other: a transmission limit passes every
        period the snapshots carry, so "active in any", while
        `tech_capacity_expansion_limit` passes `None`, so the `active` flag alone with build
        year and lifetime ignored
      - a scope naming a period the horizon does not cover builds **no row at all**, which
        is not the same as a row over an empty left-hand side

    And a scope on a network whose snapshots carry no periods is a crash upstream, not a
    behaviour: `periods` is bound only inside `if n._multi_invest`, so the solve dies with
    `UnboundLocalError: cannot access local variable 'periods'`. Measured on both
    snapshot-summing types.

    This fixture is the first of those three. Two caps, one per period, both binding:

      - `years` is [10, 5], so an identical MWh of gas costs ten emission units in 2030 and
        five in 2040. The caps are 1,500 against an unscoped 2,000 and 600 against an
        unscoped 1,000, so each period has to abate and neither can borrow from the other.
      - `gas` rises 10/12/14/16 across the horizon, so within each period there is a strictly
        cheapest snapshot to abate at and the answer is a point rather than a face. 2030
        abates 50 MW at its second snapshot and 2040 abates 80 at its second.
      - The shadow prices come out -7.8 and -14.8 -- `(90 - 12) / 10` and `(90 - 16) / 5` --
        which is what says the two rows carry their own period's `years` and not each other's.

    PyPSA pays **15,020**. A port that applied either cap to the whole horizon, or summed
    both periods into one row, lands nowhere near it: horizon-wide emissions are 3,000
    against the tighter of the two constants.
    """
    n = pypsa.Network()
    snapshots = pd.MultiIndex.from_product(
        [[2030, 2040], range(2)], names=["period", "timestep"]
    )
    n.set_snapshots(snapshots)
    n.investment_periods = [2030, 2040]
    # `objective` flat on purpose. `investment-periods-discounted` is the fixture for the
    # discount factor; here the only per-period weighting that should move anything is
    # `years`, so holding the other at 1.0 keeps a failure unambiguous between them.
    n.investment_period_weightings["objective"] = [1.0, 1.0]
    n.investment_period_weightings["years"] = [10, 5]

    n.add("Bus", "b", v_nom=110.0)
    n.add("Carrier", "gas", co2_emissions=1.0)
    n.add("Carrier", "clean")
    # `efficiency` 1.0 against `co2_emissions` 1.0 makes a tonne per MWh of electricity, so
    # the caps can be read off the dispatch without dividing.
    n.add("Generator", "gas", bus="b", carrier="gas", p_nom=200.0, efficiency=1.0,
          marginal_cost=[10.0, 12.0, 14.0, 16.0])
    n.add("Generator", "clean", bus="b", carrier="clean", p_nom=200.0, marginal_cost=90.0)
    n.add("Load", "d", bus="b", p_set=[100.0] * 4)

    n.add("GlobalConstraint", "co2_2030", type="primary_energy",
          carrier_attribute="co2_emissions", sense="<=", constant=1500.0,
          investment_period=2030)
    n.add("GlobalConstraint", "co2_2040", type="primary_energy",
          carrier_attribute="co2_emissions", sense="<=", constant=600.0,
          investment_period=2040)
    return n


def link_delay():
    """A link whose energy arrives later, which this port delivered instantly.

    PyPSA's `delay` shifts a link's output by whole snapshots: power entering at
    `t` leaves at `t + delay`. The default is 0, so the attribute is inert
    unless set and no other fixture sets one — the same shape as the four
    attributes the schema sweep turned up.

    The load sits entirely at the first snapshot, and `tie` is the only route
    from the cheap generator to it. Undelayed, the import serves the load for
    **500**. With `delay = 1` the import cannot arrive in time at all, so the
    expensive local unit carries it and PyPSA pays **9,000** — eighteen times
    more. This port paired both ends of the link within one snapshot and
    returned the 500 either way.

    `cyclic_delay` is off, so energy still in flight at the end of the horizon
    is lost rather than wrapping to the beginning.

    What this fixture cannot gate is the shift itself. The delay here makes the
    link *useless*, so an implementation that deleted the link outright would
    reproduce every number in it. `link-delay-wrap` is the one that makes the
    shift carry something.
    """
    n = pypsa.Network()
    n.set_snapshots(range(4))

    n.add("Bus", "a", v_nom=110.0)
    n.add("Bus", "b", v_nom=110.0)
    n.add("Generator", "cheap", bus="a", p_nom=200.0, marginal_cost=5.0)
    n.add("Generator", "local", bus="b", p_nom=200.0, marginal_cost=90.0)
    n.add("Link", "tie", bus0="a", bus1="b", p_nom=150.0, efficiency=1.0,
          delay=1, cyclic_delay=False)
    n.add("Load", "d", bus="b", p_set=[100.0, 0.0, 0.0, 0.0])
    return n


def link_delay_wrap():
    """A delay that is used rather than merely blocking, and the wrap.

    `link-delay` proves the refusal was worth having and nothing more: its delay
    makes the only route to the load useless, so every number in it is
    reproduced by an implementation that deletes the link. Four things have to
    bind before the shift itself is gated, and each is a separate wrong reading
    that would otherwise pass.

    ==The delay is elapsed time, not a snapshot count==

    `delay` is declared in "snapshot weighting units" and measured against
    `snapshot_weightings.generators` -- the same column the energy budgets read,
    not the `objective` one beside it. Here `generators` is 2.0, so both links'
    delays are half what they look like: `wrap` at 2 reaches back one snapshot
    and `lag` at 4 reaches back two. An implementation counting snapshots reaches
    back two and four instead.

    The `objective` weighting is 1, 1, 3, 1 rather than flat, so *when* the cheap
    unit runs changes what it costs. Without that, reaching back the wrong number
    of snapshots lands on the same objective and only the dispatch frames can see
    it.

    ==`cyclic_delay` wraps, and the mask is not a zero==

    `wrap` is cyclic. Its load sits at the first snapshot and the only source of
    energy for it is the *last* one, which is a snapshot that has not happened
    yet in every reading but PyPSA's -- 500 against the 9,000 of running the
    local unit instead.

    `lag` is not cyclic, and its first two snapshots have no source at all. PyPSA
    still computes an index for them and discards it through a validity mask; the
    index it discards is 0. Keeping it makes the link instantaneous at the first
    snapshot, and since nothing at `d` can absorb the arrival, that pins the
    link's flow to zero and the load at the third snapshot falls to `local2`:
    24,000 rather than 400.

    ==Efficiency is read at the arrival snapshot==

    `wrap`'s efficiency is 0.5 at the first snapshot and 1.0 elsewhere, and the
    constraint multiplies the *shifted* flow by the efficiency of the snapshot it
    arrives in -- so 200 MW must leave at the fourth snapshot for 100 MW to
    arrive at the first. Reading the departure snapshot's efficiency instead
    sends 100 and pays 500 where PyPSA pays 1,000.

    PyPSA disagrees with itself here, which is worth recording rather than
    smoothing over. `_apply_delay_shift` shifts `-p0 * efficiency` as a product
    when it writes the results back, so `links_t.p1` reports -200 at the first
    snapshot against the 100 its own balance row received. The constraint is what
    the objective and the dispatch come from, and it is what this port matches;
    nothing here reads `p1`.

    The two links sit on disjoint sub-networks so that neither can serve the
    other's load. Both loads are single-snapshot, which leaves the whole schedule
    forced rather than merely optimal: the arrival at a bus with no load has to be
    zero, so every other flow is pinned at zero too.
    """
    n = pypsa.Network()
    n.set_snapshots(range(4))
    n.snapshot_weightings.loc[:, "generators"] = 2.0
    n.snapshot_weightings.loc[:, "objective"] = [1.0, 1.0, 3.0, 1.0]
    n.snapshot_weightings.loc[:, "stores"] = 1.0

    # Cyclic: the load at the first snapshot is served from the last one.
    n.add("Bus", "a", v_nom=110.0)
    n.add("Bus", "b", v_nom=110.0)
    n.add("Generator", "cheap", bus="a", p_nom=400.0, marginal_cost=5.0)
    n.add("Generator", "local", bus="b", p_nom=400.0, marginal_cost=90.0)
    n.add("Link", "wrap", bus0="a", bus1="b", p_nom=250.0, delay=2,
          cyclic_delay=True, efficiency=[0.5, 1.0, 1.0, 1.0])
    n.add("Load", "dw", bus="b", p_set=[100.0, 0.0, 0.0, 0.0])

    # Non-cyclic: the first two snapshots have no source, and the load at the
    # third is served from the first.
    n.add("Bus", "c", v_nom=110.0)
    n.add("Bus", "d", v_nom=110.0)
    n.add("Generator", "cheap2", bus="c", p_nom=400.0, marginal_cost=4.0)
    n.add("Generator", "local2", bus="d", p_nom=400.0, marginal_cost=80.0)
    n.add("Link", "lag", bus0="c", bus1="d", p_nom=150.0, delay=4,
          cyclic_delay=False)
    n.add("Load", "dl", bus="d", p_set=[0.0, 0.0, 100.0, 0.0])
    return n


def store_bank():
    """Stores, which are not StorageUnits with a different name.

    Three differences drive the whole component, and a fixture that does not
    exercise them would pass against a StorageUnit implementation wearing a
    Store's label:

      - '''One signed power variable, not two.''' A Store has no charging and
        discharging efficiencies, so there is nothing to split. `p > 0`
        discharges into the bus and `p < 0` charges from it.
      - '''No power rating at all.''' PyPSA generates no operational bound for
        `Store-p`; how fast a store can move energy follows only from its energy
        band and the elapsed hours. A model that bounded `p` by `e_nom` would be
        strictly tighter and would look reasonable.
      - '''The energy band is `[e_min_pu, e_max_pu] · e_nom`,''' not `[0, e_nom]`.
        `tank` sets 0.1 and 0.5, and its energy touches both 20 and 100 -- so a
        model reading the default band would find a cheaper answer at each end.

    `tank` is non-cyclic with a non-zero `e_initial` and a standing loss;
    `swing` cycles, and ends the horizon holding 150 MWh which it discharges at
    the first snapshot -- something a non-cyclic store starting from zero cannot
    do, which is what makes the wrap observable rather than merely present;
    `grow` is extendable, ties Stores to the capacity machinery, and is built to
    exactly its `e_nom_max` of 60, so the capacity variable's own upper bound
    binds too.

    Every store carries a standing loss and `swing` a `marginal_cost_storage`,
    without which the optimum is degenerate. A lossless store that begins and
    ends the horizon at the same level has `sum(p) = 0`, so its `marginal_cost`
    contributes *nothing* whatever the schedule — Prima and HiGHS agreed on the
    objective to 5e-11 and disagreed on both trajectories. Making it costly to
    hold energy is what picks one out.

    Getting all of that to bind at once took several attempts, each failing in a
    way worth naming: `grow` unbuilt and `peak` barely running; `swing` ending
    its horizon empty, which makes a cyclic store indistinguishable from a
    non-cyclic one starting at zero; `tank`'s upper band never touched; and then
    the degeneracy above. A flag that is set but does not bind tests nothing.

    The generator price varies by snapshot on purpose. `storage-cycle` was first
    written with a flat price and its optimum came out degenerate: Prima and
    HiGHS agreed on cost to 6e-10 and disagreed on the whole trajectory, which is
    useless as a golden. Snapshot weightings differ from each other for the same
    reason as there -- `stores` scales the energy balance and `objective` scales
    cost, and holding both at 1.0 makes confusing them invisible.
    """
    n = pypsa.Network()
    n.set_snapshots(range(6))
    n.snapshot_weightings.loc[:, "stores"] = 2.0
    n.snapshot_weightings.loc[:, "objective"] = 3.0
    n.snapshot_weightings.loc[:, "generators"] = 1.0

    n.add("Bus", "b", v_nom=110.0)
    n.add("Generator", "cheap", bus="b", p_nom=120.0,
          marginal_cost=[10.0, 12.0, 21.0, 18.0, 14.0, 23.0])
    n.add("Generator", "peak", bus="b", p_nom=400.0, marginal_cost=250.0)
    n.add("Load", "d", bus="b", p_set=[300.0, 40.0, 50.0, 290.0, 60.0, 70.0])

    n.add("Store", "tank", bus="b", e_nom=200.0, e_initial=90.0,
          e_min_pu=0.1, e_max_pu=0.5, standing_loss=0.02, marginal_cost=1.0)
    n.add("Store", "swing", bus="b", e_nom=150.0, e_cyclic=True, marginal_cost=4.0,
          standing_loss=0.05, marginal_cost_storage=0.30)
    n.add("Store", "grow", bus="b", e_nom=0.0, e_nom_extendable=True,
          e_nom_max=60.0, capital_cost=12.0, marginal_cost=2.0, standing_loss=0.01)
    return n


def standard_types_network():
    """Impedance that exists nowhere in the exported files, only in a type name.

    `scigrid-de` is the network this is for, and it is not usable as the gate on
    its own. All 852 of its lines carry `x = 0` and a `type`, so the impedance
    comes from `LineType` + `length` + `num_parallel` -- and PyPSA omits the
    standard type library from every export, because its own reader repopulates
    it from the installed package. A reader given those files alone has no way to
    know what an 'Al/St 240/40 4-bundle 380.0' is.

    But scigrid-de's 96 transformers carry an explicit `x` and no type at all, so
    it validates the line half and nothing else. This fixture covers both, small
    enough to read:

      - A 380 kV triangle whose three lines take three *different* standard
        types, with different lengths and `num_parallel`. The loop flow splits by
        impedance, so a wrong `x_per_length * length / num_parallel` shows up as a
        wrong flow rather than being absorbed by topology.
      - A 110 kV line joined to the triangle by two transformers of a standard
        type, so a second cycle crosses both and the transformer conversion --
        `r = vscr/100`, `x = sqrt((vsc/100)^2 - r^2)`, both divided by
        `num_parallel`, with `s_nom` taken from the type -- is load-bearing too.

    `num_parallel = 2` on one line and one transformer, deliberately: it divides
    the series impedance and *multiplies* the shunt, and getting that backwards
    is invisible at `num_parallel = 1`, which is the default everywhere else.

    The chosen types have `phase_shift = 0`. The 110/20 kV transformer types in
    PyPSA's library are Dyn5 with a 150 degree shift, which this port does not
    model at all -- picking one would have tested type expansion and a missing
    feature at the same time, and failed for the wrong reason.

    `model = "pi"` is set explicitly, against PyPSA's default of "t". Every
    transformer type has a non-zero magnetising shunt, and under the T model
    PyPSA converts it wye-delta before building Y; that conversion is a separate
    unimplemented piece. Setting "pi" isolates type expansion from it, and keeps
    the line types' `b` -- which only matters in an AC solve -- validated rather
    than dropped along with the whole `pf` stage.
    """
    n = pypsa.Network()
    n.set_snapshots(range(3))
    for bus in ("hv1", "hv2", "hv3"):
        n.add("Bus", bus, v_nom=380.0)
    for bus in ("mv1", "mv2"):
        n.add("Bus", bus, v_nom=110.0)

    n.add("Line", "hv12", bus0="hv1", bus1="hv2",
          type="Al/St 240/40 4-bundle 380.0", length=120.0, num_parallel=1.0, s_nom=1700.0)
    n.add("Line", "hv23", bus0="hv2", bus1="hv3",
          type="Al/St 490/64 4-bundle 380.0", length=80.0, num_parallel=2.0, s_nom=3400.0)
    n.add("Line", "hv31", bus0="hv3", bus1="hv1",
          type="679-AL1/86-ST1A 380.0", length=200.0, num_parallel=1.0, s_nom=1700.0)
    n.add("Line", "mv12", bus0="mv1", bus1="mv2",
          type="490-AL1/64-ST1A 110.0", length=45.0, num_parallel=1.0, s_nom=300.0)

    n.add("Transformer", "tr1", bus0="hv1", bus1="mv1",
          type="160 MVA 380/110 kV", num_parallel=2.0, model="pi")
    n.add("Transformer", "tr2", bus0="hv2", bus1="mv2",
          type="160 MVA 380/110 kV", num_parallel=1.0, model="pi")

    n.add("Generator", "slack", bus="hv1", control="Slack", p_nom=900.0, marginal_cost=15.0)
    n.add("Generator", "peak", bus="hv3", p_nom=400.0, marginal_cost=75.0)
    n.add("Load", "dhv", bus="hv3", p_set=[300.0, 220.0, 380.0])
    n.add("Load", "dmv1", bus="mv1", p_set=[80.0, 60.0, 95.0])
    n.add("Load", "dmv2", bus="mv2", p_set=[55.0, 90.0, 40.0])
    return n


def lodf_mesh():
    """Two loops sharing an edge, so the outage factors are not all plus or minus one.

    `sclopf-triangle` cannot validate the LODF computation. A single cycle has
    only one alternative path, so the whole of an outaged branch's flow reappears
    on every survivor and every factor is exactly +-1 by topology, whatever the
    impedances. An implementation that simply returned +-1 would match it.

    Four buses and five lines give two loops sharing BC. Outaging a branch then
    leaves two distinct paths and the split depends on the impedances: PyPSA's own
    BODF has interior values of -0.333 and -0.500 here. Those are what a correct
    implementation has to reproduce.
    """
    n = pypsa.Network()
    n.set_snapshots(range(2))
    for bus in ("A", "B", "C", "D"):
        n.add("Bus", bus, v_nom=380.0)
    n.add("Line", "AB", bus0="A", bus1="B", x=0.10, r=0.0, s_nom=500.0)
    n.add("Line", "CA", bus0="C", bus1="A", x=0.15, r=0.0, s_nom=500.0)
    n.add("Line", "BD", bus0="B", bus1="D", x=0.20, r=0.0, s_nom=500.0)
    n.add("Line", "DC", bus0="D", bus1="C", x=0.25, r=0.0, s_nom=500.0)
    n.add("Line", "BC", bus0="B", bus1="C", x=0.30, r=0.0, s_nom=500.0)
    n.add("Generator", "g", bus="A", control="Slack", p_nom=600.0, marginal_cost=10.0)
    n.add("Load", "l", bus="C", p_set=[50.0, 80.0])
    return n


def sclopf_triangle():
    """A meshed triangle whose optimum is set by N-1 security, not by dispatch.

    Purpose-built. `ac-dc-dispatch` cannot be adapted: its capacity comes from
    the expansion optimum, so at six of ten snapshots every generator sits
    exactly at its limit with total output equal to total load. There is zero
    redispatch headroom, and no line rating makes an outage survivable -- SCLOPF
    on it is infeasible at any transmission scale. Its line 6 is also a bridge,
    so outaging it islands Frankfurt regardless.

    Three buses in a triangle, so any single line outage leaves the network
    connected and every contingency is well posed. Generation is deliberately
    ample (400/300/300 against a peak load of 230) so the binding constraint is
    security rather than capacity.

    The rating of 150 is chosen, not arbitrary. At 150 the plain LOPF costs 6900,
    exactly what it costs at 200 -- so the pre-contingency ratings are slack and
    the ONLY thing separating LOPF from SCLOPF is the contingency constraint.
    SCLOPF costs 14100. An implementation that dropped the N-1 rows entirely
    would return 6900, so the fixture cannot be satisfied without them.
    """
    n = pypsa.Network()
    n.set_snapshots(range(3))
    for bus in ("A", "B", "C"):
        n.add("Bus", bus, v_nom=380.0)
    n.add("Line", "AB", bus0="A", bus1="B", x=0.1, r=0.0, s_nom=150.0)
    n.add("Line", "BC", bus0="B", bus1="C", x=0.1, r=0.0, s_nom=150.0)
    n.add("Line", "AC", bus0="A", bus1="C", x=0.1, r=0.0, s_nom=150.0)
    n.add("Generator", "cheap", bus="A", p_nom=400.0, marginal_cost=10.0)
    n.add("Generator", "mid", bus="B", p_nom=300.0, marginal_cost=40.0)
    n.add("Generator", "dear", bus="C", p_nom=300.0, marginal_cost=90.0)
    n.add("Load", "lb", bus="B", p_set=[120.0, 140.0, 100.0])
    n.add("Load", "lc", bus="C", p_set=[110.0, 90.0, 130.0])
    return n


# Which branches each fixture treats as credible outages.
#
# Not every branch is a valid contingency: outaging a bridge disconnects the
# network and the post-contingency flow is undefined rather than large, which is
# why ac-dc-meshed's line 6 (Bremen-Frankfurt) can never appear here.
SCLOPF_OUTAGES = {
    "sclopf-triangle": ["AB", "BC", "AC"],
}


# PyPSA's `nominal_attrs`: the capacity attribute of each component whose size
# can be chosen rather than given.
NOMINAL_ATTRIBUTES = {
    "Generator": "p_nom",
    "Line": "s_nom",
    "Transformer": "s_nom",
    "Link": "p_nom",
    "StorageUnit": "p_nom",
    "Store": "e_nom",
}


# Fixtures whose optimal *dispatch* is not reproducible, with the evidence.
#
# Found by accident and then measured: adding this file's `standard-types`
# fixture rewrote 59,000 lines of the committed `scigrid-de` golden, having
# touched nothing that network uses. Six runs of `scigrid-de` in fresh processes
# land on exactly two answers. The objective is the same to 2e-8 relative
# (6684817.323607759 against ...738) and individual generators differ by up to
# 750 MW.
#
# The cause is upstream of the solver. `find_cycles` returns 364 cycles either
# way, but the basis alternates between 2372 and 2469 nonzeros -- and 2469-2372
# = 97 is exactly the per-snapshot difference in the LP HiGHS is handed
# (261298 against 263626 nonzeros over 24 snapshots). So PyPSA builds a
# different, equally valid Kirchhoff basis from one process to the next, and the
# degenerate optimum it reaches moves with it. Fixing PYTHONHASHSEED does not
# pin it.
#
# This is a property of the network, not of PyPSA's correctness: any KVL basis
# spanning the cycle space gives the same feasible set, and both answers cost
# the same. It does mean the dispatch frames here cannot gate an
# implementation. The objective and the marginal prices can.
# Fixtures whose `optimize` block records what PyPSA does rather than what a
# correct model should, with the evidence and the substitute.
NOT_A_TARGET = {
    "inactive": (
        "PyPSA's `cycle_matrix` keeps the inactive branch's loop while the Kirchhoff "
        "builder drops its flow term, so the row collapses to a voltage law for a loop "
        "that is not closed and pins hv31 to 27.6 MW on a 1700 MW branch. Deleting the "
        "line instead gives 19800 against this 68407.58. Its own linear flow excludes "
        "inactive branches from topology properly, so `lpf` and `optimize` here model "
        "different networks. Compare an implementation against `inactive-removed`; the "
        "`lpf` block on this fixture *is* a target."
    ),
}


DEGENERATE_DISPATCH = {
    "scigrid-de": (
        "one vertex of a degenerate optimal face, not a unique answer: PyPSA's own "
        "Kirchhoff cycle basis differs between runs (2372 or 2469 nonzeros over the "
        "same 364 cycles), and generators move by up to 750 MW between the two "
        "resulting optima while the objective agrees to 2e-8 relative. Gate on "
        "`objective` and `bus_marginal_price`; `generator_p`, `line_p0` and "
        "`transformer_p0` under `optimize` are one feasible optimum among many"
    ),
}


NETWORKS = {
    "ac-dc-meshed": pypsa.examples.ac_dc_meshed,
    "sclopf-triangle": sclopf_triangle,
    "lodf-mesh": lodf_mesh,
    "transformer-levels": transformer_levels,
    "transformer-taps": transformer_taps,
    "standard-types": standard_types_network,
    "storage-cycle": storage_cycle,
    "phase-shift": phase_shift,
    "inactive": inactive_components,
    "inactive-removed": inactive_removed,
    "ramp-limits": ramp_limits,
    "energy-budget": energy_budget,
    "investment-periods": investment_periods,
    "investment-periods-discounted": investment_periods_discounted,
    "investment-periods-expansion": investment_periods_expansion,
    "operational-limit": operational_limit,
    "tech-capacity-limit": tech_capacity_limit,
    "period-scoped-constraints": period_scoped_constraints,
    "link-delay": link_delay,
    "link-delay-wrap": link_delay_wrap,
    "store-bank": store_bank,
    "unit-commitment": unit_commitment,
    "ac-pf-pv": ac_pf_pv,
    "ac-dc-dispatch": ac_dc_dispatch,
    "ac-dc-co2": ac_dc_co2,
    "ac-dc-txvolume": ac_dc_txvolume,
    "ac-dc-txvolume-exact": ac_dc_txvolume_exact,
    "ac-dc-txcost": ac_dc_txcost,
    "storage-hvdc": pypsa.examples.storage_hvdc,
    "storage-cyclic-co2": storage_cyclic_co2,
    # The first realistic-scale network: 585 buses, 852 lines, 96 transformers,
    # 1423 generators over 24 snapshots. It is also the only bundled PyPSA
    # example that is not a capacity-expansion problem -- nothing is extendable,
    # nothing committable, no global constraints, no ramp limits -- so it is pure
    # dispatch, which is what this port already does.
    #
    # It is the transformer golden everything else was waiting on. All 96 sit at
    # tap_ratio = 1 and phase_shift = 0 with b = g = 0, so the nominal per-unit
    # conversion can be validated before the off-nominal and T-model paths are
    # attempted. And at 59,640 variables it is two orders of magnitude past
    # anything Prima has been exercised on outside Netlib.
    "scigrid-de": pypsa.examples.scigrid_de,
}


# Networks solved with `multi_investment_periods=True`.
#
# PyPSA keys two different things off two different signals, and conflating them
# is the mistake this set exists to stop. Whether an asset *exists* in a period
# comes from the snapshot index: `get_activity_mask` branches on
# `has_investment_periods`, so `build_year` and `lifetime` are enforced on any
# network whose snapshots are `(period, timestep)` pairs. Whether a period's
# `objective` discount and its `years` count are *applied* comes from
# `n._multi_invest`, which only `optimize(multi_investment_periods=True)` sets --
# `define_objective` and `define_primary_energy_limit` both guard on it.
#
# So a multi-period network solved without the flag masks assets by build year
# and then charges every period undiscounted. This script passed no flag at all
# until `investment-periods-discounted` was added, which means the
# `investment-periods` golden was generated in that mode. Its weights are both
# 1.0, so the recorded answer is the same either way and nothing was wrong with
# it -- but nothing validated the weighting either, and the port applies it
# unconditionally.
MULTI_INVEST = frozenset({
    "investment-periods",
    "investment-periods-discounted",
    "investment-periods-expansion",
    "period-scoped-constraints",
})


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
    # A multi-period network's snapshot index is a MultiIndex, so each label
    # arrives as a `(period, timestep)` tuple. Rendered as a list rather than
    # joined into a string: the two halves mean different things, and flattening
    # them would make a reader guess where the period ends.
    if isinstance(value, tuple):
        return [jsonable(v) for v in value]
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


class _Diverged(Exception):
    """Control flow only: the pf block has already been written."""


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

    # The binary formats, alongside the CSV directory. Both are HDF5 containers:
    # PyPSA's netCDF export is netCDF-4, and its .h5 export is a pandas HDFStore.
    # They are written from the same network as the CSVs, so a reader for either
    # must produce the model the CSV reader produces -- which is the comparison
    # the Scala side makes, and a much stronger one than checking a binary blob
    # against itself.
    binary = OUT / "binary"
    binary.mkdir(parents=True, exist_ok=True)
    print(f"  {name}: netCDF")
    n.export_to_netcdf(str(binary / f"{name}.nc"))
    # `tables` is genuinely optional: nothing reads these, and NOTES records why.
    # Hard-failing the whole pipeline for a file the port does not consume would
    # leave the CSV directory written and the manifest entry missing.
    try:
        print(f"  {name}: HDF5")
        n.export_to_hdf5(str(binary / f"{name}.h5"))
    except ImportError as exc:
        print(f"  {name}: HDF5 skipped ({exc})")

    summary = {
        "name": name,
        # Stringified, because a snapshot is a *label* and that is how the CSV
        # carries it. PyPSA does not require timestamps -- `ac-pf-pv` uses plain
        # integers -- and `jsonable` would render those as JSON numbers while
        # snapshots.csv writes "0" and "1", so the manifest would describe the
        # in-memory index rather than the file it is supposed to be checked
        # against.
        "snapshots": [str(s) for s in n.snapshots],
        # Multi-period networks are marked. This used to be a *skip* flag: their
        # snapshots are `(period, timestep)` pairs and the port's `Network` held
        # a flat list of labels, so the CSV reader took the `period` column and
        # produced duplicates, the writer could not reproduce the file, and the
        # netCDF export has no `snapshots_snapshot` dataset at all. Three suites
        # excluded them on this flag.
        #
        # `Network.snapshotPeriods` carries the other half now and all three
        # suites cover these fixtures, so nothing skips on it. Kept because it is
        # a true and useful description of the fixture, and because a reader of
        # the manifest should be able to tell which networks have periods without
        # parsing snapshots.csv.
        "multi_period": len(getattr(n, "investment_periods", [])) > 0,
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

    # The outage factors, for networks meshed enough that they are not all +-1.
    # Recorded because no other artefact pins them: a single-cycle fixture cannot
    # tell a correct LODF from one that returns +-1 everywhere, since that is what
    # its topology forces.
    try:
        n.determine_network_topology()
        n.calculate_dependent_values()
        factors = {}
        for label in n.sub_networks.index:
            sub = n.sub_networks.obj[label]
            branches = list(sub.branches_i())
            if not branches:
                continue
            sub.calculate_BODF()
            matrix = np.asarray(sub.BODF)
            factors[str(label)] = {
                "branches": [[str(c) for c in b] for b in branches],
                "bodf": [[jsonable(v) for v in row] for row in matrix],
            }
        if factors:
            results["bodf"] = factors
    except Exception as exc:  # noqa: BLE001 - recorded, not swallowed
        results["bodf"] = {"error": f"{type(exc).__name__}: {exc}"}

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
            "transformer_p0": frame_to_json(n.transformers_t.p0),
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

        # A diverged solve has no reference value in it. `scigrid-de` is the case:
        # PyPSA's own Newton-Raphson gives up after 100 iterations with an error
        # of 2e90 from a flat start, and the resulting voltages are of order 1e37.
        # Recording 3.2 MB of those would look like a golden and be worth nothing;
        # the convergence flags are the finding, so they are kept and the value
        # frames dropped.
        if not converged.to_numpy().any():
            results["pf"] = {
                "converged": frame_to_json(converged.astype(float)),
                "note": "PyPSA's own Newton-Raphson did not converge on this network from a "
                        "flat start, so there are no reference values to record",
            }
            print(f"  {name}: non-linear power flow did not converge (flags recorded, values not)")
            raise _Diverged

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
    except _Diverged:
        pass  # already recorded above
    except Exception as exc:  # noqa: BLE001 - recorded, not swallowed
        # ac-dc-meshed lands here: PyPSA raises AttributeError inside its
        # own sub-network handling. Recorded so the absence is visibly PyPSA's
        # rather than an oversight in this script.
        results["pf"] = {"error": f"{type(exc).__name__}: {exc}"}

    # Security-constrained optimisation, for the fixtures that declare
    # contingencies. Run on a fresh network so the plain solve below is not
    # seeded by it.
    if name in SCLOPF_OUTAGES:
        print(f"  {name}: security-constrained optimisation")
        try:
            sc = build()
            outages = SCLOPF_OUTAGES[name]
            status, condition = sc.optimize.optimize_security_constrained(
                branch_outages=outages
            )
            if status != "ok" or condition != "optimal":
                raise RuntimeError(f"solve did not converge: status={status} condition={condition}")
            results["sclopf"] = {
                "status": status,
                "condition": condition,
                "branch_outages": list(outages),
                "objective": jsonable(sc.objective),
                "generator_p": frame_to_json(sc.generators_t.p),
                "line_p0": frame_to_json(sc.lines_t.p0),
            }
        except Exception as exc:  # noqa: BLE001 - recorded, not swallowed
            results["sclopf"] = {"error": f"{type(exc).__name__}: {exc}"}

    print(f"  {name}: optimisation")
    try:
        m = build()  # fresh, so the LPF result does not seed the optimisation
        # `optimize` returns (status, condition) and does NOT raise on a
        # non-optimal termination, so an infeasible or truncated solve would
        # otherwise be written out as though it had converged -- and committed,
        # since regenerating after a version bump is the documented workflow.
        multi_invest = name in MULTI_INVEST
        status, condition = m.optimize(
            solver_name="highs", multi_investment_periods=multi_invest
        )
        if status != "ok" or condition != "optimal":
            raise RuntimeError(f"solve did not converge: status={status} condition={condition}")

        # `objective` is the model's objective value, not the system cost:
        # PyPSA defines total cost as objective + objective_constant, which is
        # why this reads negative on ac-dc-meshed. All three are captured so an
        # L2 comparison does not have to guess which one it is looking at.
        results["optimize"] = {
            "status": status,
            "condition": condition,
            # Recorded rather than left implicit, because it is a *solve option*
            # and not a property of the exported network: the `_multi_invest`
            # field in `network.csv` is whatever the last solve left behind, and
            # these networks are exported before they are solved, so it reads 0
            # there however this was run. See MULTI_INVEST.
            "multi_investment_periods": multi_invest,
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
            "transformer_p0": frame_to_json(m.transformers_t.p0),
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
            # Storage state, which is the only part of a dispatch that is not a
            # function of its own snapshot. Recorded as all five frames rather
            # than just the net `p`, because `p = p_dispatch - p_store` and an
            # implementation can reproduce the net injection at every snapshot
            # while getting the charge/discharge split -- and therefore the
            # efficiencies and the whole trajectory -- wrong.
            "storage_state_of_charge": frame_to_json(m.storage_units_t.state_of_charge),
            "storage_p_dispatch": frame_to_json(m.storage_units_t.p_dispatch),
            "storage_p_store": frame_to_json(m.storage_units_t.p_store),
            "storage_spill": frame_to_json(m.storage_units_t.spill),
            "storage_p": frame_to_json(m.storage_units_t.p),
            # A Store has one signed power variable and an energy level,
            # against a StorageUnit's four. `p > 0` discharges.
            "store_e": frame_to_json(m.stores_t.e),
            "store_p": frame_to_json(m.stores_t.p),
            # Chosen capacities, which are the *answer* to an expansion problem
            # rather than a by-product. Recorded per component as
            # `<attr>_opt`, and equal to the given `<attr>` wherever nothing is
            # extendable -- so the block is present for every fixture and an
            # implementation that quietly stopped expanding would show up here
            # rather than only in the objective.
            "nominal_opt": {
                component: {
                    str(name): jsonable(value)
                    for name, value in m.c[component].static[f"{attr}_opt"].items()
                }
                for component, attr in NOMINAL_ATTRIBUTES.items()
                if len(m.c[component].static) > 0
            },
        }
        if name in DEGENERATE_DISPATCH:
            results["optimize"]["dispatch_note"] = DEGENERATE_DISPATCH[name]
        if name in NOT_A_TARGET:
            results["optimize"]["not_a_target"] = NOT_A_TARGET[name]
    except Exception as exc:  # noqa: BLE001
        results["optimize"] = {"error": f"{type(exc).__name__}: {exc}"}

    (OUT / "results").mkdir(parents=True, exist_ok=True)
    (OUT / "results" / f"{name}.json").write_text(json.dumps(results, indent=1, sort_keys=True))
    return summary


def write_standard_types(out: Path) -> dict:
    """The standard type library, which no network export contains.

    `export_to_csv_folder` drops exactly the rows a fresh `Network()` was born
    with (`export_standard_types=False` is the default), on the correct grounds
    that they are library data rather than network data -- PyPSA's own reader
    repopulates them from the installed package. That leaves a reader outside
    Python with a `type` column naming rows it has never seen.

    So the library is committed here, and the port ships its own copy as a
    resource. Two copies is deliberate: the resource is what the library uses at
    runtime, with no goldens directory in sight, and this one is what a test
    holds it to. A PyPSA version bump that changes an impedance then fails that
    comparison instead of silently changing every answer on a typed network.
    """
    out.mkdir(parents=True, exist_ok=True)
    n = pypsa.Network()
    written = {}
    for list_name, frame in (
        ("line_types", n.line_types),
        ("transformer_types", n.transformer_types),
    ):
        path = out / f"{list_name}.csv"
        # `index_label="name"` because the index *is* the type name and PyPSA's
        # own data files head that column "name" -- writing a blank header would
        # give the CSV reader an unnamed key column.
        frame.to_csv(path, index_label="name")
        written[list_name] = {
            "count": int(len(frame)),
            "columns": ["name", *(str(c) for c in frame.columns)],
        }
        print(f"  wrote {len(frame)} {list_name}")
    return written


def write_unsupported(out: Path) -> dict:
    """CSV and netCDF exports of networks the port refuses, for the refusals to read.

    Not under `networks/`, and the distinction is the point. Everything there is
    a network `CsvReader` must load; these are networks it must **turn away**, so
    enumerating them alongside the others would fail every L1 suite that walks
    the manifest.

    They are generated rather than hand-written for the reason the goldens exist
    at all: a hand-written CSV only confirms agreement with your reading of the
    format. This directory exists because that failed. The first version of the
    piecewise refusal matched a `_piecewise` suffix taken from PyPSA's *Excel*
    sheet-name table, and the test hand-wrote a file with that name and passed --
    while `_CSVExporter.save_piecewise` writes ``f"{list_name}-{attr}-pw.csv"``
    with a two-row header, which the refusal did not match and the reader could
    not parse.
    """
    out.mkdir(parents=True, exist_ok=True)
    written = {}
    for name, build in UNSUPPORTED_NETWORKS.items():
        target = out / name
        if target.exists():
            shutil.rmtree(target)
        n = build()
        print(f"  {name}: CSV (unsupported)")
        n.export_to_csv_folder(str(target))
        # And netCDF, because this port has two readers and both feed the same
        # model. The refusal these fixtures exist for was first written against
        # one of them, which is the same mistake as writing it against a filename
        # read out of the source: a guard that covers the format you happened to
        # look at. The two spellings are not alike -- CSV gets
        # `<list>-<attr>-pw.csv`, netCDF gets a `<list>_pw_<attr>` variable with
        # its own breakpoint dimension -- so each reader needs its own, and each
        # needs a real file to be tested against.
        print(f"  {name}: netCDF (unsupported)")
        n.export_to_netcdf(str(out / f"{name}.nc"))
        # Recorded as two artefacts rather than one flat list. The netCDF file is
        # a *sibling* of the CSV directory, so appending its name to that
        # directory's listing asserted `unsupported/<name>/<name>.nc`, a path that
        # does not exist -- and left the list unsorted, unlike every other file
        # list in the manifest.
        written[name] = {
            "csv": sorted(f.name for f in target.iterdir()),
            "netcdf": f"{name}.nc",
        }
    written.update(write_synthetic_unsupported(out))
    return written


def write_synthetic_unsupported(out: Path) -> dict:
    """Shapes no exporter produces, for the refusals that must survive them.

    Separate from `write_malformed` on purpose, and the reason is the distinction
    `UnsupportedNetworkFile` exists to draw. `binary/malformed` is what three
    places -- that type's scaladoc, NOTES, and `write_malformed`'s own docstring
    -- point at to *define* "broken file", and everything in it raises
    `MalformedNetwork`. A file that is perfectly well formed and merely unmodelled
    raises the other type, so putting one there would make the directory a
    counterexample to the boundary it anchors.

    Separate from the exports above too, and labelled `synthetic` in the manifest,
    because it is not evidence about what PyPSA writes. It is a hypothesis about
    what PyPSA might one day write, and the goldens' whole argument is that those
    two are not interchangeable.
    """
    import xarray as xr

    # A piecewise curve carrying only its index variables.
    #
    # `save_piecewise` always writes the value variable beside its indices, so
    # nothing PyPSA produces reaches this branch -- and `NetCdfReader` detects on
    # the whole `<list>_pw_<attr>` family rather than on the value variable alone
    # precisely so that a renamed value variable, a partial export or a future
    # spelling still refuses instead of falling through to the static-column
    # length check. The alternative to a synthetic fixture is an unexercised
    # guard.
    xr.Dataset(
        {
            "snapshots_snapshot": ("snapshots", np.array([0, 1])),
            "generators_pw_marginal_cost_i": (
                "breakpoint",
                np.array(["curved"], dtype=object),
            ),
        },
        coords={"snapshots": np.array([0, 1]), "breakpoint": np.array([0])},
    ).to_netcdf(out / "piecewise-index-only.nc")

    return {
        "piecewise-index-only": {
            "netcdf": "piecewise-index-only.nc",
            "synthetic": "no exporter writes this; it pins the detection filter, not the format",
        }
    }


UNSUPPORTED_NETWORKS = {
    "piecewise-cost": piecewise_cost,
}

def write_malformed(out: Path) -> None:
    """Deliberately broken netCDF files, for the reader's error paths.

    Generated and committed rather than built inside the Scala suite. A test that
    shells out to this virtual environment would skip in CI, where the goldens
    are present and the venv is not -- and a test that never runs in CI is worth
    very little.

    Each file is a real netCDF-4 written by xarray, broken in exactly one way, so
    the reader is exercised against the format it actually meets.
    """
    import xarray as xr

    out.mkdir(parents=True, exist_ok=True)

    # No snapshot index at all: structurally valid, not a PyPSA network.
    xr.Dataset(
        {"buses_v_nom": ("buses_i", np.array([380.0, 380.0]))},
        coords={"buses_i": np.array(["A", "B"], dtype=object)},
    ).to_netcdf(out / "no-snapshots.nc")

    # A static column shorter than the entity index it belongs to.
    xr.Dataset(
        {
            "snapshots_snapshot": ("snapshots", np.array([0, 1])),
            "buses_v_nom": ("buses_short_i", np.array([380.0])),
        },
        coords={
            "snapshots": np.array([0, 1]),
            "buses_i": np.array(["A", "B"], dtype=object),
            "buses_short_i": np.array(["A"], dtype=object),
        },
    ).to_netcdf(out / "short-column.nc")

    # A time axis whose units CF does not define.
    times = xr.DataArray(np.array([0, 1]), dims="snapshots")
    times.attrs["units"] = "fortnights since 2015-01-01 00:00:00"
    xr.Dataset(
        {"snapshots_snapshot": times},
        coords={"snapshots": np.array([0, 1])},
    ).to_netcdf(out / "bad-time-unit.nc")


    print(f"  wrote malformed fixtures to {out}")


def write_schema_only(target: Path) -> int:
    """Just `schema.json`, for checking a PyPSA this repository is not pinned to.

    The drift workflow installs the *latest* PyPSA and needs the component
    registry out of it, and nothing else: the networks take minutes and several
    of them call a solver, none of which says anything about whether upstream
    moved an attribute. It also must not touch `goldens/`, because a schema from
    an unpinned version is not a golden -- it is the thing a golden gets compared
    against.

    Writing the same shape as `main` does, so `schema_drift.py` and the Scala
    sweep read one format regardless of which produced the file.
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(capture_reference_schema(), indent=1, sort_keys=True))
    print(f"wrote {target}")
    print(f"pypsa {pypsa.__version__}, pandas {pd.__version__}, python {sys.version.split()[0]}")
    return 0


USAGE = "usage: generate_goldens.py [--schema-only PATH | --into PATH]"


def main() -> int:
    # Deliberately hand-rolled rather than argparse: there are exactly two flags,
    # and both exist to make the file usable by the drift workflow without giving
    # anyone a way to half-regenerate the goldens.
    #
    # PATH is required on both rather than defaulting into `goldens/`. A default
    # there would be exactly the half-regeneration this comment claims to
    # prevent: `schema.json` rewritten from whatever PyPSA the invoking venv
    # happens to hold while `manifest.json` still records the pinned one, so the
    # schema and the recorded pin disagree and every schema-driven suite reads an
    # unpinned schema believing it is the golden. Regenerating the goldens is the
    # no-flag path, which rewrites the manifest alongside them.
    #
    # `--into` is a *whole* generation with the destination moved, not a partial
    # one, which is why it is safe: everything that normally lands in `goldens/`
    # lands together in PATH, manifest included, and `goldens/` is untouched. The
    # results drift check needs the solved answers from a PyPSA nobody has
    # pinned, and there is no way to get those without building the networks.
    global OUT
    argv = sys.argv[1:]
    if argv[:1] == ["--schema-only"]:
        if len(argv) == 2:
            return write_schema_only(Path(argv[1]))
        print(USAGE, file=sys.stderr)
        return 2
    if argv[:1] == ["--into"]:
        if len(argv) == 2:
            OUT = Path(argv[1]).resolve()
        else:
            print(USAGE, file=sys.stderr)
            return 2
    elif argv:
        print(USAGE, file=sys.stderr)
        return 2

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

    write_malformed(OUT / "binary" / "malformed")
    manifest["unsupported"] = write_unsupported(OUT / "unsupported")
    manifest["standard_types"] = write_standard_types(OUT / "standard_types")

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
