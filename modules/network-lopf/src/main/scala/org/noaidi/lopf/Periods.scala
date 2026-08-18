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
        // A period label PyPSA would not accept as a year. Rather than guess,
        // treat the asset as present: refusing here would reject a network on
        // the strength of a label this module failed to parse.
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

    // `max_growth` and `max_relative_growth` limit how fast a carrier's capacity
    // may grow between periods. They only bind on an extendable network, which
    // is refused just above -- but the refusal is written here rather than left
    // implicit, since "unreachable because something else refuses it" is exactly
    // the reasoning the schema sweep was built to stop trusting.
    network.table("Carrier").foreach { carriers =>
      Seq("max_growth", "max_relative_growth").foreach { attribute =>
        carriers.ids.foreach { id =>
          if declares(carriers, attribute) then
            val growth = carriers.float(attribute, id)
            if growth.isFinite then
              refuse(
                s"Carrier '$id' sets $attribute = $growth, which limits capacity added between " +
                  "investment periods; that is an expansion constraint and expansion is refused here"
              )
        }
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

    // Storage that cycles *per period* rather than over the whole horizon is a
    // different set of energy-balance rows -- the wrap closes at each period's
    // last snapshot instead of the horizon's. PyPSA defaults both flags to
    // false, so an ordinary multi-period network does not set them.
    Seq("StorageUnit" -> "cyclic_state_of_charge_per_period", "Store" -> "e_cyclic_per_period")
      .foreach { (component, attribute) =>
        network.table(component).foreach { table =>
          table.ids.foreach { id =>
            if declares(table, attribute) && table.bool(attribute, id) then
              refuse(
                s"$component '$id' sets $attribute, so its state wraps within each investment " +
                  "period rather than across the horizon; only the horizon-wide cycle is modelled"
              )
          }
        }
      }

  private def declares(table: ComponentTable, attribute: String): Boolean =
    table.spec.attribute(attribute).isDefined || table.static.contains(attribute)
