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
      columns: Map[(String, String, Int), Int],
      builder: LpBuilder,
      config: Config,
  ): Unit =
    if config.isOff && !config.bypassSpill.active then return

    // Matched rather than `getOrElse(..., return)`: a non-local return out of a
    // by-name argument is no longer supported in Scala 3, and -Werror says so.
    val table = network.tables.get("StorageUnit") match
      case Some(found) => found
      case None        => return

    // Reservoir hydro only, and only what actually has capacity. A zero-`p_nom`
    // unit would take every fraction of it to zero, which is a row that cannot
    // bind dressed up as one that can.
    val units = table.ids.filter { id =>
      table.spec.attribute("carrier").isDefined &&
        table.string("carrier", id) == Carrier &&
        table.float("p_nom", id) > 0.0
    }
    if units.isEmpty then return

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
    val live = (id: String, t: Int) => Periods.activeAt(network, table, id, t)

    /** The dispatch terms for one unit over one window, active snapshots only. */
    val energy = (id: String, members: Seq[Int]) =>
      members.filter(live(id, _)).flatMap(t =>
        columns.get((Storage.Dispatch, id, t)).map(col => (col, weight(t))),
      )

    /** The window's hours, counting only what the unit exists for. */
    val hoursOf = (id: String, members: Seq[Int]) =>
      members.filter(live(id, _)).map(weight).sum

    // --- per-snapshot floor ------------------------------------------------
    // No calendar needed, so this is the one limit an integer-labelled index can
    // carry.
    if config.minHourlyFraction > 0.0 then
      units.foreach { id =>
        val floor = config.minHourlyFraction * table.float("p_nom", id)
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
    if config.minDailyFraction > 0.0 then
      units.foreach { id =>
        val pNom = table.float("p_nom", id)
        windows(network, snapshots, Daily).foreach { (_, members) =>
          val terms = energy(id, members)
          if terms.nonEmpty then
            builder.greaterThan(terms, config.minDailyFraction * pNom * hoursOf(id, members))
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
      units.flatMap { id =>
        val zoned = config.maxWeeklyFractionByZone.collectFirst {
          case (zone, value) if unitFor(zone) == id => value
        }
        zoned.orElse(Option.when(config.maxWeeklyFraction > 0.0)(config.maxWeeklyFraction))
          .filter(_ > 0.0)
          .map(id -> _)
      }

    // A zone key that matches no unit is refused rather than ignored.
    //
    // A deliberate divergence from NordPSA, which does `if su_name in fw.index`
    // and silently keeps the global ceiling. Silence is the wrong answer here for
    // the reason this file already argues twice: a typo, a different naming
    // convention, or a unit filtered out by the `carrier`/`p_nom` test all leave
    // a configured zone limit doing nothing, and the run reports a number as
    // though the limit had been applied. The cost of refusing is that a config
    // naming a zone whose reservoir was dropped now stops instead of proceeding
    // -- which is the outcome worth having.
    val unmatched = (config.maxWeeklyFractionByZone.keySet ++
      config.bypassSpill.coefficientByZone.keySet).filterNot(z => units.contains(unitFor(z)))
    if unmatched.nonEmpty then
      throw new Lopf.UnsupportedNetwork(
        s"network '${network.name}' has no reservoir for zone(s) " +
          unmatched.toSeq.sorted.map(z => s"'$z' (looked for '${unitFor(z)}')").mkString(", ") +
          s"; its hydro StorageUnits are ${units.mkString(", ")}. A zone limit that " +
          "matches nothing would leave the global ceiling in force and report a " +
          "number as though it had been applied.",
      )

    if ceilings.isEmpty then return

    val weeks = windows(network, snapshots, Weekly)

    ceilings.foreach { (id, fraction) =>
      val pNom = table.float("p_nom", id)
      weeks.foreach { (_, members) =>
        val terms = energy(id, members)
        if terms.nonEmpty then
          builder.lessThan(terms, fraction * pNom * hoursOf(id, members))
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
      snapshots.exists(t => live(id, t) && table.valueAt("inflow", id, t) > 0.0)
    }
    if spilling.isEmpty then return

    val below = config.bypassSpill.thresholdBelowMax
    val ceilingOf = ceilings.toMap
    spilling.foreach { id =>
      val pNom     = table.float("p_nom", id)
      val fraction = ceilingOf(id)
      val kappa = config.bypassSpill.coefficientByZone
        .collectFirst { case (zone, value) if unitFor(zone) == id => value }
        .getOrElse(config.bypassSpill.coefficient)

      weeks.foreach { (_, members) =>
        val active     = members.filter(live(id, _))
        val spillTerms = active.flatMap(t =>
          columns.get((Storage.Spill, id, t)).map(col => (col, weight(t))),
        )
        if spillTerms.nonEmpty then
          // spill_week − κ·prod_week >= −κ·threshold, threshold measured down
          // from this unit's own ceiling. Written as one row with the production
          // terms moved across rather than as a bound on a difference, because
          // `spill` and `p_dispatch` are separate columns.
          val production = energy(id, members).map((col, w) => (col, -kappa * w))
          val threshold  = (fraction - below) * pNom * hoursOf(id, members)
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
