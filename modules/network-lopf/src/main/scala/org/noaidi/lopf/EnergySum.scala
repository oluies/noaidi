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
  * budget it does not have. On a fixture capping a cheap generator at 200 MWh
  * the dropped rows under-priced the answer by 116,644 against PyPSA, which is
  * the largest single discrepancy the schema sweep has turned up.
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
  * ==Only Generator==
  *
  * `e_sum_max`/`e_sum_min` are declared on Generator alone — a Link has neither,
  * and a Store's energy is bounded by `e_nom` through its own rows. [[constrain]]
  * checks the spec rather than the component name so that a schema which grows
  * the attribute elsewhere is picked up rather than silently skipped.
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
