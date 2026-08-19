package org.noaidi.lopf

import org.noaidi.network.*

/** Multi-investment periods: several build years optimised at once.
  *
  * A multi-period network's snapshots are `(period, timestep)` pairs. Three
  * things follow, and this module is all three:
  *
  *   - '''An asset exists only in some periods.''' `build_year <= period <
  *     build_year + lifetime`, and-ed with `active`. Outside that window PyPSA
  *     masks the asset's variables, which here means pinning its columns to
  *     zero — not dropping them, so the column layout is the one every other
  *     part of this model and `Sclopf`'s row-by-row copy already agree on.
  *   - '''Every cost in a period carries that period's weighting.'''
  *     `investment_periods.csv` holds an `objective` column, which is the
  *     discount factor, and PyPSA multiplies the snapshot's own objective
  *     weighting by it. So does the nodal price, which is divided by the
  *     product — see [[LopfResult.marginalPrice]].
  *   - '''`years` is a different weighting for a different purpose.''' It is how
  *     many years the period stands for, and it is what a global constraint sums
  *     against rather than what a cost is scaled by. Two columns in one small
  *     file that are easy to swap, in a repository that has already been caught
  *     doing exactly that with `snapshots.csv`'s three.
  *
  * ==What this cost before it was modelled==
  *
  * `investment_periods.csv` sat in the reader's set of non-component files and
  * '''no code read it''', so a multi-period network arrived at the builder
  * indistinguishable from an ordinary one and was solved as though every asset
  * existed from the start. On a two-period network whose cheap generator has
  * `build_year = 2040`, PyPSA spends 17,000 — the expensive unit carries the
  * whole of 2030 because the cheap one does not exist yet — and this port
  * returned '''2,000''', running the cheap generator ten years before it was
  * built, reporting `Optimal`.
  *
  * The reader compounded it. With `period` and `timestep` columns and no
  * `snapshot` column, the label came from the first column after the index, so
  * four snapshots read back as `2030, 2030, 2040, 2040` — two pairs of
  * duplicates — and `timestep` was parsed as though it were a weighting.
  */
object Periods:

  /** Whether an asset exists in a period.
    *
    * PyPSA's `build_year <= @period < build_year + lifetime`, evaluated on the
    * static frame. `lifetime` defaults to infinity, so an asset with a build
    * year and no lifetime is active from that year onwards; `build_year`
    * defaults to 0, so an asset with neither is active always. A component class
    * declaring neither attribute is active always too, which is what PyPSA's
    * `issubset` guard amounts to.
    */
  def activeIn(table: ComponentTable, id: String, period: String): Boolean =
    if !declares(table, "build_year") || !declares(table, "lifetime") then true
    else
      period.trim.toDoubleOption match
        // A period label PyPSA would not accept as a year. `reject` refuses such
        // a network before any build reaches here -- reading every asset as
        // present is the "cheaper than the truth" direction, so it is not a
        // safety net, only what a direct caller gets for a network that would
        // never have been solved.
        case None => true
        case Some(year) =>
          val built    = table.int("build_year", id).toDouble
          val lifetime = table.float("lifetime", id)
          // `NaN` is not the documented default -- infinity is -- but a file can
          // carry one, and every comparison against NaN is false, which would
          // silently retire the asset in every period.
          val end = if lifetime.isNaN then Double.PositiveInfinity else built + lifetime
          built <= year && year < end

  /** Whether an asset exists at a snapshot. Always true on a flat index. */
  def activeAt(network: Network, table: ComponentTable, id: String, snapshot: Int): Boolean =
    network.periodOf(snapshot).forall(activeIn(table, id, _))

  /** The factor every cost at this snapshot is multiplied by.
    *
    * The snapshot's own `objective` weighting times its period's. One accessor
    * rather than two multiplications spread through the builder, because the
    * price recovery has to divide by exactly the same thing and the two going
    * out of step is not visible in the objective.
    */
  def objectiveWeight(network: Network, snapshot: Int): Double =
    network.weighting("objective", snapshot) * network.periodObjectiveWeighting(snapshot)

  /** Refuse the parts of multi-period that are still not built.
    *
    * Narrower than it was: this used to refuse every multi-period network
    * outright. What is left is the combinations whose formulation differs from
    * the single-period one rather than merely being weighted differently.
    */
  def reject(network: Network, refuse: String => Nothing): Unit =
    if !network.isMultiPeriod then return

    // A period label that is not a year. `activeIn` compares `build_year <=
    // period < build_year + lifetime`, which needs a number; with none it reads
    // every asset as present, so a network declaring e.g. `2030-01` would be
    // solved with every build year ignored -- the "cheaper than the truth"
    // direction refused everywhere else here. PyPSA evaluates that expression
    // through `static.eval` and raises rather than admit a non-numeric label.
    network.investmentPeriods.foreach { period =>
      if period.trim.toDoubleOption.isEmpty then
        refuse(
          s"investment_periods.csv declares period '$period', which is not a year; no build year " +
            "can be compared against it and every asset would read as built"
        )
    }

    // A snapshot in no declared period has no weighting and no activity window,
    // so every asset would read as present and the costs would be unscaled.
    network.snapshots.indices.foreach { t =>
      val period = network.snapshotPeriods(t)
      if !network.investmentPeriods.contains(period) then
        refuse(
          s"snapshot $t is in period '$period', which investment_periods.csv does not declare; " +
            "its costs would carry no period weighting and every asset would read as built"
        )
    }

    // Capacity expansion across periods is a different model, not this one with
    // an extra factor: PyPSA gives each build year its own asset and the choice
    // of *when* to build interacts with the activity window and the discounting.
    // `Expansion` already refuses `overnight_cost`, which is the annuitised half
    // of it; this is the rest.
    Expansion.nominalAttribute.foreach { (component, _) =>
      network.table(component).foreach { table =>
        Expansion.extendables(table).headOption.foreach { id =>
          refuse(
            s"$component '$id' is extendable on a multi-period network; capacity expansion across " +
              "investment periods is not modelled, only dispatch within them"
          )
        }
      }
    }

    // `max_growth` limits how much of a carrier a period may add, and
    // `max_relative_growth` scales that limit by what the carrier already had.
    // The gate is `max_growth` alone: PyPSA selects `carrier_i = max_growth[
    // max_growth != inf]` and reads `max_relative_growth` only for the carriers
    // that filter picked, clipped at zero (`optimization/global_constraints.py`,
    // `define_growth_limit`). Testing the two independently refused *every*
    // multi-period network carrying an ordinary `carriers.csv`, because
    // `max_relative_growth` defaults to 0.0 -- which is finite, so "no relative
    // limit" read as a limit of nothing, and the refusal said so.
    //
    // They only bind on an extendable network, which is refused just above --
    // but the refusal is written here rather than left implicit, since
    // "unreachable because something else refuses it" is exactly the reasoning
    // the schema sweep was built to stop trusting.
    network.table("Carrier").foreach { carriers =>
      if declares(carriers, "max_growth") then
        carriers.ids.foreach { id =>
          val growth = carriers.float("max_growth", id)
          if growth.isFinite then
            val relative =
              if declares(carriers, "max_relative_growth") then
                carriers.float("max_relative_growth", id)
              else 0.0
            refuse(
              s"Carrier '$id' sets max_growth = $growth, with max_relative_growth = $relative, " +
                "which limits capacity added between investment periods; that is an expansion " +
                "constraint and expansion is refused here"
            )
        }
    }

    // A global constraint scoped to one period. This one was *ruled safe* in the
    // schema sweep on the grounds that a multi-period network was refused
    // outright, which stopped being true in this change -- so it is the ledger
    // entry that turned into a gap the moment the refusal above narrowed.
    //
    // Left unread it is not conservative: a CO2 cap meant for 2040 alone would
    // be applied to the whole horizon, which is a *tighter* constraint than the
    // network states and makes the answer dearer, or -- read the other way, as a
    // cap per period rather than in total -- looser. Neither has a defensible
    // sign, and PyPSA builds a separate row per scoped period.
    network.table("GlobalConstraint").foreach { constraints =>
      constraints.ids.foreach { id =>
        if declares(constraints, "investment_period") then
          val scope = constraints.string("investment_period", id).trim
          if scope.nonEmpty && scope.toLowerCase != "nan" then
            refuse(
              s"global constraint '$id' is scoped to investment period '$scope'; only a " +
                "horizon-wide constraint is built here, which would apply its cap to every period"
            )
      }
    }

    // Two row families are built once over the whole horizon rather than once
    // per period, so an asset whose activity window is not the whole horizon
    // changes rows this model does not rebuild. Pinning the asset's columns to
    // zero is enough for a *bound*; it is not enough for a row whose shape
    // depends on which assets exist.
    //
    //   - Kirchhoff's voltage law. `Cycles.basis` is computed on the
    //     whole-horizon topology and the same rows are emitted at every
    //     snapshot. PyPSA rebuilds the cycle matrix per period --
    //     `define_kirchhoff_voltage_constraints` loops `sns.unique("period")`
    //     and calls `n.cycle_matrix(investment_period=period)`, "reflecting the
    //     changing network topology over time". A line built in 2040 has its
    //     flow correctly pinned to zero in 2030, but its 2040 cycle row is still
    //     emitted there, collapsing to `x_A*f_A + x_B*f_B = 0` over what is
    //     really a radial path: zero flow forced along it, or 2030 infeasible.
    //   - Ramp limits. PyPSA masks every ramp row by `c.da.active`. Here a row
    //     spanning a period boundary ties the first snapshot after an asset is
    //     built to the zero it was pinned to before, so a unit may not start at
    //     more than one period's worth of ramp.
    //
    // The storage and store energy balance is the third such family and is *not*
    // refused: its rows are emitted over each asset's active snapshots, which is
    // PyPSA's `mask=active` exactly. That was worth doing rather than refusing
    // because the alternative -- pinned columns and a row everywhere -- reads as
    // masking and is not: at the first snapshot after a unit retires the row
    // collapses to `eff_stand · soc(t-1) = 0`, forcing it empty at its last
    // active snapshot. See `Lopf.build`.
    //
    // Refused rather than approximated, until the basis is rebuilt per period.
    // The staged line build is the canonical multi-investment case, so this is
    // not a corner: `investment-periods` is single-bus only because it is the
    // one fixture, not because branches are unusual.
    val horizon = network.snapshots.indices

    // Membership rather than role: a passive branch in no cycle contributes no
    // Kirchhoff row, so pinning its flow to zero is the whole of its masking and
    // there is nothing to refuse. Computed lazily -- a network with no
    // partly-built asset never asks, and the basis is the expensive part of the
    // build.
    lazy val cycled: Set[(String, String)] =
      Cycles.basis(network).flatMap(_.terms.map((component, id, _) => (component, id))).toSet

    network.tables.values.foreach { table =>
      table.ids.foreach { id =>
        val absent = network.investmentPeriods.filterNot(activeIn(table, id, _))
        if absent.nonEmpty then
          val window = s"is not active in investment period(s) ${absent.mkString(", ")}"
          if Role.of(table.spec) == Role.PassiveBranch && cycled.contains((table.spec.name, id)) then
            refuse(
              s"${table.spec.name} '$id' $window; the cycle basis is built once over the whole " +
                "horizon, so its cycle rows would still be imposed in the periods it does not exist"
            )
          else if Ramps.limited(table, id, horizon) then
            refuse(
              s"${table.spec.name} '$id' is ramp-limited and $window; the ramp rows are not masked " +
                "by the activity window, so the period it is built in would be entered at a ramp " +
                "from zero rather than freely"
            )
      }
    }

    // Storage that cycles *per period* is refused by `Storage.reject` and
    // `Stores.reject`, on every network rather than only a multi-period one.
    // This carried its own copy of that check and covered two of the four flags,
    // which reads as if the other two were handled: PyPSA treats an asset as
    // per-period when *either* the cyclic or the initial flag is set (`CP | IP`
    // in `define_storage_unit_constraints`), so a copy listing only the cyclic
    // half is a shorter list of the same gap, not a narrower gap.

  private def declares(table: ComponentTable, attribute: String): Boolean =
    table.spec.attribute(attribute).isDefined || table.static.contains(attribute)
