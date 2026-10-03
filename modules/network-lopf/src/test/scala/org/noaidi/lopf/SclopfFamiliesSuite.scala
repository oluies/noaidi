package org.noaidi.lopf

import org.noaidi.network.Network
import org.noaidi.prima.{PdhgParams, Pdhg, RowExpansion, SolveStatus}

/** The NordPSA families applied to a '''security-constrained''' dispatch.
  *
  * ==Why this suite exists at all==
  *
  * Each of the four families has its own fixture, and each of their suites asserts one
  * invariant it cannot itself exercise: that every row the family emits keeps the
  * standard-form index its original row had. [[Sclopf.build]] rebuilds the base model row by
  * row and is the only thing in this code base that depends on that. Until the families could
  * be passed to it, the invariant had three assertions and '''no consumer''' — three tests
  * asserting a property about a code path nothing reached.
  *
  * Two of the families, [[TerminalValue]] and [[BidLadder]], emit an '''equality''', which has
  * to land before the first inequality `Lopf.build` emits or every one of its rows takes an
  * index that differs from its original. That defect has appeared three times here, once in
  * the commit that removed the previous instance. This is the path that would have caught it.
  *
  * ==What makes the fixture hard to build==
  *
  * It is one network, and the requirement on it is stricter than for any single family: the
  * security rows have to bind, each family has to bind, '''and''' the two together have to
  * differ from either alone. A setting where the family makes the security rows slack proves
  * only that rows can be added to a model — and several of the settings swept for these cases
  * did exactly that. A tight weekly hydro ceiling is one: it binds hard enough on its own
  * that the contingency limits stop mattering, and plain and secure return the same number.
  * `generate_sclopf.py` measures all three conditions per case and this suite reads them.
  *
  * The rating is 150 on every line and the value was swept for: at 200 the security rows are
  * slack, at 130 the secure problem is so tight nothing can move it.
  *
  * ==Which series are asserted==
  *
  * Every '''secure''' case turns out to be fully determined — the contingency limits pin the
  * flow pattern — while every '''plain''' case but one is not: with 300 MW of gas available at
  * a flat 40 EUR/MWh in every hour, moving the reservoir's water between hours costs nothing,
  * so dispatch, flow and state of charge are all tie-breaks. So the plain cases are asserted
  * on the objective and the secure ones on everything, which is the direction that happens to
  * suit: the secure answer is the one this suite is about.
  */
class SclopfFamiliesSuite extends munit.FunSuite, NordPsaFixtures:

  override protected def tempPrefix: String = "noaidi-sclopf-families-"

  private lazy val fixtures: Boolean      = hasReference("sclopf.json")
  private lazy val reference: ujson.Value = referenceJson("sclopf.json")

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)
  private def solver = Pdhg.Solver(params)

  private def stored(name: String): ujson.Value = reference("cases")(name)
  private def controls: ujson.Value             = reference("controls")
  private def unit: String                      = reference("shape")("unit").str
  private def network: Network                  = variant(reference("network").str)

  /** One case's families, rebuilt from the dicts the generator wrote.
    *
    * Read from the file for the reason every NordPSA suite reads its config from the file: a
    * callback configuration written on both sides is one that drifts, and the drift shows up
    * as agreement with a PyPSA run that answered a different question. This one has four
    * families to keep in step rather than one.
    */
  private def familiesOf(name: String): Lopf.Families =
    val c = stored(name)("config").obj
    Lopf.Families(
      hydro = c.get("hydro").fold(HydroOps.off) { h =>
        HydroOps.Config(
          minHourlyFraction = h("min_hourly_frac").num,
          minDailyFraction = h("min_daily_frac").num,
          maxWeeklyFraction = h("max_weekly_frac").num,
        )
      },
      terminal = c.get("terminal").fold(TerminalValue.off) { t =>
        TerminalValue.Config(
          lambdaPerUnit = Map(unit -> t("lambda").num),
          profile = t("profile").arr.map(_.num).toIndexedSeq,
        )
      },
      ladder = c.get("ladder").fold(BidLadder.off) { l =>
        BidLadder.Config(tiers = l("tiers").num.toInt, width = l("width").num)
      },
      stability = c.get("stability").fold(Stability.off) { s =>
        val base = stabilityZoneData(reference)
        base.copy(
          systemInertiaGws = s.obj.get("ek_system_gws").filterNot(_.isNull)
            .map(_.num).getOrElse(0.0),
          zoneInertiaFloorGws = s.obj.get("floors")
            .map(_.obj.map((z, v) => z -> v.num).toMap).getOrElse(Map.empty),
          scrMin = s.obj.get("scr_min").filterNot(_.isNull).map(_.num).getOrElse(0.0),
          scrExempt = s.obj.get("exempt").map(_.arr.map(_.str).toSet).getOrElse(base.scrExempt),
        )
      },
    )

  private def solveCase(name: String, secure: Boolean): LopfResult =
    val families = familiesOf(name)
    if secure then Sclopf.solve(network, None, families, solver)
    else Lopf.solve(network, families, solver)

  /** Assert this port reaches PyPSA's answer on one half of one case. */
  private def agrees(name: String, secure: Boolean): LopfResult =
    val key    = if secure then "secure" else "plain"
    val target = stored(name)(key)
    val result = solveCase(name, secure)
    assertEquals(result.status, SolveStatus.Optimal, s"$name/$key did not solve")
    val objective = target("objective").num
    assertEqualsDouble(result.objective, objective,
      1e-6 * math.max(1.0, math.abs(objective)),
      s"$name/$key objective disagrees with PyPSA")

    val determined = target("determined")
    assert(determined("probed").bool, s"$name/$key was never probed for determinacy")
    assert(determined("same_objective").bool,
      s"$name/$key: the two PyPSA solves disagreed about the cost, so the fixture is unsound")

    val snapshots = network.snapshots.indices
    def when(block: String)(body: => Unit): Unit =
      if determined.obj.get(block).exists(v => v.boolOpt.getOrElse(false)) then body

    target("dispatch").obj.foreach { (id, theirs) =>
      if determined("dispatch").obj.get(id).exists(_.bool) then
        theirs.arr.map(_.num).zipWithIndex.foreach { (value, t) =>
          assertEqualsDouble(result.dispatch("Generator", id, t), value,
            1e-4 * math.max(1.0, math.abs(value)),
            s"$name/$key: $id's dispatch at snapshot $t disagrees with PyPSA")
        }
    }
    // The line flows, which are what the security rows are about: a port that reproduced the
    // objective through a different flow pattern would have got the outage factors wrong.
    target("flow").obj.foreach { (id, theirs) =>
      if determined("flow").obj.get(id).exists(_.bool) then
        theirs.arr.map(_.num).zipWithIndex.foreach { (value, t) =>
          assertEqualsDouble(result.dispatch("Line", id, t), value,
            1e-4 * math.max(1.0, math.abs(value)),
            s"$name/$key: line $id's flow at snapshot $t disagrees with PyPSA")
        }
    }
    when("discharging") {
      target("discharging").arr.map(_.num).zipWithIndex.foreach { (value, t) =>
        assertEqualsDouble(result.discharging(unit, t), value,
          1e-4 * math.max(1.0, math.abs(value)),
          s"$name/$key: the reservoir's output at snapshot $t disagrees with PyPSA")
      }
    }
    when("state_of_charge") {
      target("state_of_charge").arr.map(_.num).zipWithIndex.foreach { (value, t) =>
        assertEqualsDouble(result.stateOfCharge(unit, t), value,
          1e-4 * math.max(1.0, math.abs(value)) + 1e-3,
          s"$name/$key: the reservoir's level at snapshot $t disagrees with PyPSA")
      }
    }
    result

  /** Both halves of one case, plus the three conditions that make it evidence.
    *
    * The conditions are read from the file rather than recomputed, because they are
    * properties of the '''PyPSA''' answers: whether the family and the security rows each
    * bind is a fact about the fixture, and asserting it from this port's own numbers would
    * let a port that reproduced neither agree with itself.
    */
  private def bothWays(name: String): (LopfResult, LopfResult) =
    assert(controls("security_binds").bool,
      "the security rows do not bind on this network -- the whole fixture is about a " +
        "dispatch that is not security-constrained")
    assert(controls("family_changes_the_secure_answer")(name).bool,
      s"$name does not change the secure answer, so it says nothing about reaching " +
        "the security-constrained model")
    assert(controls("security_still_binds_with_family")(name).bool,
      s"$name makes the security rows slack, so this case would prove only that rows can " +
        "be added to a model")
    assert(controls("combination_differs_from_either")(name).bool,
      s"$name and the security rows do not combine into something different from either")
    (agrees(name, secure = false), agrees(name, secure = true))

  test("the control: security alone, with no family") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // What every case below is measured against, and the one place the security rows are
    // asserted on their own against this fixture. 336,000 plain, 448,034 secure.
    assert(controls("security_binds").bool, "the security rows do not bind -- weak fixture")
    val plain  = agrees("none", secure = false)
    val secure = agrees("none", secure = true)
    assert(secure.objective > plain.objective + 1.0,
      s"secure dispatch cost no more than free dispatch: ${plain.objective} -> " +
        s"${secure.objective}")
  }

  test("reservoir operating limits under N-1") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // An hourly floor rather than a weekly ceiling, and the choice is the fixture's whole
    // difficulty: a ceiling tight enough to bind makes the contingency limits slack, so
    // plain and secure return the same number and the case is about neither constraint.
    bothWays("hydro"): Unit
  }

  test("a terminal water value under N-1, which only binds once security holds water back") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // The interaction worth having. Under the plain dispatch the reservoir drains to zero, so
    // a value on the level at T is worth nothing and changes no answer; the security rows
    // leave water in it, and then the value is worth something. A port where the two families
    // did not compose would show up here and nowhere else.
    //
    // This is also the first of the two families that adds COLUMNS and an EQUALITY, which is
    // what the row-by-row copy in `Sclopf.build` is sensitive to.
    val (plain, secure) = bothWays("terminal-linear")
    assertEqualsDouble(plain.objective, stored("none")("plain")("objective").num,
      1.0, "the terminal value changed the plain answer, so the premise of this case is gone")
    val control = stored("none")("secure")("objective").num
    assert(math.abs(secure.objective - control) > 1.0,
      s"the terminal value changed nothing under security either: $control")
  }

  test("a concave terminal value under N-1") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // Five segments rather than one, so the copy carries five times as many columns and the
    // same equality per reservoir. Lambda 10 and not 20: at 20 the curve holds so much water
    // that the security rows go slack.
    bothWays("terminal-concave"): Unit
  }

  test("a bid ladder under N-1") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // The heaviest of the four for the row copy: columns and an equality per reservoir per
    // snapshot, so 168 extra columns and 56 extra equalities on a 56-snapshot horizon, every
    // one of which has to keep its index through the rebuild.
    bothWays("ladder"): Unit
  }

  test("a grid-strength floor under N-1") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // Inequalities rather than an equality, and it reaches the wind: the floor is measured
    // against the converters' actual infeed, so satisfying it and satisfying the contingency
    // limits pull on the same curtailment decision.
    bothWays("stability-scr"): Unit
  }

  test("a rotational-energy floor under N-1") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // The other stability family, which forces synchronous plant online and so changes where
    // the power comes from -- which is exactly what the security rows also decide.
    bothWays("stability-ek"): Unit
  }

  test("two families and security at once") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // The arrangement where a copy that got one family's row ordering right and another's
    // wrong shows up: neither family alone would reveal it, because each is internally
    // consistent about its own indices.
    bothWays("hydro-and-stability"): Unit
  }

  test("three families and security at once, including both that emit an equality") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // `TerminalValue` and `BidLadder` both emit an equality, and `HydroOps` emits
    // inequalities between them in `Lopf.build`'s ordering. If any of the three landed on the
    // wrong side of the equality/inequality split, this is the case where the rebuilt model
    // misindexes a row -- and `Sclopf.build` refuses rather than returning a wrong number,
    // which is the outcome worth having.
    bothWays("all-three"): Unit
  }

  test("all four families and security at once") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // The arrangement nothing covered. `all-three` leaves `stability` out, and `stability`
    // is the family `Lopf.build` emits LAST and the only one that emits inequalities
    // exclusively -- so it sits on the far side of the equality/inequality split from the
    // other three. A copy that got the three equality-emitting families right and put
    // stability's rows on the wrong side would pass `all-three` and every single-family
    // case, which is precisely the hole the four-family case closes.
    //
    // It was written down as a known gap -- "nothing tests all four families together,
    // that's where the next instance of the row-ordering defect will hide" -- and left as a
    // note rather than a test. The defect has appeared four times in this code base, once
    // in the commit that removed the previous instance, so a note was not enough.
    bothWays("all-four"): Unit
  }

  test("the four-family model's rows survive the rebuild too, stability's included") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // The invariant asserted above for `all-three`, on the case that actually has every
    // family's rows in it. Two things here that the `all-three` version cannot see: that
    // stability's columns are copied, and that adding a family which emits *only*
    // inequalities does not move any equality's index.
    val three = Lopf.build(network, familiesOf("all-three"))
    val four  = Lopf.build(network, familiesOf("all-four"))
    val secure = Sclopf.build(network, None, familiesOf("all-four"))

    assert(four.problem.numVariables > three.problem.numVariables,
      "the fourth family added no columns, so `all-four` is `all-three` under another name")
    assert(four.problem.numConstraints > three.problem.numConstraints,
      "the fourth family added no rows")
    // Stability emits inequalities only, so the equality count must be untouched by it.
    // This is the half of the ordering invariant that is about the *split* rather than
    // about an index, and it is checkable only by holding two models side by side.
    assertEquals(four.problem.numEqualities, three.problem.numEqualities,
      "adding stability changed the equality count, so one of its rows is an equality")

    assert(secure.problem.numConstraints > four.problem.numConstraints,
      "no security rows were added, so there was no rebuild to survive")
    assertEquals(secure.problem.numVariables, four.problem.numVariables,
      "the rebuild changed the column count, so a family's columns were not copied")
    assertEquals(secure.problem.numEqualities, four.problem.numEqualities,
      "the rebuild changed the equality count")

    val misindexed = (0 until four.translation.numOriginalRows).filterNot { r =>
      four.translation.expansionOf(r) match
        case RowExpansion.Direct(row)  => row == r
        case RowExpansion.Negated(row) => row == r
        case _                         => false
    }
    assertEquals(misindexed.toList, Nil,
      "a base row does not map to the standard-form row of the same index")

    network.table("Bus").foreach { buses =>
      buses.ids.foreach { bus =>
        network.snapshots.indices.foreach { t =>
          val row = four.map.balanceRows((bus, t))
          assert(row < secure.problem.numEqualities,
            s"$bus's balance row at snapshot $t is no longer an equality after the rebuild")
        }
      }
    }
  }

  // ------------------------------------------------------------------- the invariant itself

  test("every family's rows survive the row-by-row rebuild with their index intact") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // What the three families' own suites assert about a model they build themselves, now
    // asserted about the model `Sclopf.build` actually rebuilds. Two things are checked that
    // those suites cannot: that the rebuild preserves the count, and that the base model's
    // equalities are still all of the leading rows afterwards.
    val families = familiesOf("all-three")
    val base     = Lopf.build(network, families)
    val secure   = Sclopf.build(network, None, families)

    assert(secure.problem.numConstraints > base.problem.numConstraints,
      "no security rows were added, so there was no rebuild to survive")
    assertEquals(secure.problem.numVariables, base.problem.numVariables,
      "the rebuild changed the column count, so the families' columns were not copied")
    assertEquals(secure.problem.numEqualities, base.problem.numEqualities,
      "the rebuild changed the equality count, so a family's equality became an inequality " +
        "or the reverse")

    // The base model's own condition, on the base model: every original row maps to the
    // standard-form row of the same index. `Sclopf.build` checks this and refuses, so a
    // failure here would already have thrown -- which is the point. Restated so the
    // assertion is visible rather than implied by the absence of an exception.
    val misindexed = (0 until base.translation.numOriginalRows).filterNot { r =>
      base.translation.expansionOf(r) match
        case RowExpansion.Direct(row)  => row == r
        case RowExpansion.Negated(row) => row == r
        case _                         => false
    }
    assertEquals(misindexed.toList, Nil,
      "a base row does not map to the standard-form row of the same index")

    // And the balance rows, which `LopfResult.marginalPrice` reads through: their indices were
    // assigned in the base builder's numbering and are reused verbatim by the rebuild.
    // Nothing in the type system says they still line up.
    network.table("Bus").foreach { buses =>
      buses.ids.foreach { bus =>
        network.snapshots.indices.foreach { t =>
          val row = base.map.balanceRows((bus, t))
          assert(row < secure.problem.numEqualities,
            s"$bus's balance row at snapshot $t is no longer an equality after the rebuild")
        }
      }
    }
  }

  test("a family that makes the security rows slack is caught by the fixture, not by luck") {
    assume(fixtures, "reference/nordpsa sclopf fixtures are not present")
    // Not an assertion about the port. It records the shape of fixture that would make every
    // test above vacuous, because that shape is what the first attempt at this fixture had:
    // a weekly hydro ceiling of 0.30, under which plain and secure both cost 571,200 and the
    // contingency limits were doing nothing at all.
    //
    // Reproduced here so a later reader who reaches for a tighter limit finds the measurement
    // rather than rediscovering it.
    val ceiling = Lopf.Families(hydro = HydroOps.Config(maxWeeklyFraction = 0.30))
    val plain   = Lopf.solve(network, ceiling, solver)
    val secure  = Sclopf.solve(network, None, ceiling, solver)
    assertEquals(plain.status, SolveStatus.Optimal)
    assertEquals(secure.status, SolveStatus.Optimal)
    assertEqualsDouble(secure.objective, plain.objective,
      1e-6 * math.abs(plain.objective),
      "a weekly ceiling of 0.30 no longer makes the security rows slack, so the warning " +
        "this test carries is out of date and the case could be used as evidence",
    )
  }
