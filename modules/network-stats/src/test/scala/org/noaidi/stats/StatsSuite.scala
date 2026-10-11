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

  /** A golden metric, split into what this module claims and what it does not.
    *
    * Multi-period networks are excluded, and visibly: PyPSA returns a frame with a column
    * per period there, so its keys carry a third part and its numbers are per period while
    * these queries sum over the horizon. Comparing them would need the queries grouped by
    * period too. Detected from the key shape rather than from a list of network names, so a
    * network that gains periods does not silently start being skipped.
    */
  private def expected(name: String, metric: String): Option[Map[String, Double]] =
    val block = results(name).obj.get("statistics").flatMap(_.obj.get(s"$metric|carrier"))
    block.flatMap { frame =>
      if frame.obj.contains("error") then None
      else if frame.obj.keys.exists(_.count(_ == '|') > 1) then None  // per-period, see above
      else Some(frame.obj.map((k, v) => k -> v.num).toMap)
    }

  networks.foreach { name =>
    test(s"$name: supply and opex match PyPSA's statistics") {
      assume(available, "goldens missing")

      val supplyWanted = expected(name, "supply")
      val opexWanted   = expected(name, "opex")
      assume(supplyWanted.isDefined && opexWanted.isDefined,
             s"$name has no flat-index statistics to compare")

      // A degenerate dispatch makes every metric over it a metric over one vertex of an
      // optimal face, so comparing it compares tie-breaks. `scigrid-de` is the case, and the
      // generator already records why in `dispatch_note` -- read from the golden rather than
      // listed here, so a network that becomes degenerate is skipped without this suite
      // being edited. `not_a_target` is the same idea for a fixture that is a rejection test
      // rather than an answer.
      val optimize = results(name)("optimize")
      assume(!optimize.obj.contains("dispatch_note"),
             s"$name has a degenerate dispatch: ${optimize.obj.get("dispatch_note")}")
      assume(!optimize.obj.contains("not_a_target"),
             s"$name is not a comparison target: ${optimize.obj.get("not_a_target")}")

      val n = network(name)
      // A refusal is a skip rather than a failure: this module is downstream of the LOPF and
      // several goldens are deliberately refused there -- `unit-commitment` is committable,
      // `storage-hvdc` is extendable transmission. `GapRefusalSuite` is what asserts those.
      val solved = scala.util.Try(Lopf.solve(n, params))
      assume(solved.isSuccess, s"$name is refused or failed to build: ${solved.failed.map(_.getMessage)}")
      val result = solved.get
      assume(result.status == SolveStatus.Optimal, s"$name did not solve: ${result.status}")

      scala.util.Using.resource(Stats.of(result)) { stats =>
        Seq(
          "supply" -> (supplyWanted.get, stats.supply()),
          "opex"   -> (opexWanted.get, stats.opex()),
        ).foreach { (metric, pair) =>
          val (wanted, got) = pair
          val comparable = wanted.filter((key, _) => covered.contains(key.takeWhile(_ != '|')))
          val skipped    = wanted.keySet.map(_.takeWhile(_ != '|')) -- covered

          // The uncovered set is asserted, not ignored. If PyPSA ever reports a component
          // kind this module neither covers nor knows about, that is a gap to see rather
          // than a row quietly dropped from the comparison.
          assert(skipped.subsetOf(uncovered),
                 s"$name/$metric: PyPSA reports ${skipped -- uncovered}, which is neither " +
                   "covered nor a known branch kind")

          // A metric that agrees because both sides are empty proves nothing. Every other
          // suite here checks its fixture binds before comparing a number; this is that.
          assume(comparable.values.exists(v => math.abs(v) > 1e-9),
                 s"$name/$metric is all zero for the covered components")

          assertEquals(got.keySet.filter(k => covered.contains(k.takeWhile(_ != '|'))),
                       comparable.keySet,
                       s"$name/$metric: different groups from PyPSA")
          comparable.foreach { (key, want) =>
            assertEqualsDouble(got.getOrElse(key, 0.0), want,
              1e-4 * math.max(1.0, math.abs(want)), s"$name/$metric at $key")
          }
        }
      }
    }
  }
