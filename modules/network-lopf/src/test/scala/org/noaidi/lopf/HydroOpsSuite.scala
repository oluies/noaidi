package org.noaidi.lopf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network}
import org.noaidi.prima.{PdhgParams, SolveStatus}

/** NordPSA's reservoir behaviour against this port -- [[HydroOps]] and one family
  * that turned out to need none of it.
  *
  * The fixtures under `reference/nordpsa` are NordPSA's own toy hydro network —
  * one zone, 21 days at 3-hourly resolution, a reservoir with inflow and
  * `spill_cost` against a sinusoidal load — exported in five inflow/cheap-capacity
  * variants, plus `hydro.json`: the objective PyPSA reaches on each variant under
  * each operational configuration, produced by calling NordPSA's own
  * `hydro_operation_constraints` callback.
  *
  * ==Why the configuration is read from the file==
  *
  * The configurations are parsed out of `hydro.json` rather than restated here.
  * They have to be: a callback config written twice is a config that drifts, and
  * the drift would show up as this suite agreeing with a PyPSA run that answered a
  * different question. One source, whichever side is read.
  *
  * ==Why most of these numbers are hard to get wrong by accident, and two are not==
  *
  * With cyclic state-of-charge the horizon's production equals its inflow, so the
  * water already forces a mean output of `inflow / p_nom` and most parameters do
  * not bind at all. A floor under that mean, or a ceiling over it, changes nothing
  * — and a suite built on those would pass against code that dropped every row.
  * The scenarios are NordPSA's for exactly that reason: it varies `inflow` and
  * `cheap_mw` per case to put each limit on the binding side, and asserts on its
  * own references that they were not already satisfied.
  *
  * Two of its cases needed strengthening rather than copying, and both are noted
  * at the assertion.
  */
class HydroOpsSuite extends munit.FunSuite, NordPsaFixtures:

  override protected def tempPrefix: String = "noaidi-hydroops-"

  private lazy val fixtures: Boolean  = hasReference("hydro.json")
  private lazy val reference: ujson.Value = referenceJson("hydro.json")

  // The same tolerances LopfSuite drives the goldens with.
  private val params = PdhgParams(epsAbs = 1e-9, epsRel = 1e-9, maxIterations = 500_000)

  /** One case's stored PyPSA answer. */
  private def stored(name: String): ujson.Value = reference("cases")(name)

  /** Rebuild a [[HydroOps.Config]] from the JSON the generator wrote. */
  private def configOf(name: String): HydroOps.Config =
    stored(name).obj.get("config").flatMap(c => Option.when(!c.isNull)(c)) match
      case None => HydroOps.off
      case Some(c) =>
        val o     = c.obj
        val num   = (k: String) => o.get(k).map(_.num).getOrElse(0.0)
        val zones = o.get("max_weekly_frac_by_zone")
          .map(_.obj.map((z, v) => z -> v.num).toMap)
          .getOrElse(Map.empty)
        val bypass = o.get("bypass_spill").map(_.obj) match
          case None => HydroOps.BypassSpill()
          case Some(b) =>
            HydroOps.BypassSpill(
              active = b.get("active").exists(_.bool),
              thresholdBelowMax = b.get("threshold_below_max").map(_.num).getOrElse(0.10),
              coefficient = b.get("coefficient").map(_.num).getOrElse(1.0),
              coefficientByZone = b.get("coefficient_by_zone")
                .map(_.obj.map((z, v) => z -> v.num).toMap).getOrElse(Map.empty),
            )
        HydroOps.Config(
          minHourlyFraction = num("min_hourly_frac"),
          minDailyFraction = num("min_daily_frac"),
          maxWeeklyFraction = num("max_weekly_frac"),
          maxWeeklyFractionByZone = zones,
          bypassSpill = bypass,
        )

  private def solve(name: String): LopfResult =
    val key = stored(name)("network").str
    Lopf.solve(variant(key), configOf(name), org.noaidi.prima.Pdhg.Solver(params))

  /** Assert this port reaches the objective PyPSA reached on the same case. */
  private def agrees(name: String): LopfResult =
    val result = solve(name)
    val target = stored(name)("objective").num
    assertEquals(result.status, SolveStatus.Optimal, s"$name did not solve")
    assertEqualsDouble(
      result.objective,
      target,
      1e-6 * math.max(1.0, math.abs(target)),
      s"$name objective disagrees with PyPSA",
    )
    result

  private lazy val socReference: ujson.Value = referenceJson("soc.json")

  test("NordPSA's initial-SoC anchor needs no new constraint family") {
    assume(hasReference("soc.json"), "soc fixtures are not present")
    // `hydro_soc_initial` is an extra_functionality callback pinning
    // `soc[t0] == frac * p_nom * max_hours`. PyPSA already has an attribute that does
    // exactly that -- `state_of_charge_set` -- and unlike a callback it lives on the
    // network, so it survives an export and this port already honours it. So this
    // family is a config-to-network mapping rather than a constraint to implement,
    // and `generate_soc.py` establishes the equivalence against NordPSA's own
    // callback rather than against a reading of it: same objective and same state of
    // charge at both ends of the horizon.
    assert(socReference("equivalent").bool, "the callback and the attribute disagree in PyPSA")

    val stored = socReference("cases")("attribute")
    val result = Lopf.solve(variant("soc-anchor"), HydroOps.off, org.noaidi.prima.Pdhg.Solver(params))
    assertEquals(result.status, SolveStatus.Optimal)

    val target = socReference("anchor")("target_mwh").num
    assertEqualsDouble(
      result.stateOfCharge("Z hydro", 0),
      target,
      1e-6 * target,
      "the anchored snapshot did not reach NordPSA's target level",
    )
    val objective = stored("objective").num
    assertEqualsDouble(
      result.objective,
      objective,
      1e-6 * math.max(1.0, math.abs(objective)),
      "objective disagrees with PyPSA on the anchored network",
    )
  }

  test("the anchor moves the level and not the cost, and the reference says so") {
    assume(hasReference("soc.json"), "soc fixtures are not present")
    // Worth pinning because it is the reason the test above asserts a level rather
    // than a price, and because it looked at first like a weak fixture. Under cyclic
    // state-of-charge the anchor fixes the level while the horizon's water balance is
    // untouched -- total dispatch still equals inflow minus spill -- so no
    // (max_hours, fraction) pair can make it change the objective. Four were tried.
    // What it exists for is the seam between rolling-horizon windows, where one
    // window's terminal level is the next one's initial level.
    assert(socReference("anchor_binds_on_soc").bool, "the anchor did not move soc[0] at all")
    assert(
      socReference("cost_neutral_in_one_cyclic_window").bool,
      "the anchor moved the objective, so this port should be asserting that too",
    )

    // And this port agrees: dropping the anchor column leaves the objective alone and
    // moves the level away from the target.
    val source = root.resolve("networks").resolve("soc-anchor")
    val blanked = Files.readString(source.resolve("storage_units-state_of_charge_set.csv"))
      .linesIterator.zipWithIndex
      .map((line, i) => if i == 0 then line else line.split(",", 2)(0) + ",")
      .mkString("\n") + "\n"
    val free = copiedWith(source, "soc-anchor",
      "storage_units-state_of_charge_set.csv" -> blanked)
    val anchored = Lopf.solve(variant("soc-anchor"), HydroOps.off, org.noaidi.prima.Pdhg.Solver(params))
    val loose    = Lopf.solve(free, HydroOps.off, org.noaidi.prima.Pdhg.Solver(params))
    assertEqualsDouble(
      loose.objective,
      anchored.objective,
      1e-6 * math.max(1.0, math.abs(anchored.objective)),
      "removing the anchor changed the cost, which the reference says it cannot",
    )
    assert(
      math.abs(loose.stateOfCharge("Z hydro", 0) - anchored.stateOfCharge("Z hydro", 0)) > 1.0,
      "removing the anchor left the level unchanged, so it was pinning nothing",
    )
  }
  test("a network with no operational limits matches PyPSA") {
    assume(fixtures, "reference/nordpsa is not present")
    agrees("floors-ref")
    agrees("weekly-cap-ref")
  }

  test("the hourly and daily floors bind, and cost what PyPSA says they cost") {
    assume(fixtures, "reference/nordpsa is not present")
    // NordPSA's own guard, carried over: if the unconstrained reference already
    // met the 0.10 floor there would be nothing to prove.
    val floor = 0.10 * reference("shape")("p_nom").num
    assert(
      stored("floors-ref")("hydro_min_mw").num < floor,
      "the reference already satisfied the hourly floor -- weak test",
    )
    val base = agrees("floors-ref")
    val held = agrees("floors")
    assert(
      held.objective > base.objective,
      s"floors cost nothing: ${held.objective} vs ${base.objective}",
    )
  }

  test("a tighter daily floor costs strictly more") {
    assume(fixtures, "reference/nordpsa is not present")
    val loose = agrees("floors")
    val tight = agrees("daily-45")
    assert(tight.objective > loose.objective, "the tighter daily floor cost nothing")
  }

  test("the weekly ceiling binds, costs more, and forces spill") {
    assume(fixtures, "reference/nordpsa is not present")
    assert(
      stored("weekly-cap-ref")("weekly_max_frac").num > 0.40,
      "the reference was already under the ceiling -- weak test",
    )
    val base   = agrees("weekly-cap-ref")
    val capped = agrees("weekly-cap-40")
    assert(capped.objective > base.objective, "the weekly ceiling cost nothing")
    assert(
      stored("weekly-cap-40")("spill_mwh").num > stored("weekly-cap-ref")("spill_mwh").num,
      "the ceiling did not force spill",
    )
  }

  test("a per-zone ceiling overrides the global one") {
    assume(fixtures, "reference/nordpsa is not present")
    // Strengthened rather than copied. NordPSA's case uses Z = 0.35, which on this
    // network settles at wmax = 0.325 -- under its own override, so it would pass
    // against code that ignored the override entirely and applied the global 0.77.
    // Z = 0.25 binds, and the global-only reference is what makes that visible:
    // ignoring the override here is a 5.5x objective error, not a rounding one.
    val global = agrees("zone-override-ref")
    val zoned  = agrees("zone-override-25")
    assert(
      zoned.objective > global.objective,
      s"the zone override changed nothing: ${zoned.objective} vs ${global.objective}",
    )
  }

  test("the bypass hinge reaches PyPSA's objective") {
    assume(fixtures, "reference/nordpsa is not present")
    agrees("bypass-hinge")
  }

  test("a floor the inflow cannot supply is refused, not priced") {
    assume(fixtures, "reference/nordpsa is not present")
    // Cyclic state-of-charge pins mean output at inflow / p_nom = 0.30, so a 0.45
    // daily floor has no feasible schedule and PyPSA returns no objective. The
    // failure that matters is not "a different number" but "a number at all".
    assert(
      !stored("infeasible-floor").obj.contains("objective"),
      "the reference solved this case, so it is not the infeasible one",
    )
    val result = solve("infeasible-floor")
    assertNotEquals(
      result.status,
      SolveStatus.Optimal,
      "an infeasible floor was reported optimal",
    )
  }

  private def rows(n: Network, config: HydroOps.Config): Int =
    Lopf.build(n, config).problem.numConstraints

  test("an inflow-free reservoir gets no hinge, rather than a tighter ceiling") {
    assume(fixtures, "reference/nordpsa is not present")
    // `Lopf` declares a spill column for every unit at every snapshot and bounds it
    // by that snapshot's inflow, so testing the variable map for spill was vacuous:
    // always true. The hinge it wrongly emitted had every spill term pinned to zero,
    // degenerating to a weekly ceiling `thresholdBelowMax` tighter than configured --
    // where NordPSA warns and emits nothing.
    val zeroInflow = Files.readString(
      root.resolve("networks").resolve("inflow450-cheap600").resolve("storage_units-inflow.csv"),
    ).linesIterator.zipWithIndex.map { (line, i) =>
      if i == 0 then line else line.split(",", 2)(0) + ",0.0"
    }.mkString("\n") + "\n"

    val dry = copiedWith(
      root.resolve("networks").resolve("inflow450-cheap600"),
      "inflow450-cheap600",
      "storage_units-inflow.csv" -> zeroInflow,
    )

    val ceilingOnly = HydroOps.Config(maxWeeklyFraction = 0.60)
    val withHinge = ceilingOnly.copy(bypassSpill =
      HydroOps.BypassSpill(active = true, thresholdBelowMax = 0.10, coefficient = 0.15),
    )
    assertEquals(
      rows(dry, withHinge),
      rows(dry, ceilingOnly),
      "an inflow-free reservoir still got hinge rows",
    )
    // And the guard is not simply off: the same request against the real inflow does
    // add rows, so the test above is about the inflow rather than about the flag.
    val wet = variant("inflow450-cheap600")
    assert(
      rows(wet, withHinge) > rows(wet, ceilingOnly),
      "the hinge emitted nothing even with inflow -- the guard is inverted",
    )
  }

  test("a reservoir outside its investment period gets no floor row") {
    assume(available, "reference/goldens is not present")
    // `investment-periods` runs 2030, 2030, 2040, 2040. A unit built in 2040 has its
    // dispatch column pinned to [0, 0] for the first two, and an hourly floor of
    // `f * p_nom > 0` against a pinned column is an infeasible LP reported as the
    // network's problem rather than as this model's -- which is the failure `Lopf`
    // already avoids for `state_of_charge_set`.
    val n = copiedWith(
      goldens.resolve("networks").resolve("investment-periods"),
      "investment-periods",
      "storage_units.csv" ->
        ("name,bus,carrier,p_nom,max_hours,marginal_cost,build_year,lifetime\n" +
          "Z hydro,b,hydro,100.0,10.0,1.0,2040,30.0\n"),
    )
    val floor = HydroOps.Config(minHourlyFraction = 0.2)
    assertEquals(
      rows(n, floor) - rows(n, HydroOps.off),
      2,
      "the hourly floor did not emit exactly one row per active snapshot (2 of 4)",
    )
  }

  test("one calendar week in two investment periods is two windows, not one") {
    assume(available, "reference/goldens is not present")
    // A multi-period index carries only the timestep half of (period, timestep), and
    // that half repeats across periods. All four snapshots below fall inside the ISO
    // week of Monday 2023-01-02, two in each period -- so keyed on the date alone
    // they are one window and the ceiling becomes a single row spanning both
    // periods, summing energy PyPSA would never sum together.
    val n = copiedWith(
      goldens.resolve("networks").resolve("investment-periods"),
      "investment-periods",
      "snapshots.csv" ->
        (",period,timestep,objective,stores,generators\n" +
          "0,2030,2023-01-02 00:00:00,1.0,1.0,1.0\n" +
          "1,2030,2023-01-03 00:00:00,1.0,1.0,1.0\n" +
          "2,2040,2023-01-04 00:00:00,1.0,1.0,1.0\n" +
          "3,2040,2023-01-05 00:00:00,1.0,1.0,1.0\n"),
      "storage_units.csv" ->
        ("name,bus,carrier,p_nom,max_hours,marginal_cost,build_year,lifetime\n" +
          "Z hydro,b,hydro,100.0,10.0,1.0,0,inf\n"),
    )
    assertEquals(
      rows(n, HydroOps.Config(maxWeeklyFraction = 0.5)) - rows(n, HydroOps.off),
      2,
      "the week was not split per investment period",
    )
  }
  test("the daily floor's right-hand side is the window's own weighted hours") {
    assume(available, "reference/goldens is not present")
    // Asserts the value, not the masking, and the distinction is the point.
    //
    // A review asked for a case where masked and unmasked `H` differ. There is
    // none: `activeAt` reduces to `activeIn(table, id, period)`, and the window key
    // carries the period, so a window's members share one activity verdict and the
    // window is wholly active or wholly absent. Reverting `hoursOf` to
    // `members.map(weight).sum` leaves this suite green for that reason rather than
    // from a gap -- see the note on `hoursOf`.
    //
    // What is worth pinning is that `H` is the window's summed `stores` weightings
    // and not its snapshot count, which unequal weightings below make visible: a
    // count would give 2, the weightings give 10.
    val n = copiedWith(
      goldens.resolve("networks").resolve("investment-periods"),
      "investment-periods",
      "snapshots.csv" ->
        (",period,timestep,objective,stores,generators\n" +
          "0,2030,2023-01-02 00:00:00,1.0,2.0,1.0\n" +
          "1,2030,2023-01-02 06:00:00,1.0,2.0,1.0\n" +
          "2,2040,2023-01-03 00:00:00,1.0,5.0,1.0\n" +
          "3,2040,2023-01-03 06:00:00,1.0,5.0,1.0\n"),
      "storage_units.csv" ->
        ("name,bus,carrier,p_nom,max_hours,marginal_cost,build_year,lifetime\n" +
          "Z hydro,b,hydro,100.0,10.0,1.0,2040,30.0\n"),
    )
    // Built in 2040, so only the 2040 day carries a row: H = 5.0 + 5.0 = 10.0 and
    // the floor is 0.3 * 100 * 10 = 300. A count-based H would give 0.3 * 100 * 2.
    val fraction = 0.3
    val floored  = Lopf.build(n, HydroOps.Config(minDailyFraction = fraction))
    val plain    = Lopf.build(n, HydroOps.off)
    val added    = floored.problem.rhs.drop(plain.problem.numConstraints)
    assertEquals(added.length, 1, "expected exactly one daily-floor row")
    assertEqualsDouble(added(0), fraction * 100.0 * 10.0, 1e-9, "H was not the active hours")
  }

  test("a zone limit is refused even when no reservoir survives the filter") {
    assume(fixtures, "reference/nordpsa is not present")
    // The blind spot in the first version of this guard: it sat after the
    // empty-units return, so it could not fire in the one case its own comment
    // cited -- a reservoir dropped by the `carrier`/`p_nom` test. A p_nom of 0
    // drops the only unit, and the zone key then matches nothing at all.
    val n = copiedWith(
      root.resolve("networks").resolve("inflow450-cheap600"),
      "inflow450-cheap600",
      "storage_units.csv" -> Files.readString(
        root.resolve("networks").resolve("inflow450-cheap600").resolve("storage_units.csv"),
      ).replace(",1000.0,", ",0.0,"),
    )
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(n, HydroOps.Config(maxWeeklyFractionByZone = Map("Z" -> 0.3)))
    }
    assert(
      refused.getMessage.contains("'Z' matches no reservoir"),
      s"the refusal did not fire for a filtered-out reservoir: ${refused.getMessage}",
    )
  }

  test("an inert zone ceiling and an ownerless kappa are both refused") {
    assume(fixtures, "reference/nordpsa is not present")
    val n = variant("inflow450-cheap600")
    // A zone ceiling of 0.0 matches a reservoir but is filtered out, leaving it
    // *less* constrained than the global ceiling would have -- silently, before.
    val zero = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(n, HydroOps.Config(maxWeeklyFraction = 0.6,
        maxWeeklyFractionByZone = Map("Z" -> 0.0)))
    }
    assert(zero.getMessage.contains("cannot constrain anything"), zero.getMessage)

    // A kappa override for a reservoir with no ceiling has nothing to measure from.
    val ownerless = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(n, HydroOps.Config(bypassSpill =
        HydroOps.BypassSpill(active = true, coefficientByZone = Map("Z" -> 0.2))))
    }
    assert(ownerless.getMessage.contains("no weekly ceiling"), ownerless.getMessage)
  }

  test("a zone limit is refused on a network with no StorageUnit table at all") {
    assume(available, "reference/goldens is not present")
    // The reason `table` is an Option rather than an early return, and until now the
    // reason had no test: every case that reached the guard used a network that does
    // have a StorageUnit table, so restoring `case None => return` would have left
    // the suite green while reopening the silent no-op the Option was introduced to
    // close. `ac-dc-meshed` ships no storage_units.csv.
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(network("ac-dc-meshed"), HydroOps.Config(maxWeeklyFractionByZone = Map("Z" -> 0.3)))
    }
    assert(
      refused.getMessage.contains("matches no reservoir") && refused.getMessage.contains("none"),
      s"the refusal did not fire for a table-less network: ${refused.getMessage}",
    )
  }

  test("a NaN zone ceiling is refused rather than silently emitting no row") {
    assume(fixtures, "reference/nordpsa is not present")
    // The guard tested `<= 0.0` while the row filter tests `> 0.0`, and NaN fails
    // both: it matched a reservoir, passed every arm of the guard, and emitted no
    // weekly row -- the run reporting a number as though the zone limit had applied,
    // which is the exact failure inertZones exists to refuse. Periods.scala treats a
    // NaN slipping through a comparison as a real hazard for the same reason.
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(
        variant("inflow450-cheap600"),
        HydroOps.Config(maxWeeklyFraction = 0.6,
          maxWeeklyFractionByZone = Map("Z" -> Double.NaN)),
      )
    }
    assert(refused.getMessage.contains("NaN"), s"the refusal does not name NaN: ${refused.getMessage}")
  }

  test("a zone that is both unmatched and inert is reported once") {
    assume(fixtures, "reference/nordpsa is not present")
    // One config entry, one complaint. Reported by both arms it read as two problems
    // with the same name.
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(
        variant("inflow450-cheap600"),
        HydroOps.Config(maxWeeklyFraction = 0.6, maxWeeklyFractionByZone = Map("SE9" -> 0.0)),
      )
    }
    assertEquals(
      refused.getMessage.sliding(5).count(_ == "'SE9'"),
      1,
      s"the zone was named more than once: ${refused.getMessage}",
    )
  }
  test("an inactive hinge does not fail the build over a coefficient nothing reads") {
    assume(fixtures, "reference/nordpsa is not present")
    // The mirror-image mistake, and the first version made it: `coefficientByZone`
    // was unioned into the check unconditionally, so an inactive hinge failed the
    // whole build over a value no row could consult.
    Lopf.build(
      variant("inflow450-cheap600"),
      HydroOps.Config(maxWeeklyFraction = 0.6, bypassSpill =
        HydroOps.BypassSpill(active = false, coefficientByZone = Map("nowhere" -> 0.2))),
    )
  }
  test("the refusal names the limit that actually needed a calendar") {
    assume(available, "reference/goldens is not present")
    // Both windows were computed eagerly once either limit was set, so a
    // weekly-only config reported that it had been "asked for a daily window" and
    // sent the reader to the wrong line.
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(integerSnapshots, HydroOps.Config(maxWeeklyFraction = 0.5))
    }
    assert(
      refused.getMessage.contains("weekly window"),
      s"a weekly-only config blamed the wrong limit: ${refused.getMessage}",
    )
  }

  test("a zone limit matching no reservoir is refused, not quietly dropped") {
    assume(fixtures, "reference/nordpsa is not present")
    // A deliberate divergence from NordPSA, which keeps the global ceiling when a
    // zone key matches nothing. The zone name is the one thing a typo lands in, and
    // silence means the run reports a number as though the limit had applied.
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(
        variant("inflow450-cheap600"),
        HydroOps.Config(maxWeeklyFraction = 0.6, maxWeeklyFractionByZone = Map("SE2" -> 0.3)),
      )
    }
    assert(
      refused.getMessage.contains("SE2") && refused.getMessage.contains("SE2 hydro"),
      s"the refusal does not name the zone or what it looked for: ${refused.getMessage}",
    )
  }
  /** `storage-cycle`, whose snapshots are `0, 1, 2`, with its reservoir declared.
    *
    * The fixture carries a StorageUnit *named* `hydro` and no `carrier` column at
    * all, so its carrier is the schema default and [[HydroOps]] correctly matches
    * nothing on it. Both tests below were written against the unmutated fixture
    * first: the refusal did not fire and the permission "passed", each for the same
    * reason -- there was no reservoir to constrain. One of those looks like a bug
    * and the other looks like a pass, which is the worse of the two.
    */
  private def integerSnapshots: Network =
    mutate("storage-cycle", "storage_units.csv", setColumn(_, "carrier", "hydro"))

  test("a calendar window over an integer snapshot index is refused by name") {
    assume(available, "reference/goldens is not present")
    // A daily window over `0, 1, 2` is meaningless, and the two silent alternatives
    // -- drop the row, or invent an ordinal window -- are both worse than stopping.
    val refused = intercept[Lopf.UnsupportedNetwork] {
      Lopf.build(integerSnapshots, HydroOps.Config(minDailyFraction = 0.2))
    }
    assert(
      refused.getMessage.contains("not a") && refused.getMessage.contains("timestamp"),
      s"the refusal does not say why: ${refused.getMessage}",
    )
  }

  test("an hourly floor needs no calendar and so is allowed on an integer index") {
    assume(available, "reference/goldens is not present")
    // The other half of the rule. Refusing this too would be over-reach: nothing
    // about a per-snapshot floor needs to know what a week is.
    //
    // Asserted by row count rather than by absence of an exception. "It did not
    // throw" is also what a version that silently emitted nothing would report,
    // and that version is the one this whole file exists to rule out.
    val network = integerSnapshots
    val without = Lopf.build(network, HydroOps.off).problem.numConstraints
    val with_   = Lopf.build(network, HydroOps.Config(minHourlyFraction = 0.1)).problem.numConstraints
    assert(
      with_ > without,
      s"the hourly floor emitted no rows: $without constraints either way",
    )
  }
