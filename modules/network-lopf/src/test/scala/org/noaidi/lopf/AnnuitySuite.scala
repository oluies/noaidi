package org.noaidi.lopf

import java.nio.file.Files
import org.noaidi.prima.{PdhgParams, SolveStatus}

/** An `overnight_cost` annuitised over `lifetime` at `discount_rate`, against PyPSA's answer.
  *
  * `periodized_cost` reads `capital_cost` only when `overnight_cost` is NaN -- which is its
  * default -- so this port charged `capital_cost` everywhere and refused the networks that
  * set the other. The refusal was right to exist: read as a per-unit cost, a 1000/MW overnight
  * cost is wrong by the annuity factor, which is a factor of twelve on a 25-year asset at 7%.
  *
  * The annuity factors here are PyPSA's own, printed from `n.c.generators.annuity` on the
  * fixture rather than computed from the formula a second time. That matters for the two
  * branches that are not the formula: a zero rate is `1/n` and an infinite lifetime is
  * `max(r, 0)`, and both are reachable from the schema defaults rather than contrived.
  */
class AnnuitySuite extends munit.FunSuite, CsvFixtures:

  override protected def tempPrefix: String = "noaidi-annuity-"

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)

  private def results(name: String): ujson.Value =
    ujson.read(Files.readString(goldens.resolve("results").resolve(s"$name.json")))

  test("the three annuity branches match PyPSA's own factors") {
    // Measured from `n.c.generators.annuity` on `annuitised-capital`, not re-derived:
    //
    //   r=0.07  n=25     0.08581052   the ordinary case, r / (1 - (1+r)^-n)
    //   r=0.0   n=20     0.05         zero rate, so 1/n -- simple depreciation
    //   r=0.05  n=inf    0.05         a perpetuity, which is max(r, 0)
    //   r=0.06  n=30     0.07264891
    assertEqualsDouble(Expansion.annuity(0.07, 25.0), 0.08581052, 1e-8)
    assertEqualsDouble(Expansion.annuity(0.0, 20.0), 0.05, 1e-12)
    assertEqualsDouble(Expansion.annuity(0.05, Double.PositiveInfinity), 0.05, 1e-12)
    assertEqualsDouble(Expansion.annuity(0.06, 30.0), 0.07264891, 1e-8)
    // The two edges the branches above do not cover, both upstream's: no interest and no end
    // is no annual cost at all, and a negative rate on a perpetual asset clips to zero rather
    // than paying the owner.
    assertEqualsDouble(Expansion.annuity(0.0, Double.PositiveInfinity), 0.0, 1e-12)
    assertEqualsDouble(Expansion.annuity(-0.02, Double.PositiveInfinity), 0.0, 1e-12)
    // And a negative rate on a FINITE lifetime is honoured rather than clipped, which is the
    // asymmetry in `costs.py` -- the clip is inside the infinite-lifetime branch only.
    assert(Expansion.annuity(-0.02, 20.0) < 0.05,
      s"a negative rate should discount below simple depreciation: ${Expansion.annuity(-0.02, 20.0)}")
  }

  test("the annuitised objective and capacities match PyPSA's") {
    assume(available, "goldens missing")
    val result   = Lopf.solve(network("annuitised-capital"), params)
    assertEquals(result.status, SolveStatus.Optimal, s"did not solve: ${result.status}")
    val expected = results("annuitised-capital")("optimize")
    val target   = expected("objective").num

    assertEqualsDouble(result.objective, target, 1e-6 * math.abs(target),
      s"objective ${result.objective} against PyPSA's $target")

    // The capacities, because the objective alone can be right with the merit order wrong:
    // the four extendables are separated by 0.5% of total cost, so a mis-annuitised unit
    // reorders them and moves 50 MW while barely moving the objective.
    val chosen = expected("nominal_opt")("Generator").obj
    Seq("new", "depr", "both", "perp", "old").foreach { id =>
      assertEqualsDouble(result.capacity("Generator", id), chosen(id).num, 1e-4, id)
    }
  }

  test("overnight_cost takes precedence and capital_cost is ignored") {
    assume(available, "goldens missing")
    // `both` sets `capital_cost = 99999` beside its overnight cost, and upstream only WARNS:
    // "when 'overnight_cost' is provided, it takes precedence and capital_cost is ignored".
    // Summed instead, `both` would be the dearest unit on the system rather than the third
    // cheapest, so this is visible in the capacities above as well -- asserted directly here
    // because the coefficient is the thing, and 99999 against 43.59 is not a tolerance away.
    val n = network("annuitised-capital")
    val generators = n.require("Generator")
    assertEqualsDouble(Expansion.periodizedCost(n, generators, "both"), 43.589347 * 0.5, 1e-5)
    assertEqualsDouble(Expansion.periodizedCost(n, generators, "new"), 85.810517 * 0.5, 1e-5)
    // And the unset case still reads `capital_cost`, which is every other golden.
    val meshed = network("ac-dc-meshed")
    val lines  = meshed.require("Line")
    val id     = Expansion.extendables(lines).head
    assertEqualsDouble(Expansion.periodizedCost(meshed, lines, id),
      lines.float("capital_cost", id), 1e-9)
  }

  test("nyears is the objective weightings over 8760") {
    assume(available, "goldens missing")
    // The factor that makes an annuity comparable to a horizon of four snapshots, and the one
    // a reader is most likely to assume is 1. `annuitised-capital` weights its four snapshots
    // at 1095, so its horizon is HALF a year -- chosen that way because a factor of 1.0
    // cannot test a factor: dropping `* nyears` was an equivalent mutant while the fixture
    // modelled a whole year. `ac-dc-meshed`'s ten snapshots at 1.0 model ten hours.
    assertEqualsDouble(Expansion.nyears(network("annuitised-capital")).head, 0.5, 1e-12)
    assertEqualsDouble(Expansion.nyears(network("ac-dc-meshed")).head, 10.0 / 8760.0, 1e-12)
  }

  test("an overnight_cost with no discount_rate is refused") {
    assume(available, "goldens missing")
    // `discount_rate` defaults to NaN, so this is what an `overnight_cost` set on its own
    // reaches. PyPSA's consistency check only warns and its annuity returns NaN, which
    // arrives in the objective as a coefficient -- a wrong answer reported as `Optimal`.
    val n = mutate("annuitised-capital", "generators.csv", setColumn(_, "discount_rate", ""))
    val failure = intercept[Lopf.UnsupportedNetwork](Lopf.build(n))
    assert(failure.getMessage.contains("discount_rate"), failure.getMessage)
  }

  test("an overnight_cost with a non-positive lifetime is refused") {
    assume(available, "goldens missing")
    // `ValueError: lifetime must be positive` upstream. An INFINITE lifetime is fine and is
    // the default -- `perp` relies on it -- so the test is `<= 0` rather than "not finite".
    val n = mutate("annuitised-capital", "generators.csv", setColumn(_, "lifetime", "0"))
    val failure = intercept[Lopf.UnsupportedNetwork](Lopf.build(n))
    assert(failure.getMessage.contains("lifetime"), failure.getMessage)
  }
