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

  /** A column of a golden frame, by entity name. As `LopfSuite`'s, which this suite had no
    * occasion for until a fixture here recorded a dispatch worth comparing row by row.
    */
  private def frameValue(frame: ujson.Value, row: Int, column: String): Double =
    val index = frame("columns").arr.indexWhere(_.str == column)
    assert(index >= 0, s"golden frame has no column '$column'")
    frame("values")(row)(index) match
      case ujson.Num(v) => v
      case other        => fail(s"unexpected golden value $other")

  /** The lengths `generate_goldens.py` gives the lines, in file order.
    *
    * Needed as a literal for exactly one thing: the free-volume comparison against
    * `ac-dc-meshed`, which ships every line at `length = 0` and so cannot supply them. Every
    * other use reads `length` from the fixture's own table -- a positional copy is both a
    * cross-language duplicate that can drift and a coupling to row order, so reordering
    * `lines.csv` would leave the assertions passing against the wrong weights.
    */
  private val freeVolumeLengths = IndexedSeq(120.0, 80.0, 200.0, 150.0, 60.0, 90.0, 110.0)

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
    lines.ids.map(id => lines.float(weight, id) * result.capacity("Line", id)).sum

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
    // `ac-dc-meshed` ships zero lengths, so this is the one place the literal is needed.
    val freeTotal = network("ac-dc-meshed").require("Line").ids.zipWithIndex.map { (id, i) =>
      freeVolumeLengths(i) * free.capacity("Line", id)
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
    // No re-solve here: the first test already asserts this network's objective and every
    // capacity against PyPSA, and repeating it costs two 500k-iteration solves for no new
    // assertion.
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
    val built = lines.ids.collect {
      case id if Expansion.isExtendable(lines, id) =>
        lines.float("length", id) * result.capacity("Line", id)
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

    // And the bracketed form, which is how a Python list literal lands in a CSV. PyPSA
    // strips `[](){}` per entry for exactly this; without it the set is `{"[AC", "DC]"}`,
    // which matches nothing and -- under `<=` with a non-negative constant -- is accepted as
    // vacuous, so the port would undercut PyPSA on a row PyPSA builds and binds.
    val bracketed = copiedWith(
      goldens.resolve("networks").resolve("ac-dc-txcost"),
      "ac-dc-txcost",
      "global_constraints.csv" ->
        ("name,type,carrier_attribute,sense,constant\n" +
          "co2_limit,primary_energy,co2_emissions,<=,1000.0\n" +
          "tx_limit,transmission_expansion_cost_limit,\"[AC, DC]\",>=,4800.0\n"),
    )

    val both   = Lopf.solve(listed, params)
    val square = Lopf.solve(bracketed, params)
    val acOnly = Lopf.solve(network("ac-dc-txcost"), params)
    assertEquals(square.status, SolveStatus.Optimal)
    assertEqualsDouble(square.objective, both.objective, 1e-6 * math.abs(both.objective),
      "the bracketed list did not parse to the same carriers as the bare one")
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

    // No separate control, and the reason is worth recording because two were written and
    // both were wrong.
    //
    // The first pointed the cap at a missing column and asserted the answer got cheaper --
    // a control over a defect, since silently dropping the row is what the refusal below now
    // prevents. The second renamed the CSV column and expected that refusal to fire; it does
    // not, and correctly: `co2_emissions` is a PyPSA-defined attribute, so the spec keeps it
    // and every carrier reads the 0.0 default. That is PyPSA's own all-zero skip path, not
    // its KeyError path.
    //
    // The positive assertion above is sufficient on its own. A port looking up a hardcoded
    // `co2_emissions` on this network finds it in the spec, reads 0.0 for every carrier,
    // builds no terms, drops the row, and returns the unconstrained optimum -- 12.7%
    // cheaper, which the comparison catches. Confirmed by mutation rather than argued.
  }

  /** A stability config this network can carry, for the ordering test below. */
  private def stabilityOn: Stability.Config = Stability.Config(
    tech = Map("gas" -> Stability.Tech(Stability.Mode.Commit, inertiaSeconds = 5.0,
      cosPhi = 0.85, subtransientReactance = 0.16, minStableFraction = 0.4)),
    mapping = Map("Generator:gas" -> "gas"),
    systemInertiaGws = 0.001,
  )

  test("an equality keeps its row index with another family's inequalities present") {
    assume(available, "goldens missing")
    // The regression. Moving the global-constraint block ahead of the capacity-coupling
    // inequalities was not enough: `Stability` emits only inequalities and sat ABOVE the
    // block, so a `==` cap with a stability config was still an equality after inequalities.
    // Measured before the fix: original rows 110-113 of this network came back misindexed.
    //
    // Neither family's own suite could see it, because each builds without the other. That
    // is the shape of the gap, and it is why this test pairs them rather than adding another
    // case to either side.
    val families = Lopf.Families(stability = stabilityOn)
    val model    = Lopf.build(network("ac-dc-txvolume-exact"), families)
    val plain    = Lopf.build(network("ac-dc-txvolume-exact"))
    assert(model.problem.numConstraints > plain.problem.numConstraints,
      "the stability config emitted no rows, so this test is about the wrong model")

    val translation = model.translation
    val misindexed = (0 until translation.numOriginalRows).filterNot { r =>
      translation.expansionOf(r) match
        case RowExpansion.Direct(row)  => row == r
        case RowExpansion.Negated(row) => row == r
        case _                         => false
    }
    assertEquals(misindexed.toList, Nil,
      "an equality global constraint lost its row index once another family emitted " +
        "inequalities, so Sclopf would refuse the model")
  }

  test("a cap charging a carrier this port cannot account for is refused") {
    assume(available, "goldens missing")
    // PyPSA charges StorageUnit and Store carriers against a `primary_energy` cap through
    // their state of charge; this port sums generators only. Under `<=` that merely loosened
    // the row, which is why no golden caught it -- `storage-hvdc` gives its battery carrier
    // 0.0. Under `==` and `>=` a missing term has no defensible sign, so the omission is
    // named rather than left to loosen or tighten silently.
    val carriers = network("storage-hvdc").require("Carrier")
    assert(carriers.ids.contains("battery"), "this fixture has no battery carrier")
    assert(network("storage-hvdc").require("StorageUnit").ids.nonEmpty,
      "this fixture has no storage unit to charge")

    // As shipped it is zero, so it builds.
    Lopf.build(network("storage-hvdc")): Unit

    val charged = copiedWith(
      goldens.resolve("networks").resolve("storage-hvdc"),
      "storage-hvdc",
      "carriers.csv" -> "name,co2_emissions\ngas,0.24\nwind,0.0\nbattery,0.3\n",
    )
    val message = intercept[Lopf.UnsupportedNetwork](Lopf.build(charged)).getMessage
    assert(message.contains("StorageUnit") && message.contains("state of charge"), message)
  }

  test("a cyclic emitting fleet agrees with PyPSA, not merely builds") {
    assume(available, "goldens missing")
    // The claim the refusal rests on, checked against a solved network rather than against
    // a reading of upstream's source. `storage-cyclic-co2` is `storage-hvdc` with its
    // battery carrier at 0.3 and every storage unit cyclic, so PyPSA's `primary_energy` row
    // is generators-only -- which is the only row this port knows how to build. If that
    // filter ever differed, the port would build a quietly different row and return a
    // cheaper objective, and until this fixture existed nothing would have failed.
    //
    // Every other golden gives its battery carrier 0.0, so none of them can tell. The cap
    // binds here (mu = -417.44), which is what makes the agreement evidence rather than a
    // comparison of two unconstrained optima.
    val units = network("storage-cyclic-co2").require("StorageUnit")
    assert(units.ids.nonEmpty && units.ids.forall(Storage.isCyclic(units, _)),
      "this fixture no longer has an all-cyclic storage fleet")
    assertEqualsDouble(
      network("storage-cyclic-co2").require("Carrier").float("co2_emissions", "battery"),
      0.3, 1e-9, "the battery carrier no longer emits, so the fixture proves nothing")
    val mu = results("storage-cyclic-co2")("optimize")("global_constraint_mu")("co2_limit").num
    assert(math.abs(mu) > 1e-9, s"the cap does not bind in PyPSA's answer (mu = $mu)")

    agrees("storage-cyclic-co2"): Unit
  }

  test("a cyclic emitting unit still builds, because PyPSA charges it nothing either") {
    assume(available, "goldens missing")
    // The other side of the refusal above, and the case the first version of it broke.
    // PyPSA filters `not cyclic_state_of_charge` and `not e_cyclic`: a cyclic unit returns
    // to its starting level and has no net primary energy to charge. For one of those the
    // generators-only row this port builds IS PyPSA's row, so refusing it would reject a
    // network that used to build and agree.
    //
    // `storage-hvdc` has both kinds, which is what makes it able to tell them apart:
    // Storage 2 and Storage 4 are cyclic, the rest are not.
    val units = network("storage-hvdc").require("StorageUnit")
    val cyclic    = units.ids.filter(Storage.isCyclic(units, _))
    val nonCyclic = units.ids.filterNot(Storage.isCyclic(units, _))
    assert(cyclic.nonEmpty && nonCyclic.nonEmpty,
      s"this fixture no longer has both kinds: cyclic=$cyclic nonCyclic=$nonCyclic")

    // Every emitting unit cyclic: builds, because PyPSA builds the same row.
    val cyclicOnly = copiedWith(
      goldens.resolve("networks").resolve("storage-hvdc"),
      "storage-hvdc",
      "carriers.csv" -> "name,co2_emissions\ngas,0.24\nwind,0.0\nbattery,0.3\n",
      "storage_units.csv" -> allCyclic,
    )
    Lopf.build(cyclicOnly): Unit

    // And with the non-cyclic ones left alone it is refused, naming one of them.
    val mixed = copiedWith(
      goldens.resolve("networks").resolve("storage-hvdc"),
      "storage-hvdc",
      "carriers.csv" -> "name,co2_emissions\ngas,0.24\nwind,0.0\nbattery,0.3\n",
    )
    val message = intercept[Lopf.UnsupportedNetwork](Lopf.build(mixed)).getMessage
    assert(nonCyclic.exists(id => message.contains(id)),
      s"the refusal named no non-cyclic unit: $message")
    assert(!cyclic.exists(id => message.contains(id)),
      s"the refusal named a cyclic unit, which PyPSA charges nothing: $message")
  }

  /** `storage-hvdc`'s storage table with every unit made cyclic. */
  private def allCyclic: String =
    val text = java.nio.file.Files.readString(
      goldens.resolve("networks").resolve("storage-hvdc").resolve("storage_units.csv"))
    setColumn(text, "cyclic_state_of_charge", (_, _) => "True")

  test("a Store's cyclicity is read from e_cyclic, not from a StorageUnit's column") {
    assume(available, "goldens missing")
    // The Store half of the same guard, and it needs its own case: a mutation swapping
    // `Stores.isCyclic` for `Storage.isCyclic` survived every other test here, because no
    // golden ships a Store with an emitting carrier. The two read different columns --
    // `e_cyclic` against `cyclic_state_of_charge` -- and only a Store can tell them apart.
    //
    // `store-bank` has both kinds (`swing` is cyclic, `tank` and `grow` are not) and no
    // carriers at all, so the carrier column and the cap are added here.
    val withCarriers = copiedWith(
      goldens.resolve("networks").resolve("store-bank"),
      "store-bank",
      "carriers.csv" -> "name,co2_emissions\nheat,0.3\n",
      "stores.csv" -> setColumn(
        java.nio.file.Files.readString(
          goldens.resolve("networks").resolve("store-bank").resolve("stores.csv")),
        "carrier", (_, _) => "heat"),
      "global_constraints.csv" ->
        ("name,type,carrier_attribute,sense,constant\n" +
          "co2_limit,primary_energy,co2_emissions,<=,1000.0\n"),
    )
    val stores = withCarriers.require("Store")
    assert(stores.ids.exists(Stores.isCyclic(stores, _)) &&
      stores.ids.exists(!Stores.isCyclic(stores, _)),
      "this fixture no longer has both cyclic and non-cyclic stores")

    // The non-cyclic ones are charged by PyPSA and not by this port, so it refuses and names
    // them -- and does not name the cyclic one.
    val message = intercept[Lopf.UnsupportedNetwork](Lopf.build(withCarriers)).getMessage
    stores.ids.foreach { id =>
      if Stores.isCyclic(stores, id) then
        assert(!message.contains(s"'$id'"), s"named the cyclic store '$id': $message")
      else
        assert(message.contains(s"'$id'"), s"did not name the non-cyclic store '$id': $message")
    }
  }

  test("a non-finite constant is refused rather than dying inside the builder") {
    assume(available, "goldens missing")
    // The right-hand side had no guard while the branch weights did. Reproduced on all
    // three senses before the fix: `IllegalArgumentException: constraint has empty range
    // [NaN, NaN]`, naming neither the constraint nor the column.
    Seq("<=", ">=", "==").foreach { sense =>
      val nan = mutate("ac-dc-co2", "global_constraints.csv", text =>
        setColumn(setColumn(text, "constant",
          (id, current) => if id == "co2_limit" then "nan" else current),
          "sense", (id, current) => if id == "co2_limit" then sense else current))
      val message = intercept[Lopf.UnsupportedNetwork](Lopf.build(nan)).getMessage
      assert(message.contains("co2_limit") && message.contains("non-finite"),
        s"$sense: $message")
    }
  }

  test("a non-finite transmission weight is refused rather than reaching the matrix") {
    assume(available, "goldens missing")
    // A NaN `length` becomes a NaN coefficient and surfaces far away as an anonymous row,
    // which is the failure `TerminalValue` and `Stability` already refuse by name.
    val nan = mutate("ac-dc-txvolume", "lines.csv",
      setColumn(_, "length", (id, current) => if id == "0" then "nan" else current))
    val message = intercept[Lopf.UnsupportedNetwork](Lopf.build(nan)).getMessage
    assert(message.contains("length") && message.contains("non-finite"), message)
  }

  test("a carrier_attribute no column defines is refused, whatever the sense") {
    assume(available, "goldens missing")
    // PyPSA indexes the column directly -- `n.c.carriers.static[glc.carrier_attribute]` --
    // so a misspelling raises. Here `carrierAttribute` returns 0.0 both for "zero" and for
    // "no such column", so it dropped every term, dropped the row, and reported the cheaper
    // unconstrained optimum.
    //
    // This suite previously had a test asserting that `<=` accepted exactly that, on the
    // reasoning that a cap over nothing is vacuous. The reasoning holds for a column that
    // exists and is all zero -- which is PyPSA's own skip path -- and not for one that does
    // not exist, which is a typo. Pinning the two together certified the divergence.
    Seq("<=", ">=", "==").foreach { sense =>
      val typo = mutate("ac-dc-co2", "global_constraints.csv", text =>
        setColumn(setColumn(text, "carrier_attribute",
          (id, current) => if id == "co2_limit" then "co2_emission" else current),
          "sense", (id, current) => if id == "co2_limit" then sense else current))
      val message = intercept[Lopf.UnsupportedNetwork](Lopf.build(typo)).getMessage
      assert(message.contains("co2_emission") && message.contains("no Carrier column"),
        s"$sense: $message")
    }
  }

  test("a carrier column that exists and is all zero is skipped, as PyPSA skips it") {
    assume(available, "goldens missing")
    // The other half of the pair above, and the reason the refusal is narrow. PyPSA builds
    // no row when the column is present and every carrier reads zero, so neither does this.
    val allZero = copiedWith(
      goldens.resolve("networks").resolve("ac-dc-co2"),
      "ac-dc-co2",
      "carriers.csv" ->
        ("name,co2_emissions,color,marginal_cost,efficiency,capital_cost\n" +
          "gas,0.0,red,0.0,1.0,0.0\n" +
          "wind,0.0,blue,0.0,1.0,0.0\n" +
          "battery,0.0,green,0.0,1.0,0.0\n" +
          "load,0.0,black,,,\n" +
          "AC,0.0,orange,,,\n" +
          "DC,0.0,purple,,,\n"),
    )
    val result = Lopf.solve(allZero, params)
    assertEquals(result.status, SolveStatus.Optimal)
    // No row, so the answer is the unconstrained one -- which is what PyPSA gives too.
    val free = Lopf.solve(network("ac-dc-dispatch"), params)
    assert(result.objective <= free.objective + 1.0,
      "an all-zero emissions column still constrained the dispatch")
  }

  test("a transmission limit matching nothing is refused where its sense makes it a claim") {
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
    // The `<=` direction is accepted, because a cap over no branch genuinely constrains
    // nothing -- and unlike the `primary_energy` case above, a carrier naming no branch is
    // an ordinary thing in PyPSA (it filters `carrier in @car` and may match none).
    Lopf.build(carrierTypo("ac-dc-txvolume", "<=")): Unit
  }

  test("an operational_limit caps a carrier's energy, not its emissions") {
    assume(available, "goldens missing")
    // The type the refusal above used to name as its own justification: a cap on a carrier's
    // energy built as an emissions-weighted sum is a different constraint wearing the same
    // right-hand side, returning `Optimal`. It is built now, and this fixture says what it is.
    //
    // Four things are observable here at once, and the base comparison fails on each of them
    // alone -- which is why the mutations below are about the *cap* rather than about these:
    //
    //   - the sum is weighted by `snapshot_weightings.generators`, which this fixture sets to
    //     3.0 while leaving `objective` at 1.0. It is the first golden anywhere whose three
    //     weighting columns are not all equal.
    //   - `gas` runs 40 MW at the first snapshot, so summing every generator rather than the
    //     named carrier's reaches 800 against the cap's 680.
    //   - `res` contributes its NET depletion, `-soc(last) + 500 = 200`, as one variable plus
    //     a constant rather than a sum over the horizon.
    //   - `pump` carries the same carrier and is cyclic, so it contributes nothing.
    //
    // The last of those is detected by a margin of 0.8, not by the 20 its
    // `state_of_charge_initial` would suggest, and the reason is worth writing down because
    // the first version of this comment got it wrong. Including the cyclic unit does not
    // tighten the cap by 20: it hands the LP a lever, because `-soc(pump, last) + 20` can be
    // driven to zero by holding the unit charged at the last snapshot. What that costs is
    // `marginal_cost_storage`, which is 0.01 here -- so the objective comes out 2,800.8
    // instead of 2,800. At the attribute's default of zero the lever would be free, the term
    // would go to exactly zero, and including a cyclic unit would be invisible on this
    // fixture. The 0.01 is doing two jobs: pinning the level so the dispatch is determinate,
    // and making the wrong filter observable at all.
    val expected = results("operational-limit")("optimize")
    assert(!expected.obj.contains("error"), s"golden solve failed: ${expected.obj.get("error")}")

    val n      = network("operational-limit")
    val result = Lopf.solve(n, params)
    assertEquals(result.status, SolveStatus.Optimal, s"${result.solution}")

    val target = expected("objective").num
    assertEqualsDouble(target, 2800.0, 1e-6, "the golden is not the fixture this test was written for")
    assertEqualsDouble(result.objective, target, 1e-6 * target, s"against PyPSA's $target")

    // The cap's own shadow price, which has no accessor, is -3.0. It shows up in the nodal
    // prices instead: 12 at the first snapshot, where gas is marginal, and 13 at the rest,
    // where `res` is -- its bid of 10 plus the multiplier. So the prices check the dual.
    val p      = expected("generator_p")
    val soc    = expected("storage_state_of_charge")
    val prices = expected("bus_marginal_price")
    n.snapshots.indices.foreach { t =>
      n.require("Generator").ids.foreach { id =>
        assertEqualsDouble(result.dispatch("Generator", id, t), frameValue(p, t, id), 1e-4,
          s"generator $id at snapshot $t")
      }
      n.require("StorageUnit").ids.foreach { id =>
        assertEqualsDouble(result.stateOfCharge(id, t), frameValue(soc, t, id), 1e-3,
          s"$id's level at snapshot $t")
      }
      assertEqualsDouble(result.marginalPrice("b", t), frameValue(prices, t, "b"), 1e-4,
        s"marginal price at snapshot $t")
    }

    // The cap is partial at the last snapshot -- 10 MW of an available 30 -- which is what
    // makes the shadow price a point rather than an interval, and it is why the cap is 680
    // and not a rounder number: at 720 and at 660 the answer sits on a corner and PyPSA's own
    // two solvers disagree about the dispatch while agreeing about the cost.
    assertEqualsDouble(result.dispatch("Generator", "ror", 3), 10.0, 1e-4,
      "the cut is not partial at the last snapshot, so this fixture's dual is an interval")
  }

  test("the cap is on net depletion, and on the `generators` weighting") {
    assume(available, "goldens missing")
    def solved(file: String, edit: String => String): Double =
      val r = Lopf.solve(mutate("operational-limit", file, edit), params)
      assertEquals(r.status, SolveStatus.Optimal, s"${r.solution}")
      r.objective

    // The weighting column, and the whole reason the fixture sets the three apart. At
    // `generators = 1.0` the left-hand side comes to 160 + 200 = 360 against a limit of 680
    // and the cap stops binding, so `ror` runs to its profile and gas stays off. PyPSA: 2,600.
    assertEqualsDouble(
      solved("snapshots.csv", setColumn(_, "generators", (_, _) => "1.0")),
      2600.0, 1e-3, "the sum is not carrying the `generators` snapshot weighting")

    // The limit itself, tightened by 80. The substitution ladder continues at the next
    // cheapest snapshot rather than restarting, so this is a check on the ordering and not
    // only on the number being read. PyPSA: 3,070.
    assertEqualsDouble(
      solved("global_constraints.csv", setColumn(_, "constant", (_, _) => "600.0")),
      3070.0, 1e-3, "the cap's constant is not being read")

    // Raising the reservoir's starting level by 100 changes NOTHING, because the cap is on
    // how much lower it ends than it started and not on where it sits. The initial level is
    // the constant on the left-hand side and `-soc(last)` is the variable beside it, so the
    // two move together; a port that kept one and dropped the other would shift by 100 here.
    // PyPSA: 2,800, with every level 100 higher.
    assertEqualsDouble(
      solved("storage_units.csv",
             setColumn(_, "state_of_charge_initial", (id, v) => if id == "res" then "600.0" else v)),
      2800.0, 1e-3, "the cap is on the absolute level rather than on the depletion")
  }

  test("a tech_capacity_expansion_limit caps buildable capacity, per carrier and per bus") {
    assume(available, "goldens missing")
    // The fifth and last type `global_constraints.py` dispatches on, and the last one left
    // here rather than the hardest: its left-hand side is one coefficient of 1.0 per capacity
    // column, with no weighting and no snapshot index. What it needed was capacity to be a
    // decision at all.
    //
    // `carrier_attribute` names a CARRIER on this type, not a column of `carriers.csv` --
    // the same field means `co2_emissions` on `primary_energy` and `hydro` on
    // `operational_limit`. And `bus` is optional: `wind_total`'s is the empty string in the
    // exported CSV rather than an absent column, which is the shape PyPSA's own
    // `glc.get("bus") or None` exists for.
    val expected = results("tech-capacity-limit")("optimize")
    assert(!expected.obj.contains("error"), s"golden solve failed: ${expected.obj.get("error")}")

    val n      = network("tech-capacity-limit")
    val result = Lopf.solve(n, params)
    assertEquals(result.status, SolveStatus.Optimal, s"${result.solution}")

    val target = expected("objective").num
    assertEqualsDouble(target, 19400.0, 1e-6, "the golden is not the fixture this test was written for")
    assertEqualsDouble(result.objective, target, 1e-6 * target, s"against PyPSA's $target")

    expected("nominal_opt").obj.foreach { (component, built) =>
      built.obj.foreach { (id, capacity) =>
        assertEqualsDouble(result.capacity(component, id), capacity.num, 1e-4,
          s"capacity of $component '$id'")
      }
    }
    // Named as well as swept. `windB` at exactly its bus cap of 40 and `windA` at the
    // remaining 80 of the network-wide 120 is the arrangement both caps bind in; `solarA` at
    // its own `p_nom_max` of 50 is the carrier filter, since a port that counted every
    // extendable would have 120 to share between three assets rather than two.
    assertEqualsDouble(result.capacity("Generator", "windB"), 40.0, 1e-4, "windB at its bus cap")
    assertEqualsDouble(result.capacity("Generator", "windA"), 80.0, 1e-4, "windA at the remainder")
    assertEqualsDouble(result.capacity("Generator", "solarA"), 50.0, 1e-4,
      "solarA is being counted against a wind cap")

    val p = expected("generator_p")
    n.snapshots.indices.foreach { t =>
      n.require("Generator").ids.foreach { id =>
        assertEqualsDouble(result.dispatch("Generator", id, t), frameValue(p, t, id), 1e-4,
          s"generator $id at snapshot $t")
      }
      assertEqualsDouble(result.dispatch("Line", "AB", t),
        frameValue(expected("line_p0"), t, "AB"), 1e-4, s"line flow at snapshot $t")
    }
  }

  test("moving the bus scope moves which asset the cap counts") {
    assume(available, "goldens missing")
    // The whole content of the `bus` column, checked by moving it. Scoped to B the cap holds
    // `windB` to 40 and the network-wide 120 goes to `windA`; scoped to A it holds `windA` to
    // 40 -- and because `windB` is worth more per MW and now has no cap of its own, the
    // answer reverses entirely: `windB` takes all 120 and `windA` is built to zero. PyPSA:
    // 18,600. A port that read the constraint's bus against the wrong end, or ignored it,
    // lands on neither number.
    val moved = Lopf.solve(
      mutate("tech-capacity-limit", "global_constraints.csv",
             setColumn(_, "bus", (id, b) => if id == "wind_at_b" then "A" else b)),
      params,
    )
    assertEquals(moved.status, SolveStatus.Optimal, s"${moved.solution}")
    assertEqualsDouble(moved.objective, 18600.0, 1e-3, "the bus scope is not selecting assets")
    assertEqualsDouble(moved.capacity("Generator", "windB"), 120.0, 1e-4, "windB unbounded by bus")
    assertEqualsDouble(moved.capacity("Generator", "windA"), 0.0, 1e-4, "windA at its bus cap")

    // And the constant, tightened by 20. The bus cap still binds at 40, so the 20 comes off
    // `windA` alone: 60 and 40 rather than 80 and 40. PyPSA: 19,800.
    val tighter = Lopf.solve(
      mutate("tech-capacity-limit", "global_constraints.csv",
             setColumn(_, "constant", (id, c) => if id == "wind_total" then "100.0" else c)),
      params,
    )
    assertEquals(tighter.status, SolveStatus.Optimal, s"${tighter.solution}")
    assertEqualsDouble(tighter.objective, 19800.0, 1e-3, "the cap's constant is not being read")
    assertEqualsDouble(tighter.capacity("Generator", "windA"), 60.0, 1e-4, "windA at the new remainder")

    // A bus the network does not have is refused rather than matched by nothing. PyPSA raises
    // a KeyError selecting it; here the scope would match no asset, the row would be dropped
    // as vacuous under `<=`, and a cap on buildable capacity would silently not apply -- the
    // cheaper direction, reported as Optimal.
    val failure = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(
        mutate("tech-capacity-limit", "global_constraints.csv",
               setColumn(_, "bus", (id, b) => if id == "wind_at_b" then "C" else b))
      )
    }
    assert(failure.getMessage.contains("'C'"),
      s"the refusal does not name the missing bus: ${failure.getMessage}")
  }

  test("a global constraint scoped to one investment period binds in that period only") {
    assume(available, "goldens missing")
    // Refused until now, and the refusal's reasoning was right as far as it went: read as
    // horizon-wide, a cap meant for 2040 alone is TIGHTER than the network states and makes
    // the answer dearer, while read as a cap per period rather than in total it is looser.
    // Neither direction has a defensible sign, so refusing beat guessing.
    //
    // What was missing is that there is nothing to guess. For the two snapshot-summing types
    // PyPSA restricts the sum to that period's snapshots -- `period_sns = sns[period_of ==
    // period]` -- and that is all.
    //
    // Note what the export looks like, because it is where this would have gone wrong
    // silently: `global_constraints.csv` writes `investment_period` as `2030.0` while
    // `investment_periods.csv` writes the period as `2030`. Comparing the two as text matches
    // nothing, every scoped constraint would be skipped as naming an absent period, and the
    // answer would come out cheaper. The scope is resolved numerically.
    val expected = results("period-scoped-constraints")("optimize")
    assert(!expected.obj.contains("error"), s"golden solve failed: ${expected.obj.get("error")}")
    assert(expected("multi_investment_periods").bool, "the golden was not solved multi-period")

    val n = network("period-scoped-constraints")
    assertEquals(n.snapshotPeriods, IndexedSeq("2030", "2030", "2040", "2040"), "snapshot periods")

    val result = Lopf.solve(n, params)
    assertEquals(result.status, SolveStatus.Optimal, s"${result.solution}")

    val target = expected("objective").num
    assertEqualsDouble(target, 15020.0, 1e-6, "the golden is not the fixture this test was written for")
    assertEqualsDouble(result.objective, target, 1e-6 * target, s"against PyPSA's $target")

    val p      = expected("generator_p")
    val prices = expected("bus_marginal_price")
    n.snapshots.indices.foreach { t =>
      n.require("Generator").ids.foreach { id =>
        assertEqualsDouble(result.dispatch("Generator", id, t), frameValue(p, t, id), 1e-4,
          s"generator $id at snapshot $t")
      }
      assertEqualsDouble(result.marginalPrice("b", t), frameValue(prices, t, "b"), 1e-4,
        s"marginal price at snapshot $t")
    }

    // Each period abates for itself and neither borrows from the other: 50 MW at 2030's
    // second snapshot and 80 at 2040's. The prices carry the two multipliers separately --
    // 88 = 10 + 7.8 x 10 at 2030's first snapshot and 88 = 14 + 14.8 x 5 at 2040's -- which is
    // what says each row is weighted by its own period's `years` and not by the other's.
    assertEqualsDouble(result.dispatch("Generator", "clean", 1), 50.0, 1e-4, "2030 abates 50")
    assertEqualsDouble(result.dispatch("Generator", "clean", 3), 80.0, 1e-4, "2040 abates 80")
    assertEqualsDouble(result.dispatch("Generator", "clean", 0), 0.0, 1e-4, "2030's cheap snapshot is untouched")
    assertEqualsDouble(result.dispatch("Generator", "clean", 2), 0.0, 1e-4, "2040's cheap snapshot is untouched")
  }

  test("the three things a scope does, and the one it must not do") {
    assume(available, "goldens missing")
    def solved(edit: String => String): Double =
      val r = Lopf.solve(mutate("period-scoped-constraints", "global_constraints.csv", edit), params)
      assertEquals(r.status, SolveStatus.Optimal, s"${r.solution}")
      r.objective

    // Unscoping the 2040 cap applies its 600 to the whole horizon, where unweighted emissions
    // are 3,000 -- so almost everything has to be abated and the answer more than doubles.
    // PyPSA: 26,920. This is the direction the old refusal called "tighter than the network
    // states", and it is the one a port that ignored the column would take.
    assertEqualsDouble(
      solved(setColumn(_, "investment_period", (id, v) => if id == "co2_2040" then "" else v)),
      26920.0, 1e-3, "the scope is not restricting the sum to its own period")

    // Scoping it to a period the horizon does not cover makes NO ROW. PyPSA's `elif
    // glc.investment_period in periods: ... else: continue`, which is a different outcome from
    // the empty left-hand side the emptiness guard handles -- that one refuses `>=` and `==`,
    // and this one must not, because upstream builds nothing either way. With the 2040 cap
    // gone, 2040 runs gas flat out. PyPSA: 9,100.
    assertEqualsDouble(
      solved(setColumn(_, "investment_period", (id, v) => if id == "co2_2040" then "2050.0" else v)),
      9100.0, 1e-3, "a scope naming an absent period is not being skipped")

    // And the `years` weighting, flattened: the caps become 200 and 200 against limits of
    // 1,500 and 600, both slack, so gas runs flat out everywhere. PyPSA: 5,200. The fixture
    // holds `objective` at 1.0 precisely so a failure here cannot be the other column.
    val flatYears = Lopf.solve(
      mutate("period-scoped-constraints", "investment_periods.csv",
             setColumn(_, "years", (_, _) => "1.0")),
      params,
    )
    assertEquals(flatYears.status, SolveStatus.Optimal, s"${flatYears.solution}")
    assertEqualsDouble(flatYears.objective, 5200.0, 1e-3,
      "a scoped emissions sum is not carrying its period's `years`")
  }

  test("a scope on a network with no periods is refused, because PyPSA crashes on it") {
    assume(available, "goldens missing")
    // Not a judgement call. Both `define_primary_energy_limit` and `define_operational_limit`
    // read `periods`, which is bound only inside `if n._multi_invest`, so a scoped constraint
    // on a network solved without the flag dies with
    // `UnboundLocalError: cannot access local variable 'periods'`. Measured on both types,
    // not inferred from the source -- so there is no answer here to agree with, and building
    // the row horizon-wide would be inventing one.
    val failure = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(
        mutate("ac-dc-co2", "global_constraints.csv",
               text => setColumn(text, "investment_period", (_, _) => "2030.0"))
      )
    }
    assert(failure.getMessage.contains("2030"),
      s"the refusal does not name the period: ${failure.getMessage}")
    assert(failure.getMessage.toLowerCase.contains("no periods") ||
             failure.getMessage.contains("UnboundLocalError"),
      s"the refusal does not say why: ${failure.getMessage}")
  }
