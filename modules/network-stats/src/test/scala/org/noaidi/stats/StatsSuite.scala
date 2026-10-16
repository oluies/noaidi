package org.noaidi.stats

import java.nio.file.Files
import org.noaidi.lopf.{CsvFixtures, Lopf}
import org.noaidi.prima.{PdhgParams, SolveStatus}

/** The two metrics against PyPSA's own `statistics`, never against arithmetic.
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
  private val covered = Set("Generator", "StorageUnit", "Store", "Line", "Link", "Transformer")

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
  )

  /** The floor below which a group is a solver's zero rather than a group.
    *
    * Absolute, and that is the whole point of it. PyPSA drops a zero group by rounding to
    * five decimals and testing against exact zero, so the smallest value its rule admits is
    * `1e-5` **at any scale** -- the rule is five decimal places, not a fraction of anything.
    * A floor in the same shape is the only one that can be reasoned about against it.
    *
    * The first version was relative, `1e-6` of the largest group in the same metric, and it
    * was the wrong shape twice over. It happened to suppress the artefact below, and it would
    * also have hidden five groups PyPSA genuinely reports -- `scigrid-de`'s five onshore wind
    * farms at `1e-5` to `1e-4` against a largest group of 61,728, a ratio as low as 1.6e-10.
    * Those are real: upstream's own rule admits them.
    */
  private val GroupFloor = 1e-4

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
    * see [[GroupFloor]]. So what makes this safe is not the threshold but the two cases
    * below, which pin what it actually removes: exactly five groups across every golden, all
    * five in `scigrid-de`, which this suite skips for a degenerate dispatch anyway, while
    * the smallest group in any network it does compare is `0.6`. Nearly four orders of
    * margin, asserted rather than asserted-to-be-obvious, so a future golden with a
    * genuinely tiny group fails a test instead of being quietly dropped.
    *
    * This is a tolerance on the SOLVER, not on the metric: every surviving group's value is
    * still compared at `1e-4` relative, and the `drop_zero` defects this assertion has
    * already caught -- a missing `drop_zero` entirely, and `store-bank`'s carrier -- were
    * group sets differing by whole groups rather than by a rounding artefact.
    */
  private def significant(groups: Map[String, Double]): Map[String, Double] =
    groups.filter((_, v) => math.abs(v) > GroupFloor)

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
      else Some(frame.obj.map((k, v) => k -> v.num).toMap)
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

  /** Whether this golden is worth comparing at all, with the reason read from the golden. */
  private def comparable(name: String): Option[String] =
    val optimize = results(name)("optimize")
    if optimize.obj.contains("dispatch_note") then
      Some(s"degenerate dispatch: ${optimize.obj("dispatch_note")}")
    else if optimize.obj.contains("not_a_target") then
      Some(s"not a comparison target: ${optimize.obj("not_a_target")}")
    else None

  networks.foreach { name =>
    test(s"$name: supply and opex match PyPSA's statistics on every grouping") {
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

      scala.util.Using.resource(Stats.of(result)) { stats =>
        var compared = 0
        groupings.foreach { groupBy =>
          recordedMetrics.map(m => m -> (() => metrics(m)(stats, groupBy)))
            .foreach { (metric, run) =>
              expected(name, metric, groupBy) match
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
                  val loose = undetermined(name, metric, groupBy).map(component)
                  val wantedHere = wanted.filterNot((key, _) => loose.contains(component(key)))
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
                      significant(mine).keySet
                        .filterNot(k => loose.contains(component(k)))
                        .filter(k => covered.contains(component(k))),
                      significant(shared).keySet,
                      s"$name/$metric|$groupBy: different groups from PyPSA")
                    shared.foreach { (key, want) =>
                      assertEqualsDouble(mine.getOrElse(key, 0.0), want,
                        1e-4 * math.max(1.0, math.abs(want)), s"$name/$metric|$groupBy at $key")
                    }
            }
        }
        // Reaching the end having compared nothing is the failure every `assume` above can
        // produce by accident, and it looks identical to a pass.
        assert(compared > 0, s"$name compared no metric at all")
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

  test("the determinacy measurement finds the five networks it should") {
    assume(available, "goldens missing")
    // Pinned, for the reason the floor's own list is pinned: a measurement that silently
    // started finding nothing would restore every comparison against a face and look like a
    // pass.
    val affected = networks.filter { n =>
      groupings.exists(g => recordedMetrics.exists(m => undetermined(n, m, g).nonEmpty))
    }
    assertEquals(affected, List("ac-dc-co2", "ac-dc-txcost", "scigrid-de",
                                "storage-cyclic-co2", "storage-hvdc"))

    // Three of the five are degenerate in their BRANCHES alone, which is what let branch
    // coverage ship: every one-port group on them is a point, so widening `covered` cost
    // nothing on the components that were already compared.
    Seq("ac-dc-co2", "ac-dc-txcost", "storage-cyclic-co2").foreach { n =>
      val kinds = groupings.flatMap(g => recordedMetrics.flatMap(m => undetermined(n, m, g)))
        .map(component).toSet
      assertEquals(kinds -- Set("Line", "Link", "Transformer"), Set.empty[String],
        s"$n was branch-only and now has an undetermined one-port group")
    }

    // `scigrid-de` is degenerate across four component kinds, which its `dispatch_note`
    // already said. `storage-hvdc` has ONE one-port group, and it is worth naming rather
    // than folding into the three above: `Generator|Gas 2`'s capex moves by 0.0236 between
    // the two solves, which is 2.4x the 1e-2 floor rather than the 14-to-538 the other faces
    // move by. It sits in the band where interior-point tolerance and a genuine face cannot
    // be told apart, and it is treated as a face because that direction costs one skipped
    // group while the other costs a comparison that breaks on a tie-break.
    val hvdc = groupings.flatMap(g => recordedMetrics.flatMap(m => undetermined("storage-hvdc", m, g)))
      .filter(k => component(k) == "Generator").toSet
    assertEquals(hvdc, Set("Generator|Gas 2", "Generator|2"))
  }

  test("every metric the generator records has an implementation here") {
    assume(available, "goldens missing")
    // `STATISTICS_METRICS` in the generator and `metrics` here are two lists, and a metric in
    // the first and not the second would be recorded in every golden and read by nothing --
    // reported as a pass. This is that failure.
    assertEquals(recordedMetrics.toSet -- metrics.keySet, Set.empty[String])
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
        assertEquals(loaded("Generator"), Set("p", "marginal_cost"))
        assertEquals(loaded("Link"), Set("p0", "marginal_cost", "efficiency"))
        assertEquals(loaded("Store"), Set("e", "p", "marginal_cost", "marginal_cost_storage"))
        // `Load` is a one-port with no solved series, listed explicitly in `quantitiesOf`
        // rather than falling through, so it contributes no rows at all.
        assertEquals(loaded.get("Load"), None)
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
        if frame.obj.contains("error") then Nil
        else
          frame.obj.toList
            .filter((key, _) => covered.contains(component(key)))
            .map((key, v) => (name, block, key, v.num, skip))
      }
    }

  test("the solver floor removes no non-zero golden group outside a skipped network") {
    assume(available, "goldens missing")
    // The floor is a judgement, so this pins what it actually does rather than arguing that
    // it is small. Two kinds of group fall below it, and only one of them is interesting.
    //
    // NON-ZERO, and there are five, every one in `scigrid-de`:
    //
    //   1e-05   supply|name  Generator|320 Wind Onshore
    //   1e-05   supply|name  Generator|434 Wind Onshore
    //   4e-05   supply|name  Generator|264_220kV Wind Onshore
    //   8e-05   supply|name  Generator|378 Wind Onshore
    //   0.0001  supply|name  Generator|307_220kV Wind Onshore
    //
    // All five are real -- upstream's round-to-five rule admits `1e-5` at any scale -- and
    // all five are in the one network this suite skips for a degenerate dispatch. So the
    // floor hides no real number from any comparison that runs.
    //
    // The last one is exactly `GroupFloor`, and it is in this list because `significant`
    // keeps `> GroupFloor` rather than `>=`. The first version of this case said four,
    // enumerated with a strict `<` in a throwaway script, and the boundary group was the
    // difference -- which is the kind of thing a pinned list catches and a sentence about
    // magnitudes does not.
    //
    val hidden  = allGoldenGroups.filter((_, _, _, v, _) => math.abs(v) <= GroupFloor)
    val nonZero = hidden.filter((_, _, _, v, _) => v != 0.0)
    assertEquals(nonZero.map((n, b, k, _, _) => s"$n/$b/$k").sorted,
      List(
        "scigrid-de/supply|name/Generator|264_220kV Wind Onshore",
        "scigrid-de/supply|name/Generator|307_220kV Wind Onshore",
        "scigrid-de/supply|name/Generator|320 Wind Onshore",
        "scigrid-de/supply|name/Generator|378 Wind Onshore",
        "scigrid-de/supply|name/Generator|434 Wind Onshore",
      ))
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

  test("the smallest group in a compared network is far above the floor") {
    assume(available, "goldens missing")
    // The other half, and the one that says the margin is not a coincidence of the five
    // above. `store-per-period`'s 0.6 is the smallest group PyPSA reports in any network this
    // suite compares, which is 6,000x the floor. Asserted as a floor on the margin rather
    // than as the exact number, because a new fixture may legitimately lower it -- but not by
    // three orders of magnitude without someone looking.
    //
    // Exact zeros are excluded here and counted as hidden by the case above, which is not an
    // inconsistency: a zero is not a group whose margin can be measured, and PyPSA's own
    // `drop_zero` means a recorded zero can only come from a `round(5)` that did not reach
    // one. The case above wants every value the floor removes; this one wants the smallest
    // value it keeps.
    val compared = allGoldenGroups
      .filterNot((_, _, _, _, skipped) => skipped)
      .map((_, _, _, v, _) => math.abs(v))
      .filter(_ > 0.0)
    // Explicit, because `.min` on an empty list throws `UnsupportedOperationException` and
    // names nothing -- and an empty list here is exactly what a suite that stopped reading
    // the goldens looks like.
    assert(compared.nonEmpty, "no compared golden group to measure the floor against")
    val smallest = compared.min
    assert(smallest > 100.0 * GroupFloor,
      s"the smallest compared group is $smallest, within 100x of the floor $GroupFloor")
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
    assertEquals(significant(groups).keySet, Set("Generator|Frankfurt Gas"))
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
    assertEquals(significant(groups).keySet, Set("huge", "small"))
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
    assertEquals(significant(Map.empty), Map.empty[String, Double])
    assertEquals(significant(Map("a" -> 0.0)), Map.empty[String, Double])
    assertEquals(significant(Map("a" -> -5000.0)), Map("a" -> -5000.0))
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
