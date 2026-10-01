package org.noaidi.lopf

import java.nio.file.Files
import org.noaidi.network.Network
import org.noaidi.prima.{PdhgParams, Pdhg, RowExpansion, SolveStatus}

/** Global constraints beyond a CO2 cap: the other two types, and all three senses.
  *
  * `ac-dc-co2` already covers `primary_energy` at `<=`. These three fixtures cover the two
  * transmission expansion limits and the two senses that were refused until now.
  *
  * ==Why the fixtures are what they are==
  *
  * All three are `ac-dc-meshed` with one constraint added — and with '''lengths''', which
  * the PyPSA example ships at zero. A volume limit over zero-length branches is the row
  * `0 <= constant`: satisfied by anything, and indistinguishable from no row at all. That is
  * the same trap the NordPSA fixtures kept falling into, in a new place.
  *
  * They also inherit `ac-dc-meshed`'s own `co2_limit`, so each carries '''two''' global
  * constraints of different types. That was not planned and is worth keeping: a builder that
  * dispatched on `type` once and applied the answer to every row would pass a
  * single-constraint fixture.
  *
  * ==Why capacities and not just objectives==
  *
  * The `<=` volume cap moves the objective by 56%, which any comparison catches. The other
  * two do not: forcing '''more''' transmission costs only `capital_cost` per MW, and those
  * run 0.009–0.2 against an objective dominated by a −3.47M sunk-capital term, so `>=` at
  * eight times the free optimum moves it by 1.2e-3 and `==` at twice by 8e-6 — the latter
  * within sight of the 1e-6 the suites compare at.
  *
  * The '''capacities''' are not close at all. At the same constant, `==` builds 1,266,000 of
  * length-weighted capacity and `<=` builds 633,340, because `<=` is simply slack there. So
  * the sense is asserted where it is visible.
  */
class GlobalConstraintSuite extends munit.FunSuite, CsvFixtures:

  override protected def tempPrefix: String = "noaidi-globalconstraint-"

  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)

  private def results(name: String): ujson.Value =
    ujson.read(Files.readString(goldens.resolve("results").resolve(s"$name.json")))

  /** The lengths `generate_goldens.py` gives the lines, in file order. */
  private val lengths = IndexedSeq(120.0, 80.0, 200.0, 150.0, 60.0, 90.0, 110.0)

  /** Assert this port reaches PyPSA's objective, and its capacities where they are decided.
    *
    * `capacities = false` for `ac-dc-txcost`, and the reason is worth stating rather than
    * leaving as a flag. A cost floor weights the constraint by the same `capital_cost` the
    * objective charges, so every line trades one unit of cost for one unit of constraint and
    * the LP is indifferent to which it builds. Measured: HiGHS simplex puts 21,982 on line 6
    * and its interior point puts 76,382 on line 2, agreeing on the objective to 1e-8 and on
    * the cost-weighted total to four decimals.
    *
    * So that fixture is asserted on the objective and on the constraint total, both of which
    * are pinned, and not on a split that is a solver's tie-break.
    */
  private def agrees(name: String, capacities: Boolean = true): LopfResult =
    val n        = network(name)
    val expected = results(name)("optimize")
    assert(!expected.obj.contains("error"), s"golden solve failed: $name")
    val result = Lopf.solve(n, params)
    assertEquals(result.status, SolveStatus.Optimal, s"$name did not solve")
    assertEqualsDouble(
      result.objective,
      expected("objective").num,
      1e-6 * math.abs(expected("objective").num),
      s"$name objective",
    )
    if capacities then
      var compared = 0
      expected("nominal_opt").obj.foreach { (component, chosen) =>
        n.table(component).foreach { table =>
          chosen.obj.foreach { (id, value) =>
            assertEqualsDouble(result.capacity(component, id), value.num,
              1e-4 * math.max(1.0, math.abs(value.num)), s"$name: $component '$id' capacity")
            compared += 1
          }
        }
      }
      assert(compared > 0, s"$name compared no capacities, so the assertion above is vacuous")
    result

  /** The left-hand side of a transmission limit, recomputed from the solved capacities. */
  private def transmissionTotal(n: Network, result: LopfResult, weight: String): Double =
    val lines = n.require("Line")
    lines.ids.zipWithIndex.map { (id, i) =>
      val w = if weight == "length" then lengths(i) else lines.float("capital_cost", id)
      w * result.capacity("Line", id)
    }.sum

  private def constantOf(n: Network, id: String): Double =
    n.require("GlobalConstraint").float("constant", id)

  test("a transmission volume cap binds, and the port reaches PyPSA's answer") {
    assume(available, "goldens missing")
    // The strong one: 60% of what the network would build freely, which costs it 56% of the
    // objective. `ac-dc-co2`'s argument applies -- a limit that merely touched the optimum
    // would be reproduced exactly by an implementation that never built the row.
    val n      = network("ac-dc-txvolume")
    val result = agrees("ac-dc-txvolume")
    val free   = Lopf.solve(network("ac-dc-meshed"), params)
    assert(result.objective > free.objective + 1.0,
      s"the cap cost nothing: ${free.objective} -> ${result.objective}")

    // And it binds at its constant rather than merely being satisfied.
    val cap = constantOf(n, "tx_limit")
    assertEqualsDouble(transmissionTotal(n, result, "length"), cap, 1e-4 * cap,
      "the volume cap is not tight, so it did not decide anything")
  }

  test("a transmission cost floor binds, and the weight is capital_cost not length") {
    assume(available, "goldens missing")
    // The other left-hand side and the other sense at once. The two types differ in that
    // attribute and nothing else, so a builder that used `length` for both reaches a
    // different total -- which this catches even though the objective barely moves.
    val n      = network("ac-dc-txcost")
    val result = agrees("ac-dc-txcost", capacities = false)
    val floor  = constantOf(n, "tx_limit")
    assertEqualsDouble(transmissionTotal(n, result, "capital_cost"), floor, 1e-4 * floor,
      "the cost floor is not tight")
    // Weighted by length instead, the same solution is nowhere near the constant. Stated as
    // an assertion so that a port reading the wrong attribute fails here rather than
    // drifting past on a loose objective.
    assert(math.abs(transmissionTotal(n, result, "length") - floor) > floor,
      "length- and cost-weighted totals are too close for this fixture to tell them apart")
  }

  test("an equality sense is not an inequality, and the capacities are what show it") {
    assume(available, "goldens missing")
    // The constant sits ABOVE the free optimum, which is the only way the two senses can
    // disagree: below it a binding cap sits exactly on its constant either way, and a port
    // that read `==` as `<=` would pass.
    val n      = network("ac-dc-txvolume-exact")
    val result = agrees("ac-dc-txvolume-exact")
    val exact  = constantOf(n, "tx_limit")
    assertEqualsDouble(transmissionTotal(n, result, "length"), exact, 1e-4 * exact,
      "the equality did not force the built volume onto its constant")

    // What `<=` would have given at the same constant: the free optimum, roughly half.
    val free = Lopf.solve(network("ac-dc-meshed"), params)
    val freeTotal = network("ac-dc-meshed").require("Line").ids.zipWithIndex.map { (id, i) =>
      lengths(i) * free.capacity("Line", id)
    }.sum
    assert(freeTotal < 0.75 * exact,
      s"the free optimum ($freeTotal) is not far enough below the constant ($exact) for " +
        "this fixture to distinguish '==' from '<='")
  }

  test("two global constraints of different types are both applied") {
    assume(available, "goldens missing")
    // Each fixture inherits `ac-dc-meshed`'s `co2_limit` alongside its own transmission
    // limit, so `type` is dispatched on twice per build with different answers. A builder
    // that resolved it once and reused the result would satisfy one and silently drop the
    // other.
    val n = network("ac-dc-txvolume")
    val kinds = n.require("GlobalConstraint").ids.map(id =>
      n.require("GlobalConstraint").string("type", id)).toSet
    assertEquals(kinds,
      Set("primary_energy", "transmission_volume_expansion_limit"),
      "the fixture no longer carries two constraint types, so this test is vacuous")
    // Both duals are non-zero in PyPSA's answer, which is what "both bind" means.
    val mu = results("ac-dc-txvolume")("optimize")("global_constraint_mu")
    assert(math.abs(mu("co2_limit").num) > 1e-9, "the CO2 cap does not bind here")
    assert(math.abs(mu("tx_limit").num) > 1e-9, "the transmission cap does not bind here")
    agrees("ac-dc-txvolume"): Unit
  }

  test("an equality global constraint keeps the row identity Sclopf depends on") {
    assume(available, "goldens missing")
    // The reason the whole block moved. Global constraints were emitted after the
    // capacity-coupling inequalities, which was safe while only `<=` existed; `==` is an
    // equality, and an equality after an inequality takes a standard-form index that
    // differs from its original.
    //
    // `Sclopf` is the only consumer of that invariant, and it is reachable here: this
    // network has passive branches and no extendable ones are refused, so it builds.
    val n     = network("ac-dc-txvolume-exact")
    val model = Lopf.build(n)
    val problem = model.problem
    assert(problem.numConstraints > problem.numEqualities, "no inequality rows -- weak test")

    val translation = model.translation
    val misindexed = (0 until translation.numOriginalRows).filterNot { r =>
      translation.expansionOf(r) match
        case RowExpansion.Direct(row)  => row == r
        case RowExpansion.Negated(row) => row == r
        case _                         => false
    }
    assertEquals(misindexed.toList, Nil,
      "a row does not map to the standard-form row of the same index, so Sclopf would refuse it")
  }

  test("a transmission limit counts what is built, not what is already there") {
    assume(available, "goldens missing")
    // PyPSA iterates the extendable branches only: the limit is on expansion, and a fixed
    // branch's capacity is a constant that belongs on neither side of it.
    //
    // Every line in `ac-dc-meshed` is extendable, so the filter never excludes anything
    // there and a mutation removing it survived the rest of this suite. Here one line is
    // fixed at the 40,000 MW the file gives it, which at length 120 is 4.8M of
    // length-weighted capacity against a cap of 380,000 -- so an implementation that counted
    // it would be infeasible rather than merely wrong, which is as sharp as this gets.
    val fixed = mutate("ac-dc-txvolume", "lines.csv",
      setColumn(_, "s_nom_extendable", (id, current) => if id == "0" then "False" else current))
    val lines = fixed.require("Line")
    assert(!Expansion.isExtendable(lines, "0"), "line 0 is still extendable -- weak test")
    assertEqualsDouble(lines.float("s_nom", "0"), 40000.0, 1e-9,
      "line 0 no longer carries the capacity this test's arithmetic assumes")

    val result = Lopf.solve(fixed, params)
    assertEquals(result.status, SolveStatus.Optimal,
      "the fixed line's capacity was counted against a limit on expansion")
    // And the row is still over the six that remain, tight at the cap.
    val cap = constantOf(fixed, "tx_limit")
    val built = lines.ids.zipWithIndex.collect {
      case (id, i) if Expansion.isExtendable(lines, id) =>
        lengths(i) * result.capacity("Line", id)
    }.sum
    assertEqualsDouble(built, cap, 1e-4 * cap, "the cap is not tight over the extendables")
  }

  test("a carrier list is split per entry and each one trimmed") {
    assume(available, "goldens missing")
    // PyPSA writes `carrier_attribute` as a comma-separated list and strips each entry.
    // Every fixture here names one carrier, so a mutation that dropped the per-entry trim
    // survived the rest of this suite. Three attempts to write a test that can see it, and
    // each failure was informative:
    //
    //   - a single carrier with a stray space is not reachable: `CsvReader` trims whole
    //     cells, so the space is gone before this code runs
    //   - `setColumn` cannot write `AC, DC`: it joins on commas without quoting, so the cell
    //     shifts every column after it
    //   - the VOLUME limit cannot see it either, because this fixture gives lengths to the
    //     lines and not to the links, so the DC branches weight zero whether matched or not
    //
    // The cost limit can: the four links carry capital costs of 0.19 to 0.88, so including
    // them changes the row. What is reachable is the space *inside* a list -- `"AC, DC"`
    // arrives as that string, and splitting without trimming yields `" DC"`, which matches
    // nothing.
    val listed = copiedWith(
      goldens.resolve("networks").resolve("ac-dc-txcost"),
      "ac-dc-txcost",
      "global_constraints.csv" ->
        ("name,type,carrier_attribute,sense,constant\n" +
          "co2_limit,primary_energy,co2_emissions,<=,1000.0\n" +
          "tx_limit,transmission_expansion_cost_limit,\"AC, DC\",>=,4800.0\n"),
    )
    assertEquals(
      listed.require("GlobalConstraint").string("carrier_attribute", "tx_limit"),
      "AC, DC",
      "the quoted list did not survive the reader, so this test cannot see the difference",
    )

    val both   = Lopf.solve(listed, params)
    val acOnly = Lopf.solve(network("ac-dc-txcost"), params)
    assertEquals(both.status, SolveStatus.Optimal)
    assertEquals(acOnly.status, SolveStatus.Optimal)

    // With the links in the row the floor is cheaper to meet -- there is more to count
    // towards it -- so the objective moves. Untrimmed, `" DC"` matches none of them and this
    // is the AC-only answer exactly.
    assert(math.abs(both.objective - acOnly.objective) > 1.0,
      s"adding DC changed nothing: ${acOnly.objective} -> ${both.objective}, so the second " +
        "entry was not trimmed and matched no branch")

    // And the floor is met counting lines and links together, which is the row this names.
    val links = listed.require("Link")
    val lines = listed.require("Line")
    val total =
      lines.ids.filter(Expansion.isExtendable(lines, _))
        .map(id => lines.float("capital_cost", id) * both.capacity("Line", id)).sum +
      links.ids.filter(Expansion.isExtendable(links, _))
        .map(id => links.float("capital_cost", id) * both.capacity("Link", id)).sum
    assert(links.ids.exists(Expansion.isExtendable(links, _)), "no extendable DC link")
    assertEqualsDouble(total, 4800.0, 1e-3 * 4800.0,
      "the floor is not tight over lines and links together")
  }

  test("primary_energy charges the carrier column the constraint names") {
    assume(available, "goldens missing")
    // Which column is charged is data, not a constant, and every fixture in the repository
    // happens to use `co2_emissions` -- so hardcoding that name reproduces all of them, and
    // a mutation doing exactly that survived the rest of this suite.
    //
    // Here the intensities move to a differently named column and the constraint is pointed
    // at it. The answer must be unchanged: same numbers, different label. A port reading a
    // fixed name finds nothing, drops every term, and -- since `<=` over no terms is
    // accepted -- returns the unconstrained optimum, which on this fixture is 12.7% cheaper.
    val renamed = copiedWith(
      goldens.resolve("networks").resolve("ac-dc-co2"),
      "ac-dc-co2",
      "carriers.csv" ->
        ("name,nox_emissions,color,marginal_cost,efficiency,capital_cost\n" +
          "gas,0.24,red,0.0,1.0,0.0\n" +
          "wind,0.0,blue,0.0,1.0,0.0\n" +
          "battery,0.0,green,0.0,1.0,0.0\n" +
          "load,0.0,black,,,\n" +
          "AC,0.0,orange,,,\n" +
          "DC,0.0,purple,,,\n"),
      "global_constraints.csv" ->
        ("name,type,carrier_attribute,sense,constant\n" +
          "co2_limit,primary_energy,nox_emissions,<=,2000.0\n"),
    )
    val result   = Lopf.solve(renamed, params)
    val expected = results("ac-dc-co2")("optimize")("objective").num
    assertEquals(result.status, SolveStatus.Optimal)
    assertEqualsDouble(result.objective, expected, 1e-6 * math.abs(expected),
      "renaming the carrier column changed the answer, so the name is not being read")

    // The control: with the constraint pointed at a column that is not there, the cap is
    // over nothing and the answer is the cheaper unconstrained one. Without this, the
    // assertion above would also pass for a port that had dropped the row entirely.
    val wrong = copiedWith(
      goldens.resolve("networks").resolve("ac-dc-co2"),
      "ac-dc-co2",
      "global_constraints.csv" ->
        ("name,type,carrier_attribute,sense,constant\n" +
          "co2_limit,primary_energy,not_a_column,<=,2000.0\n"),
    )
    val dropped = Lopf.solve(wrong, params)
    assert(math.abs(dropped.objective - expected) > 1.0,
      "pointing the cap at a missing column changed nothing, so this fixture cannot tell " +
        "a read attribute from a hardcoded one")
  }

  test("a sense or type PyPSA does not write is refused by name") {
    assume(available, "goldens missing")
    Seq(
      "sense" -> "<",
      "type"  -> "operational_limit",
    ).foreach { (column, value) =>
      val mutated = mutate("ac-dc-txvolume", "global_constraints.csv",
        setColumn(_, column, (id, current) => if id == "tx_limit" then value else current))
      val message = intercept[Lopf.UnsupportedNetwork](Lopf.build(mutated)).getMessage
      assert(message.contains(value), s"$column = $value: $message")
    }
  }

  test("a constraint matching nothing is refused where its sense makes it a claim") {
    assume(available, "goldens missing")
    // `<=` over no terms is `0 <= constant`, which a non-negative cap satisfies and which is
    // therefore harmless. `>=` and `==` over no terms are claims that can be false, and
    // dropping those silently is how a carrier typo turns a binding requirement into an
    // unconstrained run.
    val carrierTypo = (n: String, sense: String) =>
      mutate(n, "global_constraints.csv", text =>
        setColumn(setColumn(text, "carrier_attribute",
          (id, current) => if id == "tx_limit" then "NOT_A_CARRIER" else current),
          "sense", (id, current) => if id == "tx_limit" then sense else current))

    Seq(">=", "==").foreach { sense =>
      val message =
        intercept[Lopf.UnsupportedNetwork](Lopf.build(carrierTypo("ac-dc-txvolume", sense)))
          .getMessage
      assert(message.contains("NOT_A_CARRIER"), s"$sense: $message")
    }
    // The `<=` direction is accepted, because it genuinely constrains nothing.
    Lopf.build(carrierTypo("ac-dc-txvolume", "<=")): Unit
  }
