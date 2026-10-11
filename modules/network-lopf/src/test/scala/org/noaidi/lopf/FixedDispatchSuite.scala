package org.noaidi.lopf

import java.nio.file.Files
import org.noaidi.network.{CsvReader, Network}
import org.noaidi.prima.{PdhgParams, SolveStatus}

/** `p_set` on a generator and on a controllable branch, against PyPSA's own answer.
  *
  * The gap this closes had a NOTES entry and no code site for months:
  * `define_fixed_operation_constraints` pins a dispatch variable to its `<attr>_set` and this
  * port read `p_set` for `Load` only, so the LP solved the unconstrained problem and returned
  * `Optimal`. It then spent one release as a refusal — `GapRefusalSuite` had three cases,
  * deleted in the change this suite arrived with.
  *
  * ==Why `ac-pf-pv` could not be the fixture==
  *
  * It is the fixture that '''found''' the gap, and it is still here as the last test below,
  * because a regression there is the exact divergence that went unseen. But it cannot be the
  * fixture the feature is built against: it belongs to the AC power flow, where `p_set` is an
  * input to `pf` rather than a constraint, so its values are chosen for Newton-Raphson and
  * cannot be moved to make a dispatch set point discriminating.
  *
  * Nothing compared its LOPF result until `network-stats` ran a metric over every golden,
  * which is the whole argument for a suite that iterates the manifest.
  */
class FixedDispatchSuite extends munit.FunSuite, CsvFixtures:

  override protected def tempPrefix: String = "noaidi-fixed-"

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)

  private def results(name: String): ujson.Value =
    ujson.read(Files.readString(goldens.resolve("results").resolve(s"$name.json")))

  private def objectiveOf(name: String): Double = results(name)("optimize")("objective").num

  /** A column of a golden frame, by entity name. */
  private def frameValue(frame: ujson.Value, row: Int, column: String): Double =
    val index = frame("columns").arr.indexWhere(_.str == column)
    assert(index >= 0, s"golden frame has no column '$column'")
    frame("values")(row)(index).num

  /** The fixture with one of its files deleted.
    *
    * Needed because `pinned`'s set point is a '''series file''' rather than a column, and
    * `setColumn` cannot remove a file. That asymmetry is the point of the fixture carrying
    * one of each: `p_set` is `static or series` upstream, and a series with no static
    * counterpart is invisible to a `static.contains` check.
    */
  private def without(name: String, file: String): Network =
    val dir = copyOf(name)
    Files.delete(dir.resolve(file))
    CsvReader.read(dir, schema, name)

  private def solve(n: Network): LopfResult =
    val result = Lopf.solve(n, params)
    assertEquals(result.status, SolveStatus.Optimal, s"did not solve: ${result.status}")
    result

  test("a generator and a link held at p_set match PyPSA's answer") {
    assume(available, "goldens missing")
    val result   = solve(network("fixed-dispatch"))
    val expected = results("fixed-dispatch")("optimize")
    val target   = expected("objective").num

    assertEqualsDouble(result.objective, target, 1e-6 * math.abs(target),
      s"objective ${result.objective} against PyPSA's $target")

    // The dispatch, not only the objective. Two readings can cost the same and pin different
    // things -- `pinned` at 50 with `cheap` at 170 costs what `pinned` at 170 and `cheap` at
    // 50 would not, but a fixture is only safe from that if it checks the columns.
    val dispatch = expected("generator_p")
    (0 until 3).foreach { t =>
      Seq("cheap", "pinned", "local", "spare").foreach { id =>
        val want = frameValue(dispatch, t, id)
        assertEqualsDouble(result.dispatch("Generator", id, t), want, 1e-6,
          s"$id at $t")
      }
      val tie = frameValue(expected("link_p0"), t, "tie")
      assertEqualsDouble(result.dispatch("Link", "tie", t), tie, 1e-6, s"tie at $t")
    }

    // Nodal prices, because a pin is a row and a row has a dual. The two buses price
    // differently -- 10 and 50 -- precisely because the link is held below its rating and
    // cannot equalise them, so a port that pinned the column by tightening its bounds
    // instead of by emitting a row would still cost 28,400 and would move these.
    val prices = expected("bus_marginal_price")
    (0 until 3).foreach { t =>
      Seq("a", "b").foreach { bus =>
        assertEqualsDouble(result.marginalPrice(bus, t), frameValue(prices, t, bus), 1e-6,
          s"price at $bus, $t")
      }
    }
  }

  // Each pin, dropped on its own, measured against PyPSA rather than against this port.
  // Separated from each other as well as from the free problem: a fixture where the two
  // happened to coincide would pass with either one read and the other ignored.
  test("dropping the generator's p_set series changes the answer") {
    assume(available, "goldens missing")
    val result = solve(without("fixed-dispatch", "generators-p_set.csv"))
    assertEqualsDouble(result.objective, 18600.0, 1e-4,
      "PyPSA with `pinned` unpinned costs 18,600")
  }

  test("dropping the link's p_set column changes the answer") {
    assume(available, "goldens missing")
    val n = mutate("fixed-dispatch", "links.csv", setColumn(_, "p_set", ""))
    assertEqualsDouble(solve(n).objective, 18800.0, 1e-4,
      "PyPSA with `tie` unpinned costs 18,800")
  }

  test("dropping both p_set values gives the free optimum") {
    assume(available, "goldens missing")
    val dir = copyOf("fixed-dispatch")
    Files.delete(dir.resolve("generators-p_set.csv"))
    Files.writeString(
      dir.resolve("links.csv"),
      setColumn(Files.readString(dir.resolve("links.csv")), "p_set", ""),
    )
    val n = CsvReader.read(dir, schema, "fixed-dispatch")
    assertEqualsDouble(solve(n).objective, 9000.0, 1e-4,
      "PyPSA with neither pinned costs 9,000")
  }

  test("a set point at a snapshot the asset does not exist at is dropped") {
    assume(available, "goldens missing")
    // `mask = active & (~fix.isnull())`, and this is the half with teeth. `new` carries
    // `p_set = 40` at every snapshot and is built in 2040, so the first period has no row.
    //
    // Getting it wrong does not cost accuracy. An inactive asset's column is pinned to
    // `[0, 0]` here, so an unmasked equality reads `0 = 40` and the LP is INFEASIBLE -- a
    // verdict PyPSA never reaches, reported as the network's problem rather than as this
    // model's. So the assertion that matters is as much `Optimal` as it is the number.
    val result   = solve(network("fixed-dispatch-periods"))
    val expected = results("fixed-dispatch-periods")("optimize")
    val target   = expected("objective").num
    assertEqualsDouble(result.objective, target, 1e-6 * math.abs(target),
      s"objective ${result.objective} against PyPSA's $target")

    val dispatch = expected("generator_p")
    (0 until 6).foreach { t =>
      Seq("old", "new").foreach { id =>
        assertEqualsDouble(result.dispatch("Generator", id, t), frameValue(dispatch, t, id),
          1e-6, s"$id at $t")
      }
    }
    // Named explicitly as well as checked against the frame, because this is the property:
    // zero through the first period, pinned through the second.
    (0 until 3).foreach(t => assertEqualsDouble(result.dispatch("Generator", "new", t), 0.0, 1e-6))
    (3 until 6).foreach(t => assertEqualsDouble(result.dispatch("Generator", "new", t), 40.0, 1e-6))
  }

  test("an infinite p_set is refused rather than becoming a right-hand side") {
    assume(available, "goldens missing")
    // NaN is the attribute's default and means "not given", so it cannot be an error. An
    // infinity is neither: PyPSA has no null test that catches it, so it reaches linopy as a
    // right-hand side, and here it would reach the builder as one. The refusal has to name
    // the entity and the snapshot, because a file with one bad cell among many is the case.
    val failure = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(mutate("fixed-dispatch", "links.csv", setColumn(_, "p_set", "inf")))
    }
    assert(failure.getMessage.contains("p_set"), failure.getMessage)
    assert(failure.getMessage.contains("tie"), failure.getMessage)
  }

  test("ac-pf-pv's LOPF now matches PyPSA, which is the divergence that found this") {
    assume(available, "goldens missing")
    // The measurement that started it:
    //
    //   noaidi   g_slack [0, 0]    g_pv [120, 145]   objective 2650   Optimal
    //   PyPSA    g_slack [60, 65]  g_pv [60, 80]     objective 7650   Optimal
    //
    // Cheaper by a third with no diagnostic, for as long as the fixture had existed, because
    // `NewtonRaphsonSuite` and `TopologySuite` are its only other readers and neither solves
    // an LP. Asserted here so that it has a reader in this module too.
    val result   = solve(network("ac-pf-pv"))
    val expected = results("ac-pf-pv")("optimize")
    val target   = expected("objective").num
    assertEqualsDouble(result.objective, target, 1e-6 * math.abs(target),
      s"objective ${result.objective} against PyPSA's $target")

    val dispatch = expected("generator_p")
    (0 until 2).foreach { t =>
      Seq("g_slack", "g_pv").foreach { id =>
        assertEqualsDouble(result.dispatch("Generator", id, t), frameValue(dispatch, t, id),
          1e-6, s"$id at $t")
      }
    }
  }
