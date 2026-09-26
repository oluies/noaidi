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

    // --- per-snapshot floor ------------------------------------------------
    // No calendar needed, so this is the one limit an integer-labelled index can
    // carry.
    if config.minHourlyFraction > 0.0 then
      units.foreach { id =>
        val floor = config.minHourlyFraction * table.float("p_nom", id)
        snapshots.foreach { t =>
          columns.get((Storage.Dispatch, id, t)).foreach { col =>
            builder.greaterThan(Seq((col, 1.0)), floor)
          }
        }
      }

    val needsCalendar =
      config.minDailyFraction > 0.0 || config.maxWeeklyFraction > 0.0 ||
        config.maxWeeklyFractionByZone.nonEmpty

    if !needsCalendar then return

    val days  = windows(network, snapshots, Daily)
    val weeks = windows(network, snapshots, Weekly)

    // --- daily floor -------------------------------------------------------
    if config.minDailyFraction > 0.0 then
      units.foreach { id =>
        val pNom = table.float("p_nom", id)
        days.foreach { (_, members) =>
          val hours = members.map(weight).sum
          val terms = members.flatMap(t =>
            columns.get((Storage.Dispatch, id, t)).map(col => (col, weight(t))),
          )
          if terms.nonEmpty then
            builder.greaterThan(terms, config.minDailyFraction * pNom * hours)
        }
      }

    // --- weekly ceiling, and the hinge that hangs off it -------------------
    // Resolved per unit: the global fraction, overridden by zone. A unit with
    // neither gets no ceiling, and so also no hinge -- the hinge's threshold is
    // measured down from the ceiling, so without one there is nothing to measure
    // from. NordPSA returns early on the same condition.
    val ceilings: Map[String, Double] =
      units.flatMap { id =>
        val zoned = config.maxWeeklyFractionByZone.collectFirst {
          case (zone, value) if unitFor(zone) == id => value
        }
        zoned.orElse(Option.when(config.maxWeeklyFraction > 0.0)(config.maxWeeklyFraction))
          .filter(_ > 0.0)
          .map(id -> _)
      }.toMap

    if ceilings.isEmpty then return

    val weeklyHours  = weeks.map((key, members) => key -> members.map(weight).sum).toMap
    val weeklyEnergy = (id: String, members: Seq[Int]) =>
      members.flatMap(t => columns.get((Storage.Dispatch, id, t)).map(col => (col, weight(t))))

    ceilings.foreach { (id, fraction) =>
      val pNom = table.float("p_nom", id)
      weeks.foreach { (key, members) =>
        val terms = weeklyEnergy(id, members)
        if terms.nonEmpty then
          builder.lessThan(terms, fraction * pNom * weeklyHours(key))
      }
    }

    if !config.bypassSpill.active then return

    // PyPSA only creates `spill` for a unit with inflow. Absent for all of them
    // means the request cannot be honoured at all, which NordPSA prints a warning
    // for rather than failing -- the network is legitimately spill-free.
    val spilling = ceilings.keys.filter { id =>
      snapshots.exists(t => columns.contains((Storage.Spill, id, t)))
    }.toSeq
    if spilling.isEmpty then return

    val below = config.bypassSpill.thresholdBelowMax
    spilling.foreach { id =>
      val pNom     = table.float("p_nom", id)
      val fraction = ceilings(id)
      val kappa = config.bypassSpill.coefficientByZone
        .collectFirst { case (zone, value) if unitFor(zone) == id => value }
        .getOrElse(config.bypassSpill.coefficient)

      weeks.foreach { (key, members) =>
        val spillTerms = members.flatMap(t =>
          columns.get((Storage.Spill, id, t)).map(col => (col, weight(t))),
        )
        if spillTerms.nonEmpty then
          // spill_week − κ·prod_week >= −κ·threshold, threshold measured down
          // from this unit's own ceiling. Written as one row with the production
          // terms moved across rather than as a bound on a difference, because
          // `spill` and `p_dispatch` are separate columns.
          val production = weeklyEnergy(id, members).map((col, w) => (col, -kappa * w))
          val threshold  = (fraction - below) * pNom * weeklyHours(key)
          builder.greaterThan(spillTerms ++ production, -kappa * threshold)
      }
    }

  private sealed trait Window
  private case object Daily  extends Window
  private case object Weekly extends Window

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
  ): Seq[(LocalDate, Seq[Int])] =
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
      val key = window match
        case Daily  => date
        case Weekly => date.`with`(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
      key -> t
    }
    // `groupBy` would lose the order the windows appear in, which makes the rows
    // arrive in a different sequence per run and the LP harder to diff.
    keyed.foldLeft(Vector.empty[(LocalDate, Vector[Int])]) { case (acc, (key, t)) =>
      acc.lastOption match
        case Some((last, members)) if last == key => acc.init :+ (key -> (members :+ t))
        case _                                   => acc :+ (key -> Vector(t))
    }
