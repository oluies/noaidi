package org.noaidi.lopf

import java.nio.file.{Files, Path, Paths}
import org.noaidi.network.{CsvReader, Network}
import org.noaidi.prima.{PdhgParams, SolveStatus}

/** [[HydroOps]] against NordPSA, which is where the constraints come from.
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
class HydroOpsSuite extends munit.FunSuite, CsvFixtures:

  override protected def tempPrefix: String = "noaidi-hydroops-"

  private def root: Path =
    Paths.get(sys.env.getOrElse("NOAIDI_NORDPSA", "reference/nordpsa"))

  private lazy val fixtures: Boolean =
    available && Files.exists(root.resolve("hydro.json"))

  private lazy val reference: ujson.Value =
    ujson.read(Files.readString(root.resolve("hydro.json")))

  private def variant(key: String): Network =
    CsvReader.read(root.resolve("networks").resolve(key), schema, key)

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
