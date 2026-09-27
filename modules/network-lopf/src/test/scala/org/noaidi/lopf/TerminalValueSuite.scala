package org.noaidi.lopf

import org.noaidi.prima.{PdhgParams, Pdhg, SolveStatus}

/** [[TerminalValue]] against NordPSA's own `hydro_terminal_value` callback.
  *
  * The fixture is a '''non-cyclic''' week starting 70% full, and both halves of that
  * matter. Non-cyclic because a cyclic window ties the terminal level to the initial
  * one, so the draining a terminal value exists to prevent cannot happen and every
  * profile prices the same water. Starting part-full because an empty reservoir has
  * nothing to hold back.
  *
  * ==Why the reservoir is small==
  *
  * 40 `max_hours`, not 200. The water has to be '''scarce''' for λ to compete with
  * anything: the week's residual demand has to exceed the starting level plus inflow,
  * so every MWh held at T displaces nothing and forgoes 40 − 1 = 39 EUR/MWh of the
  * expensive generator. That 39 is the number each segment's `λ_k` is measured
  * against.
  *
  * The first version of this fixture had 200 `max_hours`. The reservoir then held more
  * water than the week could use, every profile ended at the same level, and the
  * comparison proved nothing while passing.
  *
  * ==Two λ, in opposite directions==
  *
  * One λ cannot show the curve is concave rather than merely scaled, so there are two.
  * At 25 the whole linear curve sits under the 39 and holds nothing — exactly like the
  * control — while the concave curve's first segment (2.0 × 25 = 50) clears it and
  * holds precisely one fifth of capacity. At 45 both hold and the concave one holds
  * '''less''', because its cheapest segment (0.2 × 45 = 9) is not worth keeping.
  *
  * A port that got the reward right and the shape wrong passes neither.
  */
class TerminalValueSuite extends munit.FunSuite, NordPsaFixtures:

  override protected def tempPrefix: String = "noaidi-terminal-"

  private lazy val fixtures: Boolean      = hasReference("terminal.json")
  private lazy val reference: ujson.Value = referenceJson("terminal.json")

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)

  private def stored(name: String): ujson.Value = reference("cases")(name)
  private def unit: String  = reference("shape")("unit").str
  private def capacity: Double = reference("shape")("capacity_mwh").num
  private def lastSnapshot: Int = reference("shape")("snapshots").num.toInt - 1

  /** The config for one stored case, built from the numbers the generator wrote. */
  private def configOf(name: String): TerminalValue.Config =
    val c = stored(name)
    if c("profile").isNull then TerminalValue.off
    else
      TerminalValue.Config(
        lambdaPerUnit = Map(unit -> c("lambda").num),
        profile = c("profile").arr.map(_.num).toIndexedSeq,
      )

  private def solve(name: String): LopfResult =
    Lopf.solve(variant("terminal-week"), HydroOps.off, configOf(name), Pdhg.Solver(params))

  /** Assert this port reaches PyPSA's objective and terminal level on one case. */
  private def agrees(name: String): LopfResult =
    val result = solve(name)
    assertEquals(result.status, SolveStatus.Optimal, s"$name did not solve")
    val objective = stored(name)("objective").num
    assertEqualsDouble(
      result.objective,
      objective,
      1e-6 * math.max(1.0, math.abs(objective)),
      s"$name objective disagrees with PyPSA",
    )
    val level = stored(name)("soc_last").num
    assertEqualsDouble(
      result.stateOfCharge(unit, lastSnapshot),
      level,
      1e-6 * math.max(1.0, math.abs(level)) + 1e-3,
      s"$name terminal level disagrees with PyPSA",
    )
    result

  test("a window with nothing pricing its end drains the reservoir") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    assert(reference("control_drains").bool, "the control did not drain -- weak fixture")
    val control = agrees("none")
    assert(
      control.stateOfCharge(unit, lastSnapshot) < 0.01 * capacity,
      "this port left water at the end of an unpriced window",
    )
  }

  test("a linear value below the opportunity cost holds nothing") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // The control for the concave case below, and the reason it is evidence about the
    // curve rather than about the reward: at this lambda a one-segment value is worth
    // less than the generation it displaces, so it changes nothing.
    assert(reference("linear_holds_nothing_at_25").bool, "linear held water at lambda 25")
    agrees("linear-25")
  }

  test("a concave value holds one segment where a linear one holds none") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    assert(reference("concave_holds_a_segment_at_25").bool, "concave held nothing either")
    val concave = agrees("concave-25")
    val linear  = agrees("linear-25")
    assert(
      concave.stateOfCharge(unit, lastSnapshot) > linear.stateOfCharge(unit, lastSnapshot) + 1.0,
      "the concave curve held no more than the linear one",
    )
    // One fifth of capacity, because the default profile has five segments and only its
    // first is worth more than the 39 EUR/MWh it forgoes. This is the assertion a port
    // that summed the profile, or applied the wrong segment's multiplier, would fail.
    assertEqualsDouble(
      concave.stateOfCharge(unit, lastSnapshot),
      capacity / reference("default_profile").arr.length,
      1.0,
      "the concave curve did not hold exactly one segment",
    )
  }

  test("at a higher lambda the concave value holds less than the linear one") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // The opposite direction. Both hold now, and the concave one holds less because its
    // cheapest segment is not worth keeping -- so a port that had the shape inverted
    // fails here even though it passed the case above.
    assert(reference("concave_holds_less_than_linear_at_45").bool, "not less -- weak fixture")
    val linear  = agrees("linear-45")
    val concave = agrees("concave-45")
    assert(
      concave.stateOfCharge(unit, lastSnapshot) < linear.stateOfCharge(unit, lastSnapshot) - 1.0,
      "the concave curve did not hold less than the linear one at the higher lambda",
    )
  }

  test("a flat multi-segment profile is accepted and matches the single-segment one") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // Concave to within the 1e-9 tolerance NordPSA uses, so refusing it would refuse a
    // perfectly good value. It must also agree with the one-segment form exactly, since
    // cutting a straight line into two pieces changes nothing.
    val flat   = agrees("flat-two-45")
    val linear = agrees("linear-45")
    assertEqualsDouble(flat.objective, linear.objective, 1e-6 * math.abs(linear.objective))
  }

  test("segment k carries profile(k)'s multiplier, in order") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // Pins the documented correspondence, and it needs pinning at the coefficient
    // rather than through behaviour, because behaviour cannot see it.
    //
    // The segments are interchangeable -- same width, same bounds, tied only through
    // their sum -- so the optimum depends on the multiset of multipliers and not on
    // their order. Reversing the profile inside the allocation changes no objective and
    // no level, and no legal input can expose that: the reverse of a non-increasing
    // profile is either the same profile or a rising one, and rising is refused. So
    // there is no behavioural test to write here, and claiming one would be the vacuous
    // kind.
    //
    // What the documentation does promise is that `profile(k)` is the k-th fill band
    // read empty-end first. That is a statement about the coefficients, so it is
    // asserted about the coefficients.
    val profile = stored("concave-45")("profile").arr.map(_.num).toIndexedSeq
    val lambda  = stored("concave-45")("lambda").num
    val model = Lopf.build(
      variant("terminal-week"),
      HydroOps.off,
      TerminalValue.Config(lambdaPerUnit = Map(unit -> lambda), profile = profile),
    )
    profile.zipWithIndex.foreach { (multiplier, k) =>
      val column = model.map.column(TerminalValue.Segment, s"$unit#$k", lastSnapshot)
      assertEqualsDouble(
        model.problem.objective(column),
        -lambda * multiplier,
        1e-9,
        s"segment $k does not carry profile($k) = $multiplier",
      )
    }
  }

  test("a rising profile is refused rather than solved") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // NordPSA raises on this too -- `reference("refused")` records that it does, so the
    // two sides refuse the same input rather than this port inventing a rule.
    assert(reference("refused")("rising")("refused").bool, "NordPSA accepted a rising profile")
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(
        variant("terminal-week"),
        HydroOps.off,
        TerminalValue.Config(lambdaPerUnit = Map(unit -> 30.0), profile = IndexedSeq(1.0, 2.0)),
      )
    }
    assert(
      refused.getMessage.contains("not concave"),
      s"the refusal does not say why: ${refused.getMessage}",
    )
  }

  test("an empty profile is refused") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    assert(reference("refused")("empty")("refused").bool, "NordPSA accepted an empty profile")
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(
        variant("terminal-week"),
        HydroOps.off,
        TerminalValue.Config(lambdaPerUnit = Map(unit -> 30.0), profile = IndexedSeq.empty),
      )
    }
    assert(refused.getMessage.contains("no segments"), refused.getMessage)
  }

  test("a lambda against a reservoir that is not there is refused") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // The same argument as `HydroOps.inertZones`: a value configured against a name
    // that matches nothing leaves the horizon unpriced while the run reports a number.
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(
        variant("terminal-week"),
        HydroOps.off,
        TerminalValue.Config(lambdaPerUnit = Map("NO2 hydro" -> 30.0)),
      )
    }
    assert(refused.getMessage.contains("NO2 hydro"), refused.getMessage)
  }

  test("profiles of differing lengths are refused") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // The segment count is an axis of the model, so every reservoir shares it -- which
    // takes two reservoirs to test. With one, `profileByUnit` simply overrides the
    // global profile and the lengths cannot differ; written that way first, this case
    // passed while exercising nothing.
    val twoUnits = copiedWith(
      variantDir("terminal-week"),
      "terminal-week",
      "storage_units.csv" ->
        ("name,bus,carrier,p_nom,max_hours,marginal_cost,cyclic_state_of_charge," +
          "state_of_charge_initial,p_min_pu,spill_cost\n" +
          "Z hydro,b,hydro,1000.0,40.0,1.0,False,28000.0,0.0,0.1\n" +
          "Y hydro,b,hydro,1000.0,40.0,1.0,False,28000.0,0.0,0.1\n"),
    )
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(
        twoUnits,
        HydroOps.off,
        TerminalValue.Config(
          lambdaPerUnit = Map("Z hydro" -> 30.0, "Y hydro" -> 30.0),
          profile = IndexedSeq(1.0),
          profileByUnit = Map("Y hydro" -> IndexedSeq(2.0, 1.0, 0.5)),
        ),
      )
    }
    assert(refused.getMessage.contains("differing lengths"), refused.getMessage)
  }
