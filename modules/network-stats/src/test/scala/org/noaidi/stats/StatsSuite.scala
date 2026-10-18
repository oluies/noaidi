package org.noaidi.stats

import java.nio.file.Files
import org.noaidi.lopf.{CsvFixtures, Lopf, LopfResult}
import org.noaidi.prima.{PdhgParams, SolveStatus}

/** Every implemented metric against PyPSA's own `statistics`, never against arithmetic.
  *
  * The discipline is this repository's existing one, and this module needed it twice before
  * a single assertion ran:
  *
  *   - `supply` was written against `snapshot_weightings.stores`, with the design note
  *     arguing for it explicitly. PyPSA delegates `supply` to `energy_balance` and weights
  *     with `generators`. `operational-limit` reports 120 for a 40 MW dispatch at one
  *     snapshot, which is 40 x 3, and no amount of re-reading the formula would have said so.
  *   - `opex` was written against the static `marginal_cost` column. `operational-limit`'s
  *     run-of-river bids 1/2/3/4 across four snapshots and PyPSA charges 320; the static
  *     column holds the schema default and charges 0.
  *
  * Both were caught by running the metric against upstream, and neither by review.
  *
  * ==And one finding that was not about this module at all==
  *
  * Iterating the manifest put `opex` over `ac-pf-pv`, whose LOPF result nothing had ever
  * compared -- `NewtonRaphsonSuite` and `TopologySuite` are its only other readers and
  * neither solves an LP. It came out at 2,650 against PyPSA's 7,650, because the network
  * ships `generators-p_set.csv` and the LOPF read `p_set` for `Load` only. That gap was on
  * the list in NOTES and had no code site; `Lopf.rejectDispatchSetPoints` is now the code
  * site, `GapRefusalSuite` holds the cases, and this suite skips the network as refused.
  *
  * Worth stating because it is an argument for the shape of this suite rather than for its
  * contents: a fixture only guards what some suite reads, and breadth over the manifest is
  * what reads the ones nobody aimed at.
  */
class StatsSuite extends munit.FunSuite, CsvFixtures:

  override protected def tempPrefix: String = "noaidi-stats-"

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)

  private def results(name: String): ujson.Value =
    ujson.read(Files.readString(goldens.resolve("results").resolve(s"$name.json")))

  /** Every golden network, from the manifest rather than a list here. */
  private lazy val networks: List[String] =
    ujson
      .read(Files.readString(goldens.resolve("manifest.json")))("networks")
      .obj.keys.toList.sorted

  /** Every component PyPSA reports a metric for, all of which this module now covers.
    *
    * `uncovered` is gone with the gap it described. `supply` summed one-port injections only
    * for two releases, and this suite asserted that the set PyPSA reported and the module did
    * not was EXACTLY the branch kinds -- so that the day branches arrived, the assertion
    * would fail rather than the agreement widening in silence. That is what happened.
    */
  private val covered =
    Set("Generator", "StorageUnit", "Store", "Load", "Line", "Link", "Transformer")

  /** The component part of a `"Component|group"` or `"Component|group|period"` key. */
  private def component(key: String): String = key.takeWhile(_ != '|')

  /** Every grouping the generator records, read from the goldens rather than listed here.
    *
    * Taken off a network that has them all, so adding one to `STATISTICS_GROUPINGS` puts it
    * under test without this file being edited -- the same argument as reading the network
    * list from the manifest.
    */
  private lazy val groupings: List[String] =
    results("statistics-dimensions")("statistics").obj.keys
      .map(_.split('|').last).toList.distinct.sorted

  /** Every metric the generator records, on the same argument as [[groupings]]. */
  private lazy val recordedMetrics: List[String] =
    results("statistics-dimensions")("statistics").obj.keys
      .map(_.split('|').head).toList.distinct.sorted

  /** The implementation behind each recorded metric name.
    *
    * Keyed by the name `generate_goldens.py` writes, which is PyPSA's own method name, so a
    * metric added to `STATISTICS_METRICS` and not here fails the case below rather than
    * silently going uncompared. That is the failure mode this map exists for: the previous
    * version of the suite named `supply` and `opex` inline, so three new metrics would have
    * been recorded in the goldens, read by nothing, and reported as a pass.
    */
  private val metrics: Map[String, (Stats, String) => Map[String, Double]] = Map(
    "supply"          -> ((s, g) => s.supply(g)),
    "opex"            -> ((s, g) => s.opex(g)),
    "capex"           -> ((s, g) => s.capex(g)),
    "installed_capex" -> ((s, g) => s.installedCapex(g)),
    "expanded_capex"  -> ((s, g) => s.expandedCapex(g)),
    "optimal_capacity"   -> ((s, g) => s.optimalCapacity(g)),
    "installed_capacity" -> ((s, g) => s.installedCapacity(g)),
    "expanded_capacity"  -> ((s, g) => s.expandedCapacity(g)),
    "overnight_cost"     -> ((s, g) => s.overnightCost(g)),
    "fom"                -> ((s, g) => s.fom(g)),
    "system_cost"        -> ((s, g) => s.systemCost(g)),
    "curtailment"        -> ((s, g) => s.curtailment(g)),
    "transmission"       -> ((s, g) => s.transmission(g)),
    "energy_balance"     -> ((s, g) => s.energyBalance(g)),
    "withdrawal"         -> ((s, g) => s.withdrawal(g)),
    "capacity_factor"    -> ((s, g) => s.capacityFactor(g)),
    "revenue"            -> ((s, g) => s.revenue(g)),
    "market_value"       -> ((s, g) => s.marketValue(g)),
    "prices"             -> ((s, g) => s.prices(g)),
  )

  /** Metrics the goldens record and this module does not answer yet, named rather than absent.
    *
    * '''Empty, and kept.''' `STATISTICS_METRICS` in the generator is PyPSA's full nineteen and
    * [[metrics]] now implements all nineteen, so there is nothing to name today. The mechanism
    * stays because the next upstream release is what it is for: a metric added to
    * `statistics` lands in every golden on the first regeneration, and the case below then
    * fails unless it is either implemented or listed here. An empty set is the state that
    * mechanism is supposed to be in.
    *
    * It carried six names for two releases and the reason each one was there is worth keeping,
    * because it is what the two batches cost: `energy_balance`, `withdrawal`,
    * `capacity_factor`, `revenue` and `market_value` all report `Load`, which `quantitiesOf`
    * gave no rows in `fact`; `revenue`, `market_value` and `prices` need the bus marginal
    * price, which was in no table; and `prices` is keyed by BUS rather than by component.
    *
    * ==The floor has narrowed twice and will not narrow again==
    *
    * The group floor was a single absolute value in the units of an energy or a cost, and two
    * of the metrics that landed are neither: `capacity_factor` is '''dimensionless''' and
    * `market_value` is a price. The smallest compared group went from `store-per-period`'s 0.6
    * at 6,000x the floor to `phase-shift`'s 0.0160985 at 161x to `ac-dc-dispatch`'s 0.0003846
    * at 3.8x -- and at that point the single floor gave way to [[groupFloor]], which is per
    * metric and zero for the fourteen that produce no artefact.
    */
  private val pending: Set[String] = Set.empty

  /** Metrics keyed by BUS rather than by component, which the comparison below cannot share.
    *
    * `prices` alone. Every other metric returns a frame keyed by `(component, group)` --
    * upstream does not aggregate across components -- so the comparison splits a key on `|`,
    * reads the component kind off the front and skips by kind. `prices` is keyed by the bus
    * grouper alone, so there is no kind to read and `covered` has nothing to say about it.
    *
    * It gets its own block at the end of the same case rather than a case of its own, which
    * keeps it inside the one solve: 41 more networks solved twice is minutes, and `scigrid-de`
    * alone is 60,000 variables.
    */
  private val busKeyed: Set[String] = Set("prices")

  /** The metrics this suite actually compares: everything recorded, less [[pending]].
    *
    * Every case here reads this rather than [[recordedMetrics]], including the ones that only
    * read the goldens. A floor or a determinacy pin measured over a metric nobody compares is
    * a statement about the reference rather than about this module, and it would move with
    * each batch for no reason a reader could act on.
    */
  private lazy val comparedMetrics: List[String] =
    recordedMetrics.filterNot(m => pending(m) || busKeyed(m))

  /** One golden number, keeping a tagged NaN as a NaN rather than throwing on it.
    *
    * `generate_goldens.py` writes `{"$nan": true}` and `{"$inf": "+"}` because JSON has no
    * literal for either, and `market_value` produces both -- it divides a mean revenue by a
    * mean operation, and an asset that never operated has a zero denominator. 1,112 NaNs
    * across the goldens, plus one `+inf` on `ac-dc-txvolume`. `ujson`'s `.num` raises
    * `InvalidData` on the tag, which is a failure that names the encoding rather than the
    * metric.
    */
  private def number(v: ujson.Value): Double =
    v.objOpt match
      case Some(tag) if tag.contains("$nan") => Double.NaN
      case Some(tag) if tag.contains("$inf") =>
        if tag("$inf").str == "-" then Double.NegativeInfinity else Double.PositiveInfinity
      case _ => v.num

  /** The floor below which a group is this solver's zero rather than a group, PER METRIC.
    *
    * One value served all nineteen for three releases and it was in the wrong shape for most
    * of them: the metrics differ in scale by five orders of magnitude, so a floor placed to
    * suppress a cancellation residue in `revenue` was also hiding real groups in
    * `market_value`. Both walls are measured now, per metric, and the table below is the whole
    * calibration.
    *
    * ==Only five metrics can turn a solver epsilon into a group, and two mechanisms do it==
    *
    *   - '''A clip.''' `supply`, `withdrawal` and `curtailment` keep one side of a quantity, so
    *     a port whose injection is `-1e-11` here and exactly `0` upstream becomes a group here
    *     and none there. The boundary, not the magnitude, is what makes it.
    *   - '''A cancellation.''' `energy_balance` and `revenue` sum a signed quantity over both
    *     ports, so a branch's two ends nearly annihilate and what is left is the epsilon times
    *     the scale of the terms -- `revenue`'s residue reaches 4e-5 because the terms carry a
    *     price.
    *   - '''A dual epsilon, amplified.''' `prices` is a load-weighted average of a DUAL, and
    *     the gate that admits a network at all -- [[dualAgrees]] -- tolerates
    *     `1e-4 * max(1, |want|)`, so 1e-4 ABSOLUTE at a cheap bus. A bus priced at exactly
    *     zero upstream and 5e-5 here passes that gate, rounds to a group here and to none
    *     upstream, and lands in the key-set comparison. No artefact exists today; this one is
    *     a reachable trap rather than a measured residue, and it is floored on that basis.
    *
    * Every other metric reads a static column, sums at one port, or takes an absolute value,
    * and '''produces no artefact at all''' -- measured by setting every floor to zero and
    * reading what fails. So they get no floor, and every group they report is checked for
    * presence rather than only for value.
    *
    * `prices` is floored on a mechanism rather than on a measurement, which is the one entry
    * here that is not evidence-led, and it is worth saying why that is the safe direction:
    * neither case below can see it. `prices` is keyed by BUS, so it is excluded from
    * `allGoldenGroups` and from the census and margin assertions alike -- an unfloored
    * `prices` would read as a side effect of the name's absence from this set rather than as
    * a decision. Its smallest real group anywhere in the goldens is 0.10223, so the floor
    * costs nothing: a thousand times of room and a trap closed.
    *
    * `market_value` is the one that cancels and still gets no floor, because its `0 / 0` groups
    * are removed by the non-finite skip instead and its real groups reach 4.96e-06. A floor
    * placed for `revenue` would hide them for nothing.
    *
    * ==What it buys, and the one place it cannot separate anything==
    *
    * {{{
    * metric            artefact   smallest real group      floor
    * prices                none   0.10223                   1e-4   (mechanism, not residue)
    * supply               1e-05   1e-05  (scigrid-de)       1e-4
    * withdrawal           1e-05   0.87578                   1e-4
    * energy_balance       1e-05   1e-05  (scigrid-de)       1e-4
    * revenue              4e-05   1e-05  (scigrid-de)       1e-4
    * curtailment           none   0.34042                   1e-4
    * market_value          none   4.95805e-06               0
    * capacity_factor       none   2.79015e-06               0
    * optimal_capacity      none   0.00028                   0
    * ... and ten more        none   0.6 to 13764.8           0
    * }}}
    *
    * The honest part is the second column. For `supply`, `energy_balance` and `revenue` the
    * smallest real group upstream reports is the SAME magnitude as the artefact, so no floor
    * separates them -- and the only reason 1e-4 works is that those groups are all in
    * `scigrid-de`, the one network this suite skips for a degenerate dispatch. That is a
    * weaker claim than a margin and it is the true one; the case below pins which groups it
    * costs.
    *
    * The gain is on the other side: `market_value` was the tightest case at 3.8x and now has no
    * floor at all, so its real groups down to 4.96e-06 are checked for presence rather than
    * only for value. Same for `capacity_factor` at 2.79e-06 and for the two capacity metrics
    * at 0.00028.
    */
  private val floored: Set[String] =
    Set("supply", "withdrawal", "curtailment", "energy_balance", "revenue", "prices")

  private def groupFloor(metric: String): Double = if floored.contains(metric) then 1e-4 else 0.0

  /** Groups big enough to be a group rather than a solver's zero.
    *
    * `Stats` reproduces PyPSA's `drop_zero` exactly -- round to five decimals, drop exact
    * zero -- because it is upstream's rule and it is what makes the group SET comparable at
    * all. What it is not is reproducible across solvers at that scale. On
    * `ac-dc-txvolume-exact` HiGHS leaves `Manchester Gas` and `Norway Gas` at exactly 0.0
    * while Prima -- a first-order method at 1e-9 on a 60,000-variable LP -- leaves them at
    * `1.0e-5`, which rounds to `0.00001` and survives the rule. Two extra groups, both a
    * generator PyPSA agrees does not run.
    *
    * Applied to '''both''' sides, so it can only ever remove a group the two sides disagree
    * about at the solver's own noise level.
    *
    * ==It cannot distinguish a real `1e-5` group from an artefact, and does not pretend to==
    *
    * Both are `1e-5`. Nothing absolute separates them, and nothing relative does either --
    * see [[groupFloor]]. So what makes this safe is not the threshold but the two cases
    * below, which pin what it actually removes: thirteen groups across every golden, all
    * thirteen in `scigrid-de`, which this suite skips for a degenerate dispatch anyway, and
    * every compared group clearing its own metric's floor by more than 3x. Asserted rather
    * than asserted-to-be-obvious, so a future golden with a genuinely tiny group fails a test
    * instead of being quietly dropped.
    *
    * This is a tolerance on the SOLVER, not on the metric: every surviving group's value is
    * still compared at `1e-4` relative, and the `drop_zero` defects this assertion has
    * already caught -- a missing `drop_zero` entirely, and `store-bank`'s carrier -- were
    * group sets differing by whole groups rather than by a rounding artefact.
    */
  private def significant(metric: String, groups: Map[String, Double]): Map[String, Double] =
    groups.filter((_, v) => math.abs(v) > groupFloor(metric))

  /** A golden metric for one grouping, or `None` where PyPSA itself did not produce one.
    *
    * The only thing filtered out now is an `error` block, and in practice that is `country`
    * on all but two networks: it is the one dimension PyPSA has no declared attribute for, so
    * its grouper raises `AttributeError` rather than grouping under a blank. `Stats` refuses
    * the same networks, which is asserted separately below rather than inferred from a skip.
    *
    * Multi-period networks are no longer skipped. They used to be, and visibly: upstream
    * returns a frame with a column per period, so its keys carry a third part and its numbers
    * are per period while the queries summed over the horizon. The queries group by period
    * now, so the keys line up and eleven networks came back into the comparison.
    */
  private def expected(name: String, metric: String, groupBy: String): Option[Map[String, Double]] =
    val block = results(name).obj.get("statistics").flatMap(_.obj.get(s"$metric|$groupBy"))
    block.flatMap { frame =>
      if frame.obj.contains("error") then None
      else Some(frame.obj.map((k, v) => k -> number(v)).toMap)
    }

  /** The groups of one metric this network's optimum does not pin, measured by the generator.
    *
    * `generate_goldens.py` solves every network twice -- HiGHS simplex, then interior point
    * with crossover and presolve off -- and compares each metric GROUP BY GROUP, recording
    * only the keys that moved. A key in this set is one vertex of an optimal face, so
    * comparing it compares tie-breaks between two solvers rather than two implementations of
    * a metric.
    *
    * ==Per group, because the coarser rules all throw away something real==
    *
    * `DEGENERATE_DISPATCH` is per NETWORK, and that is what `scigrid-de` was skipped under.
    * Four more networks turned out to be degenerate the moment branch groups began to be
    * compared, and skipping those networks whole would have cost every generator comparison
    * they carry -- which is most of what they are for. Measured, the undetermined groups on
    * `ac-dc-co2`, `ac-dc-txcost`, `storage-hvdc` and `storage-cyclic-co2` are '''only''' the
    * `Line` and `Link` ones; every one-port group on all four is a point.
    *
    * So this skips keys rather than metrics or networks, and the branch coverage that needed
    * it is in the same change -- a marker with nothing to mark would have been untestable.
    */
  private def undetermined(name: String, metric: String, groupBy: String): Set[String] =
    results(name).obj
      .get("determinacy")
      .flatMap(_.obj.get("statistics"))
      .flatMap(_.obj.get(s"$metric|$groupBy"))
      .flatMap(_.obj.get("undetermined"))
      .map(_.obj.keys.toSet)
      .getOrElse(Set.empty)

  /** Whether this network's buses carry no `country` column, read off PyPSA's own complaint.
    *
    * The grouper raises `AttributeError: 'DataFrame' object has no attribute 'country'` on
    * such a network -- 37 of the 39 -- but only for a metric that has rows to group, so the
    * same network records that error for one metric and an EMPTY FRAME for another. Reading
    * "the column is missing" off any one of its error blocks is what lets the comparison treat
    * both as the declination they are.
    *
    * The `AttributeError` is matched specifically, and the first version was not: it took any
    * `error` on a `|country` block as evidence, and `overnight_cost` records a `ValueError`
    * about `discount_rate` on the six ac-dc networks -- which DO have the column. Those six
    * then expected a refusal for every metric whose country frame was empty, and the suite
    * failed on `fom|country` answering correctly.
    */
  private def countryRaises(name: String): Boolean =
    results(name).obj
      .get("statistics")
      .exists(_.obj.exists { (block, frame) =>
        block.endsWith("|country") &&
          frame.obj.get("error")
            .exists(e => e.str.startsWith("AttributeError") && e.str.contains("country"))
      })

  /** Metrics built on the bus marginal price, which is a DUAL variable.
    *
    * `revenue` prices an injection with it, `market_value` divides that by what the asset
    * operated, and `prices` reports it on its own. Every other metric here reads the primal.
    *
    * The distinction is not bookkeeping. A dual face is far more common than a primal one:
    * any bus whose marginal unit sits at a bound has an INTERVAL of valid prices, and two
    * solvers pick different points in it while agreeing on the objective, the dispatch and
    * every primal metric. `prima-core/NOTES.md` records this for the LOPF -- "nodal prices
    * agree wherever the dual is unique and are a different point of the same optimal dual
    * face elsewhere" -- and these three are the first metrics to inherit it.
    *
    * So they are compared only where [[dualAgrees]] says the prices themselves agree, which
    * is measured per network rather than assumed or listed.
    */
  private val dualMetrics: Set[String] = Set("revenue", "market_value", "prices")

  /** The networks whose nodal prices are a different vertex of the same optimal dual face.
    *
    * Pinned, and [[dualAgrees]] is asserted against it on EVERY network -- which is what makes
    * the gate load-bearing in both directions. Forcing the measurement false fails on the
    * thirty-two networks whose prices agree; forcing it true fails on these seven.
    *
    * That matters more than it sounds. The first guard was an assertion that a network whose
    * duals agreed had compared at least one `prices` grouping -- and it sat BEHIND the same
    * flag, so forcing the flag false removed the three dual metrics from every comparison and
    * the assertion that would have noticed. Measured: that mutant survived the whole suite.
    *
    * The measurement is still what gates the comparison; this list only has to agree with it.
    * A port that fixed one of these keeps the metric skipped until the name is removed, and
    * the assertion is what says so rather than a silent widening.
    *
    * ==The evidence, as the worst disagreement per network==
    *
    * {{{
    * ramp-limits         22.31   a@4          PyPSA 40.0   here 62.31
    * link-delay-wrap     15.00   b@3          PyPSA 15.0   here  0.0
    * link-delay           5.00   a@0          PyPSA  5.0   here  0.0
    * ac-dc-dispatch       3.87   Bremen@8     PyPSA  0.11  here  3.98
    * ac-dc-txvolume       0.145  Norwich DC@8
    * ac-dc-co2            0.114  Norway@5     PyPSA  0.11  here  0.22
    * annuitised-capital   0.0086 a@1          PyPSA 11.2114 here 11.2029
    * }}}
    *
    * ==Every one of them is a face, and that is measured rather than assumed==
    *
    * The two zeros read like a missing dual when this list was first written -- a price of
    * exactly 0 where upstream reports 5 and 15, on the two delayed-link networks -- and that
    * reading was wrong. What makes them faces is two facts, both checked by the case below:
    *
    *   - '''Strong duality holds on all seven.''' The dual gap is between 1.3e-13 and 1.8e-9
    *     RELATIVE, and each objective matches PyPSA's to five decimals. Two objectives
    *     agreeing is what makes a dual optimal rather than merely feasible, so this port's
    *     prices are an optimal dual solution and not an approximate one.
    *   - '''Every price that is forced agrees with PyPSA exactly.''' Of the differing cells,
    *     none is at a bus with a generator strictly inside its bounds -- except on
    *     `ac-dc-dispatch` and `ramp-limits`, where a CO2 multiplier of -2178.29 and the ramp
    *     multipliers enter the reduced cost and an interior unit therefore does not pin its
    *     bus price to its marginal cost. On `link-delay` and `link-delay-wrap` there is no
    *     global constraint and no ramp limit, so `price <= marginal_cost` IS the whole
    *     condition at an idle generator, and it holds at all fourteen differing cells.
    *
    * So the zeros are the bottom of an interval and PyPSA's 5 and 15 are the top. These two
    * networks are about as degenerate as an LP gets: the load exists at one snapshot, the
    * delay puts it out of the cheap generator's reach, and every column except one sits at
    * zero -- so most prices are bounded above and determined by nothing.
    *
    * `LopfSuite` already makes this argument for `ac-dc-dispatch`, in almost these words. The
    * case below is the same argument over the other six, driven by this list, because the
    * skip is only honest if the dual it skips comparing is optimal.
    */
  private val dualFace: Set[String] =
    Set("ac-dc-co2", "ac-dc-dispatch", "ac-dc-txvolume", "annuitised-capital", "link-delay",
        "link-delay-wrap", "ramp-limits")

  /** Whether this network's nodal prices agree with PyPSA's, cell by cell.
    *
    * Measured against `optimize.bus_marginal_price` at the same `1e-4` relative tolerance the
    * metrics are compared at, so "the dual agrees" means exactly "a metric built on it can be
    * compared". Measured rather than pinned, because the alternative -- a list of networks
    * someone maintains -- would go stale in the direction that looks like a pass: a port that
    * started getting a price wrong would keep comparing the metric over it.
    *
    * The case below pins which networks it rejects, so the measurement cannot quietly start
    * rejecting everything either.
    *
    * ==Measured, and the spread is three orders wide==
    *
    * Across the 39 goldens the worst disagreement is below 1e-6 on 31 of them -- solver
    * tolerance, nothing more -- and on the rest it is a different vertex of the dual: 22.3 on
    * `ramp-limits`, 15.0 on `link-delay-wrap` where PyPSA prices a bus at 15 and this port at
    * 0, 5.0 on `link-delay`, 3.87 on `ac-dc-dispatch`. Those are not near misses and no
    * tolerance makes them one.
    */
  private def dualAgrees(name: String, result: LopfResult): Boolean =
    val frame = results(name)("optimize").obj.get("bus_marginal_price")
    frame.forall { prices =>
      val buses = prices("columns").arr.map(_.str).toVector
      prices("values").arr.zipWithIndex.forall { (row, t) =>
        row.arr.zipWithIndex.forall { (want, bus) =>
          val wanted = want.numOpt.getOrElse(0.0)
          math.abs(result.marginalPrice(buses(bus), t) - wanted) <=
            1e-4 * math.max(1.0, math.abs(wanted))
        }
      }
    }

  /** Whether `prices` is comparable on this network at all, which its WEIGHTS decide too.
    *
    * `prices` is a load-weighted average, so it reads the dispatch as well as the dual: its
    * weight is `withdrawal(groupby="bus")`. A network whose withdrawal is one vertex of a
    * primal face therefore has a `prices` that moves even where every individual price holds
    * still -- measured on `storage-cyclic-co2`, whose per-cell prices agree to 2e-6 and whose
    * `prices|bus_carrier` comes out 0.045 away from upstream's 128.008, because its storage
    * units trade 1.67 MWh of charging among themselves.
    *
    * So the whole metric is skipped on a network where either half is loose, rather than a
    * grouping or a key: an aggregated group mixes buses, and nothing in the golden says which
    * of them contributed the movement.
    */
  private def pricesComparable(name: String): Boolean =
    groupings.forall(g => undetermined(name, "prices", g).isEmpty)

  /** Metrics whose group can be a near-total cancellation of much larger terms.
    *
    * These three sum a '''signed''' quantity over '''both''' ports, so a branch contributes
    * `-p0` at one end and `+p0 * efficiency` at the other and the group is the difference of
    * two numbers orders of magnitude bigger than itself. Measured on `ac-dc-txvolume-exact`:
    * `revenue|bus_carrier`'s `Link|DC` is -82.36 as the net of two terms near 1,136,000, so
    * agreement to 8e-9 on the terms is 1.1e-4 on the net -- just past the tolerance everything
    * here is compared at.
    *
    * No other metric can do it. `supply`, `withdrawal` and `curtailment` clip to one sign, the
    * capital metrics and `transmission` sum at one port, and `capacity_factor` takes an
    * absolute value. So the set is structural rather than "the ones that happened to fail":
    * `energy_balance` is in it on the same grounds as the other two and costs 41 comparisons
    * to include, which is the price of saying what is true instead of what is minimal.
    *
    * ==What they inherit, and from themselves==
    *
    * A metric in here reads its OWN determinacy verdict at every grouping rather than only at
    * the one being compared, because a coarse group mixes the entities whose finer groups
    * moved and the probe sees the movement where the group is finest.
    *
    * That replaced three patches, each of which borrowed a verdict from a DIFFERENT metric --
    * a quotient from its dispatch, then from six dispatch metrics because a branch nets to
    * nothing in `energy_balance`, then across every grouping as well. All three existed
    * because `DETERMINACY_ABS` was 1e-2 and absolute, so the probe could not see a face in a
    * dimensionless or a cancelling group at all. The generator's statistics probe now uses
    * this suite's own tolerance and sees them directly, so borrowing from another metric is
    * gone and what is left is this: the same metric, every grouping, for the three that can
    * cancel.
    */
  private val cancelling: Set[String] = Set("energy_balance", "revenue", "market_value")

  /** Whether this golden is worth comparing at all, with the reason read from the golden. */
  private def comparable(name: String): Option[String] =
    val optimize = results(name)("optimize")
    if optimize.obj.contains("dispatch_note") then
      Some(s"degenerate dispatch: ${optimize.obj("dispatch_note")}")
    else if optimize.obj.contains("not_a_target") then
      Some(s"not a comparison target: ${optimize.obj("not_a_target")}")
    else None

  networks.foreach { name =>
    test(s"$name: every implemented metric matches PyPSA's statistics on every grouping") {
      assume(available, "goldens missing")
      assume(results(name).obj.contains("statistics"), s"$name has no statistics block")

      // A degenerate dispatch makes every metric over it a metric over one vertex of an
      // optimal face, so comparing it compares tie-breaks. `scigrid-de` is the case, and the
      // generator already records why in `dispatch_note` -- read from the golden rather than
      // listed here, so a network that becomes degenerate is skipped without this suite being
      // edited. `not_a_target` is the same idea for a fixture that is a rejection test.
      assume(comparable(name).isEmpty, s"$name is ${comparable(name).getOrElse("")}")

      val n = network(name)
      // A refusal is a skip rather than a failure: this module is downstream of the LOPF and
      // several goldens are deliberately refused there -- `unit-commitment` is committable,
      // `storage-hvdc` is extendable transmission. `GapRefusalSuite` is what asserts those.
      val solved = scala.util.Try(Lopf.solve(n, params))
      assume(solved.isSuccess, s"$name is refused or failed to build: ${solved.failed.map(_.getMessage)}")
      val result = solved.get
      assume(result.status == SolveStatus.Optimal, s"$name did not solve: ${result.status}")

      // Measured once per network, because it decides three of the nineteen metrics and
      // reads every bus at every snapshot.
      val duals = dualAgrees(name, result)
      // And checked against [[dualFace]] here rather than in a case of its own, so that it is
      // asserted on every network rather than on a sample. This is the guard: an assertion
      // that the three dual metrics WERE compared would sit behind this same flag and vanish
      // with them.
      assertEquals(duals, !dualFace.contains(name),
        s"$name: the dual-agreement measurement disagrees with the pinned list")

      scala.util.Using.resource(Stats.of(result)) { stats =>
        var compared = 0
        groupings.foreach { groupBy =>
          comparedMetrics.map(m => m -> (() => metrics(m)(stats, groupBy)))
            .filterNot((metric, _) => dualMetrics.contains(metric) && !duals)
            .foreach { (metric, run) =>
              // PyPSA's `country` grouper raises when it is REACHED, and a metric with no
              // rows never reaches it: `transmission|country` on a network with no
              // transmission branch comes back as an empty frame rather than as an error, on
              // ten goldens. `Stats.dimension` refuses the dimension up front instead, which
              // is the same answer arrived at earlier, so an empty golden counts as a
              // declination wherever the network has no `country` column at all.
              val declined =
                expected(name, metric, groupBy).isEmpty ||
                  (groupBy == "country" && countryRaises(name) &&
                    expected(name, metric, groupBy).exists(_.isEmpty))
              (if declined then None else expected(name, metric, groupBy)) match
                case None =>
                  // PyPSA raised. The module has to raise too, or it is answering a question
                  // upstream declines -- which for `country` would be "every bus is in the
                  // same blank country", a group that does not exist.
                  intercept[IllegalArgumentException](run())
                case Some(wanted) =>
                  val mine  = run()
                  // Whole COMPONENT KINDS, not individual keys, and the difference matters.
                  // The generator measures which keys moved between PyPSA's own two solves,
                  // so it cannot name a key my solve produces and neither of PyPSA's does --
                  // and on a degenerate flow pattern that happens: which lines carry anything
                  // at all is part of the face, not just how much. So if any group of a kind
                  // moved, every group of that kind comes out of this comparison.
                  //
                  // Still far finer than the whole-network skip it replaces: on `ac-dc-co2`
                  // only `Line` and `Link` are loose, and every generator group is compared.
                  // This metric's own verdict, at this grouping -- and at every grouping for
                  // the three that can cancel. See [[cancelling]] for why those three and for
                  // the three borrowed-verdict patches this replaced.
                  val loose =
                    (if cancelling.contains(metric) then
                       groupings.flatMap(g => undetermined(name, metric, g))
                     else undetermined(name, metric, groupBy).toSeq)
                      .map(component).toSet
                  // Keys where upstream's answer is NaN or an infinity because its
                  // DENOMINATOR is exactly zero, which is a fact about the solver rather than
                  // about the metric. PyPSA's simplex returns an exact zero for an asset that
                  // did not operate; a restarted PDHG at 1e-9 returns 1e-8 to 1e-13, so the
                  // quotient is a plausible-looking number instead of a NaN -- measured, 17
                  // groups across the goldens, from 3e-5 to 6.9e12. No threshold separates
                  // those from a real group, because `0 / 0` can land anywhere.
                  //
                  // `market_value` only. `capacity_factor`'s NaNs are STRUCTURAL -- a load has
                  // no `p_nom` at all -- and are asserted rather than skipped.
                  val undefined =
                    if metric == "market_value" then
                      wanted.collect { case (key, value) if !value.isFinite => key }.toSet
                    else Set.empty[String]
                  val wantedHere = wanted.filterNot((key, _) =>
                    loose.contains(component(key)) || undefined.contains(key))
                  val shared  = wantedHere.filter((key, _) => covered.contains(component(key)))
                  val skipped = wantedHere.keySet.map(component) -- covered

                  // Nothing is uncovered any more, so this asserts the comparison is whole
                  // rather than that the hole is the expected shape. A component kind PyPSA
                  // reports and this module does not know about is a gap to see.
                  assertEquals(skipped, Set.empty[String],
                    s"$name/$metric|$groupBy: PyPSA reports a component this module omits")

                  // A metric that agrees because both sides are empty proves nothing, so a
                  // trivial one is not counted. It does NOT abort the test: an `assume` here
                  // skipped every grouping after the first trivial one, which is a silent
                  // loss of coverage in the suite whose whole subject is silent losses of
                  // coverage. The `compared` count below is what makes a wholly trivial
                  // network a failure rather than a pass.
                  if shared.values.exists(v => math.abs(v) > 1e-9) then
                    compared += 1
                    assertEquals(
                      significant(metric, mine).keySet
                        .filterNot(k => loose.contains(component(k)))
                        .filterNot(undefined.contains)
                        .filter(k => covered.contains(component(k))),
                      significant(metric, shared).keySet,
                      s"$name/$metric|$groupBy: different groups from PyPSA")
                    shared.foreach { (key, want) =>
                      // A NaN is an ANSWER here, not a missing one, so it is asserted rather
                      // than tolerated: `capacity_factor` divides by `optimal_capacity` and a
                      // `Load` has no `p_nom`, so upstream reports NaN for every load in
                      // every golden. `assertEqualsDouble` would fail on `|NaN - NaN|`, and
                      // reporting a number where PyPSA reports NaN is exactly the kind of
                      // invented answer this suite exists to catch.
                      // Two branches rather than one, because `NaN == NaN` is false and an
                      // `assertEquals` on them fails with "values are not equal, even if they
                      // have the same toString()". An infinity compares fine.
                      if want.isNaN then
                        assert(mine.get(key).exists(_.isNaN),
                          s"$name/$metric|$groupBy at $key: PyPSA reports NaN, this reports " +
                            mine.get(key).fold("no group")(_.toString))
                      else if want.isInfinite then
                        assert(mine.get(key).contains(want),
                          s"$name/$metric|$groupBy at $key: PyPSA reports $want, this reports " +
                            mine.get(key).fold("no group")(_.toString))
                      else
                        assertEqualsDouble(mine.getOrElse(key, 0.0), want,
                          1e-4 * math.max(1.0, math.abs(want)), s"$name/$metric|$groupBy at $key")
                    }
            }
        }
        // `prices` gets its own block rather than its own case, inside the same solve. The
        // comparison above reads a component kind off the front of every key and skips by
        // kind; `prices` is keyed by the bus grouper alone, so there is no kind to read --
        // which also makes its determinacy skip finer, per KEY rather than per kind.
        var pricesCompared = 0
        groupings.foreach { groupBy =>
          if !duals || !pricesComparable(name) then ()
          else expected(name, "prices", groupBy) match
            case None =>
              // `bus` on every network and `country` on all but two: upstream raises
              // `ValueError` for the first and `AttributeError` for the second, and unlike
              // the component metrics it raises on EVERY network, because a network always
              // has buses for the grouper to reach.
              intercept[IllegalArgumentException](stats.prices(groupBy))
            case Some(wanted) =>
              val loose  = undetermined(name, "prices", groupBy)
              val mine   = stats.prices(groupBy)
              val shared = wanted.filterNot((key, _) => loose.contains(key))
              if shared.values.exists(v => math.abs(v) > 1e-9) then
                compared += 1
                pricesCompared += 1
                assertEquals(
                  significant("prices", mine).keySet.filterNot(loose.contains),
                  significant("prices", shared).keySet,
                  s"$name/prices|$groupBy: different groups from PyPSA")
                shared.foreach { (key, want) =>
                  assertEqualsDouble(mine.getOrElse(key, 0.0), want,
                    1e-4 * math.max(1.0, math.abs(want)), s"$name/prices|$groupBy at $key")
                }
        }
        // Reaching the end having compared nothing is the failure every `assume` above can
        // produce by accident, and it looks identical to a pass.
        assert(compared > 0, s"$name compared no metric at all")
        // And the same failure one metric down, for the HALF of `prices`' gate that is read
        // from the golden. A `pricesComparable` that became always false would take the metric
        // out of every comparison while `compared` stayed positive on the strength of the
        // other eighteen -- that mutant fails 30 cases here.
        //
        // It does NOT guard the dual half, because it is gated on it: forcing `duals` false
        // removes both the comparison and this assertion. The case above is what guards that,
        // by pinning the measurement rather than its consequence.
        if duals && pricesComparable(name) then
          assert(pricesCompared > 0, s"$name compared no prices grouping at all")
      }
    }
  }

  test("every network records a determinacy measurement") {
    assume(available, "goldens missing")
    // The skip above reads this block and treats an absent one as "everything is a point".
    // That is the right default for a key the generator found no disagreement on, and the
    // wrong one for a generator that stopped measuring -- which would silently restore every
    // comparison against a face. This is the difference.
    val missing = networks.filterNot(n => results(n).obj.get("determinacy").exists(_.obj.contains("statistics")))
    assertEquals(missing, List.empty[String])
  }

  test("the determinacy measurement finds the eleven networks it should") {
    assume(available, "goldens missing")
    // Pinned, for the reason the floor's own list is pinned: a measurement that silently
    // started finding nothing would restore every comparison against a face and look like a
    // pass.
    //
    // Eleven, and both the count and the contents moved when the probe's tolerance was
    // retuned to this suite's own -- `|a - b| <= 1e-4 * max(1, |a|, |b|)` in place of a 1e-2
    // ABSOLUTE floor calibrated on megawatt-hours. The lists got longer because the probe can
    // now see a face in a dimensionless group, a price and a cancelling net, which is the
    // whole point; `ac-dc-txvolume` is the network that is new here, on one `revenue` group.
    val affected = networks.filter { n =>
      groupings.exists(g => comparedMetrics.exists(m => undetermined(n, m, g).nonEmpty))
    }
    assertEquals(affected, List("ac-dc-co2", "ac-dc-dispatch", "ac-dc-meshed", "ac-dc-txcost",
                                "ac-dc-txvolume", "ac-dc-txvolume-exact", "annuitised-capital",
                                "ramp-limits", "scigrid-de", "storage-cyclic-co2",
                                "storage-hvdc"))

    // Which metric finds a ONE-PORT kind loose, per network and outside `scigrid-de`.
    //
    // A one-port kind coming loose is the expensive kind: a loose BRANCH kind costs
    // comparisons on components this module only recently covered at all, and a loose one-port
    // kind costs the comparisons that were the whole point of the module.
    //
    // By kind rather than by key, because the comparison skips by kind: the keys are in the
    // goldens and a list of them here would be four hundred lines of `scigrid-de`-shaped churn
    // without saying anything the kind does not.
    //
    // `scigrid-de` is excluded because it is degenerate across four component kinds and this
    // suite skips it whole for a degenerate dispatch -- its own `dispatch_note` says so.
    val onePortKinds = Set("Generator", "StorageUnit", "Store", "Load")
    val faces = networks
      .filterNot(_ == "scigrid-de")
      .map { n =>
        n -> groupings.flatMap { g =>
          comparedMetrics.flatMap { m =>
            undetermined(n, m, g)
              .filter(k => onePortKinds.contains(component(k)))
              .map(k => s"$m ${component(k)}")
          }
        }.toSet
      }
      .filter((_, kinds) => kinds.nonEmpty)
      .toMap
    assertEquals(faces, Map(
      "ac-dc-co2" ->
        Set("curtailment Generator", "market_value Generator", "market_value Load",
            "revenue Generator", "revenue Load"),
      "ac-dc-dispatch" ->
        Set("curtailment Generator", "market_value Generator", "market_value Load",
            "revenue Generator", "revenue Load"),
      "ac-dc-meshed" ->
        Set("capex Generator", "curtailment Generator", "opex Generator", "revenue Generator",
            "system_cost Generator"),
      "ac-dc-txcost" ->
        Set("capacity_factor Generator", "capex Generator", "curtailment Generator",
            "energy_balance Generator", "opex Generator", "revenue Generator",
            "supply Generator", "system_cost Generator"),
      "ac-dc-txvolume" ->
        Set("revenue Generator"),
      "ac-dc-txvolume-exact" ->
        Set("capex Generator", "curtailment Generator", "opex Generator", "revenue Generator",
            "system_cost Generator"),
      "annuitised-capital" ->
        Set("curtailment Generator", "opex Generator", "system_cost Generator"),
      "ramp-limits" ->
        Set("market_value Generator", "market_value Load", "revenue Generator", "revenue Load"),
      "storage-cyclic-co2" ->
        Set("capacity_factor Generator", "capacity_factor StorageUnit", "capex Generator",
            "curtailment Generator", "curtailment StorageUnit", "energy_balance Generator",
            "energy_balance StorageUnit", "expanded_capex Generator",
            "market_value StorageUnit", "opex Generator", "revenue Generator",
            "supply Generator", "system_cost Generator", "withdrawal StorageUnit"),
      "storage-hvdc" ->
        Set("capacity_factor Generator", "capacity_factor StorageUnit", "capex Generator",
            "curtailment Generator", "curtailment StorageUnit", "energy_balance Generator",
            "energy_balance StorageUnit", "expanded_capacity Generator",
            "expanded_capex Generator", "market_value StorageUnit", "opex Generator",
            "optimal_capacity Generator", "revenue Generator", "supply Generator",
            "system_cost Generator", "withdrawal StorageUnit"),
    ))
  }

  test("every network this skips reaches an optimal dual, not an approximate one") {
    assume(available, "goldens missing")
    // What licenses [[dualFace]]. Skipping a comparison because "the dual is a different
    // vertex" is only honest if this port's dual is a vertex of the same optimal face -- and
    // the alternative, that it is simply wrong, looks identical from the metric's side.
    //
    // Strong duality is what separates them: two objectives agreeing makes both the primal
    // and the dual optimal rather than merely feasible. `LopfSuite` asserts exactly this for
    // `ac-dc-dispatch` and explains why comparing the prices themselves would be asserting a
    // choice neither solver is obliged to make. This is the same assertion over the rest of
    // the list, plus the objective, so a dual that stopped being optimal fails here rather
    // than disappearing into a skip.
    dualFace.toList.sorted.foreach { name =>
      // Read once. `network` re-parses the fixture on every call, and this case was three
      // times slower than the rest of the suite put together before it was hoisted.
      val n      = network(name)
      val result = Lopf.solve(n, params)
      assertEquals(result.status, SolveStatus.Optimal, name)
      val primal = result.solution.objectiveValue
      assertEqualsDouble(primal, result.solution.dualObjectiveValue,
        1e-6 * math.max(1.0, math.abs(primal)),
        s"$name: strong duality violated, so the dual is not a face: ${result.solution.kkt}")
      assertEqualsDouble(primal, results(name)("optimize")("objective").num,
        1e-6 * math.max(1.0, math.abs(primal)),
        s"$name: the primal does not match PyPSA's, so the prices differ for a worse reason")
      // And finite, so the dual path cannot be silently NaN behind the tolerance.
      n.require("Bus").ids.foreach { bus =>
        n.snapshots.indices.foreach { t =>
          assert(result.marginalPrice(bus, t).isFinite, s"$name: $bus at snapshot $t")
        }
      }
    }
  }

  test("the three dual metrics are compared against the goldens, not merely gated") {
    assume(available, "goldens missing")
    // The gate itself is asserted per network against [[dualFace]]. This is the other half:
    // that the metrics behind it produce the golden's own numbers rather than an empty map
    // nobody notices.
    //
    // `statistics-dimensions` is the network because it sets `nice_name` on both its carriers,
    // so it is the one that distinguishes `prices`' RAW carrier from every other metric's
    // `nice_name` chain -- the mutant that reads the nice name fails 45 cases, and this is
    // where the distinction is visible in two lines rather than in a diff of 45.
    scala.util.Using.resource(Stats.of(Lopf.solve(network("statistics-dimensions"), params))) {
      stats =>
        val prices = stats.prices("carrier")
        assertEquals(prices.keySet, Set("AC", "H2"))
        expected("statistics-dimensions", "prices", "carrier").get.foreach { (key, want) =>
          assertEqualsDouble(prices(key), want, 1e-4 * math.max(1.0, want), key)
        }
        assert(stats.revenue("name").nonEmpty, "revenue compared nothing")
        assert(stats.marketValue("name").nonEmpty, "market_value compared nothing")
    }
    // And that the pinned list names networks that exist, so a renamed fixture fails here
    // rather than quietly un-skipping itself.
    assertEquals(dualFace -- networks.toSet, Set.empty[String])
  }

  test("prices is skipped on exactly the networks whose own prices are a face") {
    assume(available, "goldens missing")
    // The determinacy half of `prices`' gate, pinned -- the dual half needs a solve and the
    // per-network case asserts it there. Six networks, read off `prices`' own blocks.
    //
    // It used to read `withdrawal`'s blocks as well, because `prices` is a load-WEIGHTED
    // average and so moves when the dispatch behind its weights moves -- `storage-cyclic-co2`
    // was the case, its per-cell prices agreeing to 2e-6 while `prices|bus_carrier` sat 0.045
    // from upstream's 128.008. That borrowing is gone: the retuned probe sees the movement in
    // `prices`' own block, which is where it belongs.
    assertEquals(networks.filterNot(pricesComparable),
      List("ac-dc-co2", "ac-dc-dispatch", "ramp-limits", "scigrid-de", "storage-cyclic-co2",
           "storage-hvdc"))
  }

  test("every metric the generator records is implemented here or named as pending") {
    assume(available, "goldens missing")
    // `STATISTICS_METRICS` in the generator and `metrics` here are two lists, and a metric in
    // the first and not the second would be recorded in every golden and read by nothing --
    // reported as a pass. This is that failure, with [[pending]] as the one allowed shape of
    // the gap.
    assertEquals(recordedMetrics.toSet -- metrics.keySet -- pending, Set.empty[String],
      "the generator records a metric that is neither implemented nor named as pending")
    // The other two directions, so the named hole cannot outlive what it names. Without
    // these, a `pending` entry would survive the metric being implemented -- and quietly take
    // it out of every comparison in this file, which is precisely the silent loss of coverage
    // the list exists to prevent.
    assertEquals(pending -- recordedMetrics.toSet, Set.empty[String],
      "a pending metric the generator no longer records")
    assertEquals(pending.intersect(metrics.keySet), Set.empty[String],
      "a metric is implemented and still listed as pending, so nothing compares it")
  }

  test("every grouping PyPSA offers is one this module accepts") {
    // The two lists are written in two places -- `STATISTICS_GROUPINGS` in the generator and
    // `Stats.Dimensions` here -- and a dimension added to one and not the other would show up
    // as a grouping nobody compares rather than as a failure. This is that failure.
    assertEquals(groupings.toSet -- Stats.Dimensions, Set.empty[String])
  }

  test("an unknown grouping is refused by name rather than reaching SQL") {
    assume(available, "goldens missing")
    // `groupBy` becomes a column name, so an unchecked value is SQL injection through a
    // parameter that looks like an enum.
    scala.util.Using.resource(Stats.of(Lopf.solve(network("store-bank"), params))) { stats =>
      val failure = intercept[IllegalArgumentException](stats.supply("carrier; DROP TABLE fact"))
      assert(failure.getMessage.contains("no grouping dimension"), failure.getMessage)
    }
  }

  test("country is refused on a network whose buses carry no country column") {
    assume(available, "goldens missing")
    // PyPSA's own grouper raises `AttributeError` here, and the golden records that. Asserted
    // directly rather than left to the skip above, because "the golden has no block and the
    // module raised" is also what a module that raised for the wrong reason looks like.
    scala.util.Using.resource(Stats.of(Lopf.solve(network("store-bank"), params))) { stats =>
      val failure = intercept[IllegalArgumentException](stats.supply("country"))
      assert(failure.getMessage.contains("country"), failure.getMessage)
    }
  }

  test("a value is rounded to five decimals, because PyPSA rounds before it drops") {
    assume(available, "goldens missing")
    // `_apply_option_kwargs` rounds to `options.params.statistics.round` -- 5 by default --
    // and only then drops exact zeros, so upstream's numbers ARE rounded and so are these.
    //
    // This case exists because the group-set comparison cannot say so. The floor in
    // `significant` hides exactly the sub-1e-5 artefacts the rounding removes, so replacing
    // `ROUND(..., 5) <> 0` in the HAVING with a `> 1e-9` threshold fails nothing above --
    // an equivalent mutant, measured. The rounding of the VALUE is separable and is what this
    // asserts, which leaves only the HAVING unguarded and says so.
    scala.util.Using.resource(Stats.of(Lopf.solve(network("statistics-dimensions"), params))) {
      stats =>
        val values = Stats.Dimensions.toSeq.filter(_ != "country").flatMap { groupBy =>
          stats.supply(groupBy).values ++ stats.opex(groupBy).values
        }
        assert(values.nonEmpty, "nothing to check")
        values.foreach { v =>
          val scaled = v * 1e5
          assertEqualsDouble(scaled, math.rint(scaled), 1e-6,
            s"$v is not rounded to five decimals")
        }
    }
  }

  test("every component contributes the series its Role says it does") {
    assume(available, "goldens missing")
    // `quantitiesOf` is keyed by `Role` for branches, so `Process` would land correctly
    // without being named. Nothing in the metric comparisons can see that: `opex` over a
    // `Link` is excluded from every comparison because `Link` is an uncovered component, so
    // dropping `marginal_cost` from the controllable-branch case fails no assertion above --
    // an equivalent mutant, measured. This is the assertion that makes it load-bearing, and
    // it reads the loaded `fact` table rather than the metric.
    //
    // `statistics-dimensions` is the network because it carries a generator, a store and two
    // links at once, which is three of the four shapes in one place.
    scala.util.Using.resource(Stats.of(Lopf.solve(network("statistics-dimensions"), params))) {
      stats =>
        val loaded = stats
          .query("SELECT component, quantity FROM fact GROUP BY component, quantity")
          .map(r => r("component").toString -> r("quantity").toString)
          .groupMap(_._1)(_._2)
          .view.mapValues(_.toSet).toMap
        assertEquals(loaded("Generator"), Set("p", "marginal_cost", "p_max_pu"))
        assertEquals(loaded("Link"), Set("p0", "marginal_cost", "efficiency", "p_max_pu"))
        assertEquals(loaded("Store"), Set("e", "p", "marginal_cost", "marginal_cost_storage"))
        // `Load` has no solved series -- there is no load variable -- and contributes one
        // row anyway, because `assign_solution` writes `Load.p` from `p_set`. So the `p`
        // here is an INPUT, and `entity.sign` of -1 is what makes it a withdrawal rather
        // than a supply. It contributed nothing at all until `energy_balance` arrived, which
        // is the change that made four metrics possible.
        assertEquals(loaded("Load"), Set("p"))
        // And the sign column, which decides every branch/one-port split in the two views
        // over `fact`. NULL is "this is a branch".
        assertEquals(
          stats
            .query("SELECT component, sign FROM entity GROUP BY component, sign ORDER BY component")
            .map(r => r("component").toString -> Option(r("sign")).map(_.toString))
            .toMap,
          Map("Generator" -> Some("1.0"), "Link" -> None, "Load" -> Some("-1.0"),
              "Store" -> Some("1.0")))
    }
  }

  /** Every golden statistics group of a COVERED component, as `(network, block, key, value,
    * skipped)`.
    *
    * The covered filter matters and the review that asked for it was right: the key-set
    * comparison only ever looks at `Generator`, `StorageUnit` and `Store`, so a `Line` group
    * is never compared and counting one here would let the two cases below fail about a
    * comparison that does not happen.
    *
    * `skipped` is `comparable` ALONE, which is narrower than the per-network test's own skip
    * set -- that also skips a `Lopf` refusal and a non-optimal status, and `unit-commitment`
    * is refused for committable units while shipping a `statistics` block. So these two cases
    * are deliberately stricter than the comparison: a golden that gains a tiny group in a
    * LOPF-refused network fails the first one. That is the safe direction -- over-strict,
    * never falsely green -- and it is stated here because the failure message would otherwise
    * send a reader looking for a comparison that is not running. Folding the refusals in
    * would mean solving every network inside a case about the goldens, which is a worse
    * trade than this sentence.
    */
  private lazy val allGoldenGroups: List[(String, String, String, Double, Boolean)] =
    networks.flatMap { name =>
      val json = results(name)
      val skip = comparable(name).isDefined
      json.obj.get("statistics").toList.flatMap(_.obj.toList).flatMap { (block, frame) =>
        // `comparedMetrics` rather than every block, for the reason given there: a floor
        // measured over a metric nobody compares describes the reference, not this module.
        // `prices` is out as well -- its keys are buses, so `component(key)` reads a bus name
        // and `covered` would filter it by accident rather than by decision.
        if frame.obj.contains("error") || !comparedMetrics.contains(block.split('|').head) then
          Nil
        else
          frame.obj.toList
            .filter((key, _) => covered.contains(component(key)))
            .map((key, v) => (name, block, key, number(v), skip))
      }
    }

  test("the solver floor removes no non-zero golden group outside a skipped network") {
    assume(available, "goldens missing")
    // The floor is a judgement, so this pins what it actually does rather than arguing that
    // it is small. Two kinds of group fall below it, and only one of them is interesting.
    //
    // NON-ZERO, and there are thirteen, every one in `scigrid-de` -- which is the claim
    // that matters, because that is the one network this suite skips for a degenerate
    // dispatch. The floor hides no real number from any comparison that runs.
    //
    // Pinned by `network/metric` and count rather than key by key. The five `supply` ones are
    // worth naming in full because they are the boundary case the first version of this got
    // wrong:
    //
    //   1e-05   supply|name  Generator|320 Wind Onshore
    //   1e-05   supply|name  Generator|434 Wind Onshore
    //   4e-05   supply|name  Generator|264_220kV Wind Onshore
    //   8e-05   supply|name  Generator|378 Wind Onshore
    //   0.0001  supply|name  Generator|307_220kV Wind Onshore
    //
    // All five are real -- upstream's round-to-five rule admits `1e-5` at any scale -- and the
    // last is exactly the 1e-4 floor, which is in the list because `significant` keeps
    // `> groupFloor(metric)` rather than `>=`. The first version of this case said four, enumerated
    // with a strict `<` in a throwaway script, and the boundary group was the difference.
    // `energy_balance` reports the same five, being the signed metric `supply` is the positive
    // half of.
    val hidden = allGoldenGroups.filter((_, block, _, v, _) =>
      math.abs(v) <= groupFloor(block.split('|').head))
    val nonZero = hidden.filter((_, _, _, v, _) => v != 0.0)
    assertEquals(
      nonZero.groupBy((n, b, _, _, _) => s"$n/${b.split('|').head}").view.mapValues(_.size).toMap,
      Map("scigrid-de/supply" -> 5, "scigrid-de/energy_balance" -> 5,
          "scigrid-de/revenue" -> 3))
    // Thirteen where a single floor hid forty. The twenty-seven recovered are
    // `capacity_factor`'s thirteen and `market_value`'s fourteen, which a floor placed for a
    // cancellation residue in `revenue` was suppressing for no benefit -- neither metric
    // produces an artefact, so neither has a floor now. See [[groupFloor]].
    //
    // And the thirteen that remain are the honest part: `supply`, `energy_balance` and
    // `revenue` have real groups at the SAME magnitude as their artefacts, so no floor
    // separates them. These three sets are in `scigrid-de` and the only reason the floor is
    // safe is that this suite skips that network for a degenerate dispatch.
    assert(nonZero.forall((_, _, _, _, skipped) => skipped),
      "the floor hides a non-zero group in a network this suite actually compares")
    // EXACTLY ZERO, and there are many, all of them from the two `expanded_*` metrics. Those
    // are PyPSA's own and the two arrive at a zero differently, which is why both names are
    // pinned rather than a prefix matched:
    //
    //   expanded_capex     subtracts two already zero-dropped frames and does not re-drop
    //                      the difference, so a group whose expansion is nothing is 0.0
    //   expanded_capacity  additionally CLIPS, reporting 0 wherever the chosen capacity does
    //                      not exceed the installed one -- so it reports 0 for the same
    //                      retired asset that `expanded_capex` reports as -21,109,107.32
    //
    // Both are reproduced and their values are compared. The floor removes them from the SET
    // comparison, where a zero group carries no information either way.
    val zeroBlocks = hidden.filter((_, _, _, v, _) => v == 0.0)
      .map((_, b, _, _, _) => b.split('|').head).distinct
    assertEquals(zeroBlocks.sorted, List("expanded_capacity", "expanded_capex"))

    // And the NaNs, which this case would otherwise drop in silence: every comparison above
    // tests a magnitude, and `NaN <= groupFloor(metric)` is false, so a NaN group is in neither the
    // hidden set nor the kept one. There are 1,291 of them across 31 of the 41 networks and
    // every single one is `capacity_factor` for a `Load` -- upstream dividing by an
    // `optimal_capacity` a load has no `p_nom` for. The comparison asserts them directly; the
    // census is here so that a NaN appearing in some OTHER metric is a failure rather than a
    // group this case quietly stopped counting.
    //
    // Two metrics produce them and for different reasons. `capacity_factor`'s are STRUCTURAL
    // -- a load has no `p_nom` for `optimal_capacity` to report -- and the comparison asserts
    // them directly. `market_value`'s are NUMERICAL: its denominator is exactly zero wherever
    // the asset did not operate, which is a `0 / 0` upstream and an epsilon over an epsilon
    // here, so those keys are skipped per key and the census is what bounds the skip.
    val nonFinite = allGoldenGroups.filter((_, _, _, v, _) => !v.isFinite)
    assertEquals(
      nonFinite
        .groupBy((_, b, k, v, _) =>
          s"${b.split('|').head}/${component(k)}/${if v.isNaN then "nan" else "inf"}")
        .view.mapValues(_.size).toMap,
      Map(
        "capacity_factor/Load/nan" -> 1291,
        "market_value/Line/nan"    -> 559,
        "market_value/Line/inf"    -> 1,
        "market_value/Link/nan"    -> 67,
        "market_value/Generator/nan" -> 329,
        "market_value/Generator/inf" -> 5,
        "market_value/Transformer/nan" -> 146,
        "market_value/StorageUnit/nan" -> 5,
        "market_value/Load/nan"    -> 16,
      ))
  }

  test("expanded_capex reports a zero group, because PyPSA does not re-drop the difference") {
    assume(available, "goldens missing")
    // The behaviour the case above describes, asserted on the module rather than on the
    // goldens. `ac-dc-dispatch` has capacity fixed, so every generator's expansion is
    // nothing -- and upstream reports four `0.0` groups rather than an empty frame, because
    // `drop_zero` runs inside `capex` and `installed_capex` and not on their difference.
    //
    // A single `(nominal - nominal_in)` query with the usual `HAVING ... <> 0` drops them and
    // agreed with every assertion anyway: the floor filters a zero off both sides, so the
    // group sets matched and `getOrElse(key, 0.0)` made the absent values compare equal. This
    // is what makes the faithful version load-bearing.
    scala.util.Using.resource(Stats.of(Lopf.solve(network("ac-dc-dispatch"), params))) { stats =>
      val mine   = stats.expandedCapex("name")
      val wanted = expected("ac-dc-dispatch", "expanded_capex", "name").get
      val zeros  = wanted.filter((k, v) => v == 0.0 && covered.contains(component(k)))
      assert(zeros.nonEmpty, "the golden has no zero group to check against")
      zeros.foreach { (key, _) =>
        assert(mine.contains(key), s"$key is reported as 0.0 by PyPSA and omitted here")
        assertEqualsDouble(mine(key), 0.0, 1e-9, key)
      }
    }
  }

  test("every compared group clears its own metric's floor by a wide margin") {
    assume(available, "goldens missing")
    // The other half, and the one that says the floor is not merely small but WELL PLACED.
    // A single absolute floor made this a race to the bottom: `store-per-period`'s 0.6 at
    // 6,000x, then `phase-shift`'s `capacity_factor` at 161x, then `ac-dc-dispatch`'s
    // `market_value` of 0.0003846008 at 3.8x -- because one value had to serve metrics whose
    // scale differs by five orders of magnitude.
    //
    // With [[groupFloor]] per metric the question changes shape. Fourteen metrics have no
    // floor at all, so nothing of theirs can be close to one, and the five that do have a
    // floor are measured against their OWN groups. The ratio below is per metric and the
    // tightest of them is what is asserted.
    val tightest = allGoldenGroups
      .filterNot((_, _, _, _, skipped) => skipped)
      .filter((_, _, _, v, _) => math.abs(v) > 0.0)
      .map((_, block, _, v, _) =>
        val floor = groupFloor(block.split('|').head)
        if floor == 0.0 then Double.PositiveInfinity else math.abs(v) / floor)
    // The FINITE ratios only, and that distinction is the case's whole guard. Mapping an
    // unfloored metric's group to infinity makes `nonEmpty` true without any floored metric
    // having been measured: empty `floored`, or stop recording `supply` and `revenue` groups
    // in compared networks, and every ratio is infinite, `min > 3.0` holds, and this passes
    // having measured nothing. That is the silent-coverage-loss mode the predecessor's own
    // `compared.nonEmpty` guard existed to catch, and replacing it with a weaker one is the
    // kind of regression this suite is supposed to be good at noticing.
    val ratios = tightest.filter(_.isFinite)
    assert(ratios.nonEmpty, "no floored metric contributed a compared group to measure")
    assert(ratios.min > 3.0,
      s"a compared group sits within 3x of its metric's floor: ratio ${ratios.min}")
  }

  test("the floor drops the solver artefact and keeps a real group") {
    // The two values the floor exists to separate, taken from `ac-dc-txvolume-exact`: Prima
    // reports 1.0e-5 for two generators PyPSA leaves at exactly 0.0, and 1465.27437 for one
    // it reports at 1465.27439.
    val groups = Map(
      "Generator|Manchester Gas" -> 1.0e-5,
      "Generator|Norway Gas"     -> 1.0e-5,
      "Generator|Frankfurt Gas"  -> 1465.27437,
    )
    assertEquals(significant("supply", groups).keySet, Set("Generator|Frankfurt Gas"))

    // And the other branch, which is what this floor's shape exists for: `market_value` is
    // unfloored, so the same magnitude that is an artefact in `supply` is a GROUP there --
    // 4.96e-06 is the smallest one upstream actually reports. Pinned here rather than left to
    // the goldens-gated comparison, so the zero-floor branch is covered where the floored one
    // is.
    assertEquals(significant("market_value", Map("a" -> 4.96e-6)).keySet, Set("a"))
    assertEquals(significant("supply", Map("a" -> 4.96e-6)).keySet, Set.empty[String])
  }

  test("the floor keeps a real group that a relative one would hide") {
    // The case that makes the floor ABSOLUTE rather than a fraction of the largest group, and
    // the one the first version got wrong. `scigrid-de` is its real shape: PyPSA reports five
    // onshore wind farms between 1e-5 and 1e-4 against a largest group of 61,728, and its
    // round-to-five rule admits them, so they are groups and not noise.
    //
    // A relative floor of 1e-6 of the largest would put the threshold at 0.0617 and hide all
    // five. Here `small` stands for them: real, and eleven orders below `huge`.
    val groups = Map("huge" -> 1.5e9, "small" -> 0.6, "artefact" -> 1.0e-5)
    assertEquals(significant("supply", groups).keySet, Set("huge", "small"))
    // Said as the comparison rather than left implicit, because "absolute" is the property
    // and a reader should be able to see what it buys.
    val relativeFloor = 1e-6 * groups.values.map(math.abs).max
    assert(0.6 < relativeFloor,
      s"this case no longer separates the two floors: 0.6 is above $relativeFloor")
  }

  test("the floor is empty-safe and does not invent a group") {
    // `maxOption` on an empty map was what the relative version needed a default for, and a
    // default of 0.0 there made `|v| > 0` drop every group of an all-zero metric -- which is
    // correct but by accident. Absolute has no such edge, and this says so.
    assertEquals(significant("supply", Map.empty), Map.empty[String, Double])
    assertEquals(significant("supply", Map("a" -> 0.0)), Map.empty[String, Double])
    assertEquals(significant("supply", Map("a" -> -5000.0)), Map("a" -> -5000.0))
  }

  test("the two expanded_* metrics disagree about the same retirement, as upstream does") {
    assume(available, "goldens missing")
    // `ac-dc-meshed` retires gas: 150,000 MW installed against 982.03 chosen. The two
    // metrics report that differently and neither formula hints at the other:
    //
    //   expanded_capex     capex.sub(installed_capex, fill_value=0)
    //                      -> -21,109,107.32, a negative
    //   expanded_capacity  optimal.sub(installed).where(optimal.abs() > installed.abs(), 0)
    //                      -> 0, because 982 does not exceed 150,000
    //
    // Both are in the goldens for that network, so the network-wide comparison already
    // covers them. This case exists because the clip is the kind of term that reads like a
    // safety net and is load-bearing: dropping it makes the capacity metric agree with the
    // capex one in sign, which is exactly what upstream does not do.
    scala.util.Using.resource(Stats.of(Lopf.solve(network("ac-dc-meshed"), params))) { stats =>
      val cost     = stats.expandedCapex("carrier")
      val capacity = stats.expandedCapacity("carrier")
      assert(cost("Generator|gas") < -1e6,
        s"gas was retired, so its expanded capex is a large negative: ${cost("Generator|gas")}")
      assertEqualsDouble(capacity("Generator|gas"), 0.0, 1e-9,
        "the same retirement is clipped to zero in capacity")
      // And the metric is not uniformly zero, which a clip applied to everything would be:
      // wind genuinely expands, 290 MW installed to 7,292.13 chosen.
      assert(capacity("Generator|wind") > 7000.0,
        s"wind expands and must not be clipped: ${capacity("Generator|wind")}")
    }
  }
