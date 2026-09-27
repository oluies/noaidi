package org.noaidi.lopf

import java.time.format.DateTimeFormatter
import java.time.{DayOfWeek, LocalDate, LocalDateTime}
import java.time.temporal.TemporalAdjusters
import org.noaidi.network.*
import org.noaidi.prima.LpBuilder

/** Operational limits on reservoir hydro, over calendar windows.
  *
  * A port of NordPSA's `hydro_operation_constraints`, which it applies to PyPSA
  * as an `extra_functionality` callback. That placement is the reason this file
  * exists: a callback builds rows straight into the linopy model at solve time
  * and stores '''nothing''' on the network, so a netCDF export of a NordPSA world
  * carries its buses, its inflow and its `spill_cost` but not one of these four
  * limits. Reading that file and solving it reproduces a different, strictly
  * easier problem — and reports a cheaper objective for it, which is exactly the
  * shape of a comparison that looks like a port bug and is not one.
  *
  * ==What the limits are for==
  *
  * They fence off the two unphysical extremes an unconstrained LP walks into.
  * Left alone it will shut the reservoirs off entirely through a long low-price
  * stretch — the small river-system reservoirs would overflow — and it will run
  * them flat out week after week, which is the standard failure of ELLI-like
  * models. The reference power throughout is the StorageUnit's `p_nom`, meaning
  * the '''reservoir''' share after any run-of-river split, not the whole fleet.
  *
  * {{{
  * minHourlyFraction   p_dispatch(t)          >= f · p_nom
  * minDailyFraction    Σ_day    w(t)·p(t)     >= f · p_nom · H_day
  * maxWeeklyFraction   Σ_week   w(t)·p(t)     <= f · p_nom · H_week
  * bypassSpill         Σ_week   w(t)·spill(t) >= κ · (Σ_week w(t)·p(t) − threshold)
  * }}}
  *
  * All four are optional and all four are off at zero, which is what
  * [[Config.off]] is.
  *
  * ==H is measured, not assumed==
  *
  * `H_day` and `H_week` are the '''actual''' sum of `stores` weightings inside
  * the window, never the count of snapshots and never a constant 24 or 168. Two
  * separate things go wrong if it is assumed. At 3-hourly resolution every
  * weighting is 3.0, so a count would under-state the window's hours by exactly
  * the resolution and tighten a floor threefold. And the first and last windows
  * of a horizon are usually '''partial''' — a series starting on a Wednesday has
  * a five-day first week — so a constant would demand a full week's energy from
  * five days of it. Both are silent: the LP stays feasible and returns a
  * schedule that is merely wrong.
  *
  * The `stores` weighting is the one PyPSA drives storage accounting from, and
  * it is a different column from `objective` and from `generators`. Every fixture
  * in `reference/goldens` that touches storage holds them unequal — `storage-cycle`
  * is `objective = 2.0`, `stores = 3.0`, `generators = 1.0` — so reaching for the
  * wrong one is visible here rather than latent.
  *
  * ==Calendar windows need a calendar==
  *
  * The day and week windows are '''calendar''' windows: a normalised date, and
  * the ISO week whose start is the preceding Monday. That is what pandas
  * `to_period("W-SUN").start_time` yields, and it is only defined when the
  * snapshot labels are timestamps.
  *
  * Plenty of networks here do not have those. `storage-cycle` and
  * `unit-commitment` label their snapshots `0, 1, 2`, and `storage-cycle` is a
  * storage fixture, so this is the common case rather than a corner. On such a
  * network a window limit is not satisfiable-by-default, it is '''meaningless''',
  * and the two available wrong answers are to drop it — the silent failure this
  * whole file is about — or to invent an ordinal window, which would answer a
  * question nobody asked. So it is refused, by name, and only for the limits that
  * actually need a calendar: [[Config.minHourlyFraction]] is per-snapshot and
  * applies to an integer index perfectly well.
  */
object HydroOps:

  /** The carrier a StorageUnit must declare to be reservoir hydro. */
  val Carrier = "hydro"

  private val Formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  /** Forcing spill once sustained weekly output gets close to its ceiling.
    *
    * High sustained production physically requires water to be spilled past the
    * smaller stations. This is the linear hinge that says so: slack while the
    * week's output stays under `maxWeeklyFraction − thresholdBelowMax`, and from
    * there a lower bound on PyPSA's own `spill` variable, which `spill_cost`
    * otherwise pins to zero. No binary is needed for it.
    *
    * Off by default, and that is not timidity. PyPSA bounds `spill` above by the
    * inflow in the '''same''' snapshot, so a week with high output and low inflow
    * has no feasible spill to offer and the whole model goes infeasible. NordPSA
    * ships a feasibility report to run before a solve for that reason.
    */
  final case class BypassSpill(
      active: Boolean = false,
      thresholdBelowMax: Double = 0.10,
      coefficient: Double = 1.0,
      /** κ per zone, keyed by zone; the unit is `"<zone> hydro"`. */
      coefficientByZone: Map[String, Double] = Map.empty,
  )

  /** The four knobs, all off at zero.
    *
    * `maxWeeklyFractionByZone` overrides the global ceiling for one zone, keyed
    * the way NordPSA keys it: the zone name, matched against the StorageUnit
    * called `"<zone> hydro"`. A zone with no global ceiling and no override has
    * no weekly row at all.
    */
  final case class Config(
      minHourlyFraction: Double = 0.0,
      minDailyFraction: Double = 0.0,
      maxWeeklyFraction: Double = 0.0,
      maxWeeklyFractionByZone: Map[String, Double] = Map.empty,
      bypassSpill: BypassSpill = BypassSpill(),
  ):
    /** Whether this asks for nothing, in which case [[constrain]] emits nothing. */
    def isOff: Boolean =
      minHourlyFraction <= 0.0 && minDailyFraction <= 0.0 &&
        maxWeeklyFraction <= 0.0 && maxWeeklyFractionByZone.isEmpty

  /** No operational limits, which is what a plain PyPSA network means. */
  val off: Config = Config()

  /** The weekly ceiling fraction for one reservoir, if it gets one.
    *
    * The zone override, else the global fraction, else none -- and `> 0.0` throughout,
    * which is the test that actually decides whether a row is emitted.
    *
    * One definition, called by both the row builder and [[inertZones]]. They had a
    * copy each, forty lines apart and agreeing by coincidence: adding a second
    * override source or relaxing the positivity gate in one would have desynced the
    * guard from the rows it guards, whose symptom is a build that refuses a valid
    * kappa override or accepts an inert one.
    *
    * `> 0.0` rather than `> 0.0 || isNaN`-style permissiveness on purpose: NaN is not
    * a ceiling, and because this is the same predicate the guard consults, a NaN zone
    * entry is reported there rather than silently dropped here.
    */
  private def ceilingFor(config: Config, id: String): Option[Double] =
    config.maxWeeklyFractionByZone
      .collectFirst { case (zone, value) if unitFor(zone) == id => value }
      .orElse(Option.when(config.maxWeeklyFraction > 0.0)(config.maxWeeklyFraction))
      .filter(_ > 0.0)
  /** Refuse zone configuration that cannot take effect.
    *
    * Three ways a zone entry is inert, all silent before this existed and all the
    * same failure: the run reports a number as though the limit had been applied.
    *
    *   - the name matches no reservoir -- a typo, another naming convention, or a
    *     unit dropped by the `carrier`/`p_nom` test
    *   - the ceiling is non-positive, so it is filtered out and leaves that unit
    *     *less* constrained than the global ceiling would have
    *   - a kappa override names a unit that gets no ceiling, and the hinge is
    *     measured down from a ceiling, so there is nothing for it to modify
    *
    * A deliberate divergence from NordPSA, which keeps the global value in all
    * three. Worth being explicit about the cost: a config naming a zone whose
    * reservoir was legitimately dropped now stops instead of proceeding. That is
    * the outcome worth having, and it is the reason `maxWeeklyFraction = 0.0`
    * globally still means "off" -- a global zero is the documented switch, while a
    * per-zone entry is an override somebody wrote on purpose.
    *
    * `coefficientByZone` is only checked when the hinge is active, because an
    * inactive hinge never reads it -- failing a build over a value nothing can
    * consult would be the mirror-image mistake.
    */
  private def inertZones(network: Network, config: Config, units: Seq[String]): Unit =
    val ceilingZones = config.maxWeeklyFractionByZone
    val kappaZones =
      if config.bypassSpill.active then config.bypassSpill.coefficientByZone else Map.empty

    val unmatched = (ceilingZones.keySet ++ kappaZones.keySet)
      .filterNot(z => units.contains(unitFor(z)))
      .toSeq.sorted
      .map(z => s"'$z' matches no reservoir (looked for '${unitFor(z)}')")

    // `!(v > 0.0)` rather than `v <= 0.0`, which is the same for every value except
    // NaN -- and NaN was the hole: it is not `<= 0.0`, so the guard passed it, and it
    // is not `> 0.0`, so `ceilingFor` dropped it. The entry matched a reservoir,
    // cleared every arm, and emitted no weekly row. Mirroring the emission predicate
    // exactly is what closes that rather than a special case for NaN.
    //
    // Restricted to zones that did match a unit, so an entry that is both unmatched
    // and inert is one complaint rather than two with the same name.
    val nonPositive = ceilingZones
      .filter((z, v) => units.contains(unitFor(z)) && !(v > 0.0))
      .keys.toSeq.sorted
      .map(z => s"'$z' sets a ceiling of ${ceilingZones(z)}, which cannot constrain anything")

    // A kappa override is only meaningful where a ceiling exists to measure from, and
    // "gets a ceiling" is asked of the same function the rows are built from.
    val ceilinged = units.filter(id => ceilingFor(config, id).isDefined).toSet
    val ceilingless = kappaZones.keys.toSeq.sorted
      .filter(z => units.contains(unitFor(z)) && !ceilinged.contains(unitFor(z)))
      .map(z => s"'$z' overrides the hinge coefficient for a reservoir that has no weekly ceiling")

    val problems = unmatched ++ nonPositive ++ ceilingless
    if problems.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' was given zone limits that cannot take effect: " +
          problems.mkString("; ") +
          s". Its hydro StorageUnits are " +
          (if units.isEmpty then "none" else units.mkString(", ")) + ".",
      )
  /** The unit name NordPSA gives a zone's reservoir. */
  private def unitFor(zone: String): String = s"$zone $Carrier"

  /** Emit the operational rows for the StorageUnit table.
    *
    * `columns` is the whole variable map rather than a resolved accessor, because
    * unlike [[EnergySum]] this needs two different variables — [[Storage.Dispatch]]
    * and [[Storage.Spill]] — and `spill` is '''absent''' from a network with no
    * inflow. A lookup that threw on the missing one would turn "this network has
    * no spill to bound" into a crash, so the absence is read with `get` and
    * reported the way PyPSA reports it: skip the hinge, keep the rest.
    */
  def constrain(
      network: Network,
      snapshots: Range,
      // `collection.Map` for the reason `TerminalValue.plan` gives: read in place
      // rather than copying every column in the horizon per build.
      columns: scala.collection.Map[(String, String, Int), Int],
      builder: LpBuilder,
      config: Config,
  ): Unit =
    if config.isOff && !config.bypassSpill.active then return

    // Reservoir hydro only, and only what actually has capacity. A zero-`p_nom`
    // unit would take every fraction of it to zero, which is a row that cannot
    // bind dressed up as one that can.
    //
    // An absent table gives no units rather than an early return, because the
    // zone check below has to see that case: a network with no StorageUnit table
    // at all matches every zone key nothing, which is precisely what it exists to
    // report.
    val table = network.tables.get("StorageUnit")
    val units = table.toIndexedSeq.flatMap { t =>
      t.ids.filter { id =>
        t.spec.attribute("carrier").isDefined &&
          t.string("carrier", id) == Carrier &&
          t.float("p_nom", id) > 0.0
      }
    }

    // Inert zone configuration is refused, and this runs *before* the empty-units
    // return rather than after it.
    //
    // Placed after, it could not fire in the one case its own reasoning names: a
    // reservoir filtered out by the `carrier`/`p_nom` test leaves `units` empty,
    // the method returned, and a config naming that zone reported a number as
    // though its limit had applied. Which is the whole failure being guarded
    // against, reachable only from the guard's own blind spot.
    inertZones(network, config, units)

    if units.isEmpty then return

    // Non-empty `units` came from the table, so it is present.
    val storage = table.get

    val weight = (t: Int) => network.weighting("stores", t)

    // Only snapshots the unit exists at. On a flat index `activeAt` is always
    // true, so this costs nothing there -- but on a multi-period network a unit
    // outside its `build_year`/`lifetime` window has every column pinned to
    // [0, 0], and an hourly floor of `f · p_nom > 0` against a pinned column is
    // an infeasible LP reported as the *network's* problem rather than as this
    // model's. `Lopf` masks `state_of_charge_set` by activity for exactly that
    // reason. The window rows need it too, and more subtly: an unmasked `H` sums
    // the weightings of snapshots the unit does not exist at, so the floor
    // demands a full window's energy from the active hours alone.
    val live = (id: String, t: Int) => Periods.activeAt(network, storage, id, t)

    /** The snapshots of a window this unit exists at. Filtered once, then reused:
      * `live` re-reads the period label and the unit's `build_year`/`lifetime`, so
      * calling it again per accessor multiplied that work by the number of rows.
      */
    val activeIn = (id: String, members: Seq[Int]) => members.filter(live(id, _))

    /** The dispatch terms for one unit over the active snapshots handed in. */
    val energy = (id: String, active: Seq[Int]) =>
      active.flatMap(t => columns.get((Storage.Dispatch, id, t)).map(col => (col, weight(t))))

    /** The window's hours, counting only the active snapshots handed in.
      *
      * For the window rows this is currently unobservable, and the reason is worth
      * writing down rather than rediscovering. `activeAt` reduces to
      * `activeIn(table, id, period)` -- activity depends on the snapshot's period
      * and nothing else -- and [[WindowKey]] carries the period, so every window
      * lies inside exactly one period and its members share a single activity
      * verdict. A window is therefore wholly active or wholly absent: masked and
      * unmasked `H` agree, and an unmasked one is indistinguishable by any test.
      *
      * Kept rather than dropped because the invariant is the window key's, not this
      * function's. Widen the key -- group across periods, or admit an activity rule
      * that varies inside one -- and the two stop agreeing, with a silently tighter
      * floor as the symptom. The hourly floor is a different matter: each snapshot
      * is its own row there, so masking it is load-bearing and pinned.
      */
    val hoursOf = (active: Seq[Int]) => active.map(weight).sum

    // --- per-snapshot floor ------------------------------------------------
    // No calendar needed, so this is the one limit an integer-labelled index can
    // carry.
    if config.minHourlyFraction > 0.0 then
      units.foreach { id =>
        val floor = config.minHourlyFraction * storage.float("p_nom", id)
        snapshots.filter(live(id, _)).foreach { t =>
          columns.get((Storage.Dispatch, id, t)).foreach { col =>
            builder.greaterThan(Seq((col, 1.0)), floor)
          }
        }
      }

    // --- daily floor -------------------------------------------------------
    // Each window computed only where a limit actually asks for one, so the
    // refusal in `windows` names the limit that needed a calendar. Computing both
    // up front made a weekly-only config on an integer index report that it had
    // been "asked for a daily window", which is a message that sends the reader
    // to the wrong line.
    // `lazy`, so each grouping is computed once rather than once per unit -- a year
    // at hourly resolution over ten zones re-parsed ~88k labels -- while still not
    // being computed at all unless its own limit asks, which is what keeps the
    // refusal naming the limit that needed a calendar.
    lazy val days  = windows(network, snapshots, Daily)
    lazy val weeks = windows(network, snapshots, Weekly)

    if config.minDailyFraction > 0.0 then
      units.foreach { id =>
        val pNom = storage.float("p_nom", id)
        days.foreach { (_, members) =>
          val active = activeIn(id, members)
          val terms  = energy(id, active)
          if terms.nonEmpty then
            builder.greaterThan(terms, config.minDailyFraction * pNom * hoursOf(active))
        }
      }

    // --- weekly ceiling, and the hinge that hangs off it -------------------
    // Resolved per unit, in `units` order rather than through a `Map`: hash order
    // would emit the rows in an order unrelated to the table's, which is the same
    // diffability the hand-folded `windows` below exists to protect.
    //
    // The global fraction, overridden by zone. A unit with neither gets no
    // ceiling, and so also no hinge -- the hinge's threshold is measured down
    // from the ceiling, so without one there is nothing to measure from. NordPSA
    // returns early on the same condition.
    val ceilings: Seq[(String, Double)] =
      units.flatMap(id => ceilingFor(config, id).map(id -> _))

    if ceilings.isEmpty then return

    ceilings.foreach { (id, fraction) =>
      val pNom = storage.float("p_nom", id)
      weeks.foreach { (_, members) =>
        val active = activeIn(id, members)
        val terms  = energy(id, active)
        if terms.nonEmpty then
          builder.lessThan(terms, fraction * pNom * hoursOf(active))
      }
    }

    if !config.bypassSpill.active then return

    // Spill availability is read from the inflow, not from the variable map.
    //
    // `Lopf` declares a `Storage.Spill` column for every unit at every snapshot
    // and bounds it by that snapshot's inflow, so a column-presence test is
    // vacuous here -- it is always true, unlike PyPSA, which creates no spill
    // variable at all for a unit without inflow. Testing presence therefore did
    // not skip the hinge for an inflow-free unit, it emitted one whose every
    // spill term is pinned to zero, degenerating the row to
    // `prod_week <= (fraction − below) · p_nom · H_week`: a weekly ceiling
    // `thresholdBelowMax` tighter than the one configured, where NordPSA warns
    // and emits nothing at all.
    val spilling = ceilings.map(_._1).filter { id =>
      snapshots.exists(t => live(id, t) && storage.valueAt("inflow", id, t) > 0.0)
    }
    if spilling.isEmpty then return

    val below = config.bypassSpill.thresholdBelowMax
    val ceilingOf = ceilings.toMap
    spilling.foreach { id =>
      val pNom     = storage.float("p_nom", id)
      val fraction = ceilingOf(id)
      val kappa = config.bypassSpill.coefficientByZone
        .collectFirst { case (zone, value) if unitFor(zone) == id => value }
        .getOrElse(config.bypassSpill.coefficient)

      weeks.foreach { (_, members) =>
        val active     = activeIn(id, members)
        val spillTerms = active.flatMap(t =>
          columns.get((Storage.Spill, id, t)).map(col => (col, weight(t))),
        )
        if spillTerms.nonEmpty then
          // spill_week − κ·prod_week >= −κ·threshold, threshold measured down
          // from this unit's own ceiling. Written as one row with the production
          // terms moved across rather than as a bound on a difference, because
          // `spill` and `p_dispatch` are separate columns.
          val production = energy(id, active).map((col, w) => (col, -kappa * w))
          val threshold  = (fraction - below) * pNom * hoursOf(active)
          builder.greaterThan(spillTerms ++ production, -kappa * threshold)
      }
    }

  private sealed trait Window
  private case object Daily  extends Window
  private case object Weekly extends Window

  /** One window: the investment period it sits in, and its calendar start.
    *
    * The period is part of the key, not decoration. On a multi-period network
    * `snapshots` holds only the timestep half of a `(period, timestep)` index, and
    * that half repeats across periods -- `investment-periods` runs `0, 1, 0, 1`.
    * Keyed on the date alone, two periods' identically-labelled snapshots are the
    * same window, and a week that happened to end one period and open the next
    * would have its rows fused into a single constraint spanning both. Keyed on the
    * pair they cannot be.
    */
  private final case class WindowKey(period: Option[String], start: LocalDate)

  /** Group snapshot indices by calendar window, preserving order.
    *
    * Refuses rather than guesses when a label is not a timestamp. The message
    * names the label and the limit that needed it, because the two ways this goes
    * wrong silently -- dropping the row, or inventing an ordinal window -- are
    * both worse than stopping.
    */
  private def windows(
      network: Network,
      snapshots: Range,
      window: Window,
  ): Seq[(WindowKey, Seq[Int])] =
    val keyed = snapshots.map { t =>
      val label = network.snapshots(t)
      val stamp =
        try LocalDateTime.parse(label, Formatter)
        catch
          case _: Exception =>
            throw new Lopf.UnsupportedNetwork(
              s"network '${network.name}' labels snapshot $t '$label', which is not a " +
                s"'yyyy-MM-dd HH:mm:ss' timestamp, and HydroOps was asked for a " +
                s"${if window == Daily then "daily" else "weekly"} window over it. " +
                "Calendar windows need a calendar; only minHourlyFraction works on an " +
                "integer snapshot index.",
            )
      val date = stamp.toLocalDate
      val start = window match
        case Daily  => date
        case Weekly => date.`with`(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
      WindowKey(network.periodOf(t), start) -> t
    }
    // `groupBy` would lose the order the windows appear in, which makes the rows
    // arrive in a different sequence per run and the LP harder to diff. Merging
    // only *consecutive* equal keys is also what keeps a repeated label from
    // fusing windows that are far apart in the index.
    keyed.foldLeft(Vector.empty[(WindowKey, Vector[Int])]) { case (acc, (key, t)) =>
      acc.lastOption match
        case Some((last, members)) if last == key => acc.init :+ (key -> (members :+ t))
        case _                                   => acc :+ (key -> Vector(t))
    }
