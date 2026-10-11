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

  /** The components whose injections [[Stats.supply]] sums.
    *
    * PyPSA's `energy_balance` also includes the branches at their ports, with a sign per end.
    * This module does not, and the test below asserts the difference is '''exactly''' the
    * branch kinds -- so covering them later changes a test rather than quietly widening an
    * agreement.
    */
  private val covered   = Set("Generator", "StorageUnit", "Store")
  private val uncovered = Set("Line", "Link", "Transformer")

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
          Seq("supply" -> (() => stats.supply(groupBy)), "opex" -> (() => stats.opex(groupBy)))
            .foreach { (metric, run) =>
              expected(name, metric, groupBy) match
                case None =>
                  // PyPSA raised. The module has to raise too, or it is answering a question
                  // upstream declines -- which for `country` would be "every bus is in the
                  // same blank country", a group that does not exist.
                  intercept[IllegalArgumentException](run())
                case Some(wanted) =>
                  val mine    = run()
                  val shared  = wanted.filter((key, _) => covered.contains(component(key)))
                  val skipped = wanted.keySet.map(component) -- covered

                  // The uncovered set is asserted, not ignored. If PyPSA ever reports a
                  // component kind this module neither covers nor knows about, that is a gap
                  // to see rather than a row quietly dropped from the comparison.
                  assert(skipped.subsetOf(uncovered),
                         s"$name/$metric|$groupBy: PyPSA reports ${skipped -- uncovered}, " +
                           "which is neither covered nor a known branch kind")

                  // A metric that agrees because both sides are empty proves nothing, so a
                  // trivial one is not counted. It does NOT abort the test: an `assume` here
                  // skipped every grouping after the first trivial one, which is a silent
                  // loss of coverage in the suite whose whole subject is silent losses of
                  // coverage. The `compared` count below is what makes a wholly trivial
                  // network a failure rather than a pass.
                  if shared.values.exists(v => math.abs(v) > 1e-9) then
                    compared += 1
                    assertEquals(
                      significant(mine).keySet.filter(k => covered.contains(component(k))),
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
        assertEquals(loaded("Link"), Set("p0", "marginal_cost"))
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

  test("the solver floor removes exactly five golden groups, all in a skipped network") {
    assume(available, "goldens missing")
    // The floor is a judgement, so this pins what it actually does rather than arguing that
    // it is small. Every group PyPSA reports below it, over every network in the manifest --
    // 39 of them -- and both metrics and all seven groupings:
    //
    //   1e-05   scigrid-de/supply|name  Generator|320 Wind Onshore
    //   1e-05   scigrid-de/supply|name  Generator|434 Wind Onshore
    //   4e-05   scigrid-de/supply|name  Generator|264_220kV Wind Onshore
    //   8e-05   scigrid-de/supply|name  Generator|378 Wind Onshore
    //   0.0001  scigrid-de/supply|name  Generator|307_220kV Wind Onshore
    //
    // All five are real -- upstream's round-to-five rule admits `1e-5` at any scale -- and
    // all five are in the one network this suite skips for a degenerate dispatch. So the
    // floor currently hides nothing from any comparison that runs.
    //
    // The last one is exactly `GroupFloor`, and it is in this list because `significant`
    // keeps `> GroupFloor` rather than `>=`. The first version of this case said four,
    // enumerated with a strict `<` in a throwaway script, and the boundary group was the
    // difference -- which is the kind of thing a pinned list catches and a sentence about
    // magnitudes does not.
    //
    // A golden that gains a genuinely tiny group in a network that IS compared fails here,
    // which is the point: the alternative is that it silently stops being compared.
    val hidden = allGoldenGroups.filter((_, _, _, v, _) => math.abs(v) <= GroupFloor)
    assertEquals(hidden.map((n, b, k, _, _) => s"$n/$b/$k").sorted,
      List(
        "scigrid-de/supply|name/Generator|264_220kV Wind Onshore",
        "scigrid-de/supply|name/Generator|307_220kV Wind Onshore",
        "scigrid-de/supply|name/Generator|320 Wind Onshore",
        "scigrid-de/supply|name/Generator|378 Wind Onshore",
        "scigrid-de/supply|name/Generator|434 Wind Onshore",
      ))
    assert(hidden.forall((_, _, _, _, skipped) => skipped),
      "the floor hides a group in a network this suite actually compares")
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
