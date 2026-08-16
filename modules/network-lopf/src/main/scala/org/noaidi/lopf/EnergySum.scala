package org.noaidi.lopf

import org.noaidi.network.*
import org.noaidi.prima.LpBuilder

/** A budget on a generator's energy over the whole horizon.
  *
  * {{{
  * e_sum_min  <=  Σ_t  weighting(t) · p(t)  <=  e_sum_max
  * }}}
  *
  * Two rows per generator at most, and until now zero: `e_sum_max` and
  * `e_sum_min` were named nowhere in this port. Their defaults are `+inf` and
  * `−inf`, so nothing about an unset one is visible, and a set one was simply
  * dropped — the LP stayed feasible and returned a schedule that spends a fuel
  * budget it does not have. On the `energy-budget` fixture the dropped rows
  * under-price the answer by '''23,280''' — 7,320 against PyPSA's 30,600 — which
  * is the largest single discrepancy the schema sweep has turned up.
  *
  * ==The weighting is `generators`, not `objective`==
  *
  * PyPSA sums `p` against `snapshot_weightings.generators`, the same column the
  * emissions cap uses and a different one from the cost. All three are separate
  * columns of `snapshots.csv`, every fixture here holds them equal at 1.0, and
  * so no comparison in this repository can tell a mistake here from a correct
  * reading. That is the reason to read the column rather than reach for the one
  * already in scope.
  *
  * ==Only Generator, and where that is enforced==
  *
  * `e_sum_max`/`e_sum_min` are declared on Generator alone — a Link has neither,
  * and a Store's energy is bounded by `e_nom` through its own rows. PyPSA is
  * blunter still: `define_total_supply_constraints` takes `component` with a
  * hardcoded default of `"Generator"` and is called only that way.
  *
  * [[constrain]] tests the '''spec''' rather than the component name, so a
  * schema that grows the attribute onto another dispatchable class is picked up
  * rather than silently skipped. That guarantee reaches exactly as far as the
  * tables [[Lopf]] drives this over — the ones whose dispatch variable is keyed
  * by the component name, which is the same set the ramp rows use. StorageUnit
  * and Store are deliberately outside it: their power is keyed by
  * [[Storage.Dispatch]] and [[Stores.Power]], so the column lookup here would
  * not find them, and a claim to cover them would be false rather than merely
  * unexercised.
  */
object EnergySum:

  /** Emit the energy-budget rows for one table.
    *
    * `column` resolves the dispatch variable, passed in for the same reason
    * [[Ramps.constrain]] takes one: this stays independent of how [[Lopf]] keys
    * its variable map.
    *
    * Comparisons against the infinities, not `isFinite`. A NaN budget satisfies
    * neither `< inf` nor `> -inf` and so emits nothing, which is PyPSA's
    * behaviour for the same reason — and `isFinite` would have made a NaN emit a
    * row with a NaN right-hand side, turning a data error into an unsolvable
    * problem rather than an ignored attribute.
    */
  def constrain(
      table: ComponentTable,
      network: Network,
      snapshots: Range,
      column: (String, Int) => Int,
      builder: LpBuilder,
  ): Unit =
    if table.spec.attribute("e_sum_max").isEmpty && table.spec.attribute("e_sum_min").isEmpty then
      return

    table.ids.foreach { id =>
      val maximum = if table.spec.attribute("e_sum_max").isDefined then
        table.float("e_sum_max", id)
      else Double.PositiveInfinity
      val minimum = if table.spec.attribute("e_sum_min").isDefined then
        table.float("e_sum_min", id)
      else Double.NegativeInfinity

      val capped   = maximum < Double.PositiveInfinity
      val floored  = minimum > Double.NegativeInfinity

      if capped || floored then
        // One term per snapshot, over the whole horizon. This is the only row in
        // the model that spans every snapshot for a single entity, which is what
        // makes it a budget rather than a limit.
        val terms = snapshots.map(t => (column(id, t), network.weighting("generators", t)))
        if capped then builder.lessThan(terms, maximum)
        if floored then builder.greaterThan(terms, minimum)
    }
