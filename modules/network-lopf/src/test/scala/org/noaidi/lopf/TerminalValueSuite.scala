package org.noaidi.lopf

import org.noaidi.network.Network
import org.noaidi.prima.{PdhgParams, Pdhg, RowExpansion, SolveStatus}

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

  /** `terminal-week` with a second reservoir, for the cases that need two. */
  private def twoReservoirs: Network = copiedWith(
    variantDir("terminal-week"),
    "terminal-week",
    "storage_units.csv" ->
      ("name,bus,p_nom,p_min_pu,carrier,spill_cost,marginal_cost," +
        "state_of_charge_initial,max_hours\n" +
        "Z hydro,b,1000.0,0.0,hydro,0.1,1.0,28000.0,40.0\n" +
        "Y hydro,b,1000.0,0.0,hydro,0.1,1.0,28000.0,40.0\n"),
  )

  private def refusal(n: Network, config: TerminalValue.Config): String =
    intercept[Lopf.UnsupportedNetwork](Lopf.build(n, HydroOps.off, config)).getMessage

  test("a lambda that cannot price anything is refused, NaN included") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // The hole commit 7d15d7c closed in HydroOps.inertZones, reopened here one commit
    // later and in the module whose comment cites inertZones as its authority. `isOff`
    // tested `<= 0.0` and `plan` selected `> 0.0`; NaN is neither, so it said "on" and
    // then emitted nothing. Measured before the fix: variables 336 -> 336, rows
    // 112 -> 112, no refusal, and the solve returned the unpriced objective as though
    // the horizon had been priced.
    Seq(Double.NaN, 0.0, -5.0).foreach { bad =>
      val message = refusal(variant("terminal-week"), TerminalValue.Config(lambdaPerUnit = Map(unit -> bad)))
      assert(message.contains("cannot price anything"), s"lambda $bad: $message")
    }
  }

  test("a reservoir with cyclic state of charge is refused") {
    assume(available, "reference/goldens is not present")
    // A cyclic level is a free degree of freedom: add a constant to every state of
    // charge and every balance row still holds, so the LP lifts the level to the cap,
    // fills every segment and collects the whole curve without moving one dispatch.
    // Measured on this fixture before the guard: objective 31592.698 -> 27344.698, a
    // delta of exactly -4248 = -lambda x capacity, every generator identical.
    //
    // NordPSA has no such guard either, so this is a modelling gap shared with upstream
    // rather than a porting error -- but this port refuses other configurations that
    // price nothing, and collecting a reward for free is worse than pricing nothing.
    val message = refusal(
      mutate("storage-cycle", "storage_units.csv", setColumn(_, "carrier", "hydro")),
      TerminalValue.Config(lambdaPerUnit = Map("cyclic" -> 30.0)),
    )
    assert(message.contains("cyclic_state_of_charge"), message)
  }

  test("a StorageUnit that is not a reservoir is refused") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // HydroOps filters on `carrier == hydro`; this did not, while calling every match a
    // "reservoir" in its own refusals. A battery given a lambda was accepted, got five
    // segment columns with negative coefficients, and reduced the objective by up to
    // lambda times its capacity.
    val battery = copiedWith(
      variantDir("terminal-week"),
      "terminal-week",
      "storage_units.csv" ->
        ("name,bus,p_nom,p_min_pu,carrier,spill_cost,marginal_cost," +
          "state_of_charge_initial,max_hours\n" +
          "Z battery,b,1000.0,0.0,battery,0.1,1.0,28000.0,40.0\n"),
    )
    val message = refusal(battery, TerminalValue.Config(lambdaPerUnit = Map("Z battery" -> 50.0)))
    assert(message.contains("not carrier"), message)
  }

  test("a profile override for a reservoir with no lambda is refused") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // Inert in the same way an unmatched zone is, and it hid more: a rising or empty
    // curve behind an unmatched key never reached the concavity check at all, so a
    // one-character slip valued the reservoir along the global curve and returned a
    // plausible number.
    val message = refusal(
      twoReservoirs,
      TerminalValue.Config(
        lambdaPerUnit = Map("Z hydro" -> 30.0),
        profileByUnit = Map("Y hydro" -> IndexedSeq(1.0, 2.0)),
      ),
    )
    assert(message.contains("has no lambda"), message)
  }

  test("a per-unit profile reaches the model, with its own multipliers") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // The accepted path had no test at all: the only case passing `profileByUnit`
    // intercepted the differing-lengths refusal, which fires before any column is
    // allocated. So an implementation reading `config.profile` unconditionally passed
    // every test. Asserted on the coefficients, per reservoir.
    val shared = IndexedSeq(2.0, 1.0)
    val mine   = IndexedSeq(3.0, 0.5)
    val model = Lopf.build(
      twoReservoirs,
      HydroOps.off,
      TerminalValue.Config(
        lambdaPerUnit = Map("Z hydro" -> 10.0, "Y hydro" -> 10.0),
        profile = shared,
        profileByUnit = Map("Y hydro" -> mine),
      ),
    )
    Seq("Z hydro" -> shared, "Y hydro" -> mine).foreach { (id, profile) =>
      profile.zipWithIndex.foreach { (multiplier, k) =>
        val column = model.map.column(TerminalValue.Segment, s"$id#$k", lastSnapshot)
        assertEqualsDouble(model.problem.objective(column), -10.0 * multiplier, 1e-9,
          s"$id segment $k")
      }
    }
  }

  test("an extendable reservoir is named as extendable, not as empty") {
    assume(available, "reference/goldens is not present")
    // PyPSA's expansion idiom is `p_nom = 0, p_nom_extendable = True`, and testing
    // capacity first called that "a reservoir that holds nothing" -- wrong, and the more
    // confusing of the two messages. `storage-hvdc` ships exactly that shape.
    val message = refusal(
      mutate("storage-hvdc", "storage_units.csv", setColumn(_, "carrier", "hydro")),
      TerminalValue.Config(lambdaPerUnit = Map("Storage 0" -> 30.0)),
    )
    assert(message.contains("extendable"), message)
  }

  test("a network with no StorageUnit table is refused by name") {
    assume(available, "reference/goldens is not present")
    // Reachable before with a bare `None.get`, naming neither the network nor the
    // reservoir.
    val message = refusal(network("ac-dc-meshed"), TerminalValue.Config(lambdaPerUnit = Map("x" -> 30.0)))
    assert(message.contains("no StorageUnit table"), message)
  }

  test("a non-finite profile multiplier is named rather than reaching a coefficient") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // Every comparison in the concavity test is false for NaN, so `[1.0, NaN, 2.0]`
    // genuinely rises and the check could not see it. The multiplier then became a NaN
    // objective coefficient that failed hundreds of lines away as "objective coefficient
    // 337 is not finite" -- an anonymous column, in a module whose every other refusal
    // names the reservoir.
    val message = refusal(
      variant("terminal-week"),
      TerminalValue.Config(lambdaPerUnit = Map(unit -> 30.0),
        profile = IndexedSeq(1.0, Double.NaN, 2.0)),
    )
    assert(message.contains("has NaN at segment 1"), message)
  }

  test("the terminal equality keeps the row identity Sclopf depends on") {
    assume(fixtures, "reference/nordpsa terminal fixtures are not present")
    // `build()` sorts equalities before inequalities, so an equality emitted after any
    // inequality gets a standard-form index that differs from its original one --
    // and `Sclopf.build` refuses to proceed unless every row it copies maps
    // `Direct(r)` to the same `r`. Emitted with the other optional families at the end
    // of `Lopf.build`, after the capacity and global-constraint rows, this broke that
    // silently: latent only because `Sclopf` builds with the terminal value off.
    //
    // Asserted here rather than through `Sclopf`, which has no terminal parameter to
    // thread yet -- so this is the invariant it will need when it gets one.
    val model = Lopf.build(
      variant("terminal-week"),
      // HydroOps on as well, so the model definitely contains inequality rows: without
      // any, the ordering cannot be got wrong and the test would pass vacuously.
      HydroOps.Config(maxWeeklyFraction = 0.6),
      TerminalValue.Config(lambdaPerUnit = Map(unit -> 30.0)),
    )
    val translation = model.translation
    val problem     = model.problem
    assert(problem.numConstraints > problem.numEqualities, "no inequality rows -- weak test")

    // The terminal value has to have emitted something, or this whole test is about a
    // model that never had a segment in it. That is not hypothetical: it is exactly the
    // state commit 7d15d7c found, where the config said "on" and emission selected
    // nothing, leaving the model byte-identical at 336 variables and 112 rows -- and
    // under that regression the scan below would have been green.
    val plain = Lopf.build(variant("terminal-week"), HydroOps.Config(maxWeeklyFraction = 0.6))
    assert(
      problem.numVariables > plain.problem.numVariables,
      "no segment columns were added, so there is no terminal equality to misindex",
    )
    // The segment column has to be findable by name, which is what `LopfResult` reads a
    // solution back through. There is no separate column count to compare against any
    // more -- `VariableMap` stopped carrying one, so nothing can under-report it.
    model.map.column(TerminalValue.Segment, s"$unit#0", lastSnapshot): Unit

    // Sclopf's own condition, not a weaker one: it accepts `Direct(r)` and `Negated(r)`
    // and rejects everything else, `Range` included. Flagging only a misindexed `Direct`
    // let a misindexed `Negated` or a two-sided `Range` through.
    val misindexed = (0 until translation.numOriginalRows).filterNot { r =>
      translation.expansionOf(r) match
        case RowExpansion.Direct(row)  => row == r
        case RowExpansion.Negated(row) => row == r
        case _                         => false
    }
    assertEquals(
      misindexed.toList,
      Nil,
      "a row does not map to the standard-form row of the same index, so Sclopf would refuse it",
    )
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
    // Asserts the reason that is true, not the one the message used to give. It said the
    // LP would "fill the segments in the wrong order", which it cannot -- the segments
    // are interchangeable. The refusal is about `profile(k)` meaning the k-th fill band,
    // and about both sides agreeing which configurations are legal.
    assert(
      refused.getMessage.contains("k-th fill band") &&
        refused.getMessage.contains("NordPSA refuses it too"),
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
