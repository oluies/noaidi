package org.noaidi.lopf

import org.noaidi.network.*

/** Capacity as a decision rather than a given.
  *
  * A dispatch model reads `p_nom` and bounds output by it. An expansion model
  * makes `p_nom` a variable, charges `capital_cost` for it, and turns every
  * operational bound into a constraint against that variable:
  *
  * {{{
  * min_pu · capacity  <=  dispatch  <=  max_pu · capacity
  * }}}
  *
  * which is why the dispatch variable itself becomes unbounded — its limits move
  * from the column into two rows per snapshot. That is PyPSA's formulation, and
  * following it matters here more than usual because the objective it produces
  * is not the number anyone would guess.
  *
  * ==The objective is incremental, and reads negative==
  *
  * PyPSA charges capital cost on the '''whole''' optimal capacity and then
  * subtracts the capital cost of what was already there:
  *
  * {{{
  * objective          = Σ capital_cost · capacity  −  Σ capital_cost · p_nom  +  operating cost
  * objective_constant = Σ capital_cost · p_nom                       (over extendables only)
  * total_system_cost  = objective + objective_constant
  * }}}
  *
  * So `objective` is the cost of the *change*, and on `ac-dc-meshed` it is
  * −3,474,256 against a total system cost of 18,441,021 — the optimum builds
  * less than the network came with. An implementation that reported the total
  * would be out by 21.9 million and still look plausible, which is why all three
  * numbers are in the goldens.
  *
  * ==What is not modelled==
  *
  * Modular capacity -- `p_nom_mod`, an integer number of blocks -- which is a
  * mixed-integer problem rather than this linear one.
  *
  * `overnight_cost` was here and is built now: see [[periodizedCost]] and
  * [[annuity]], with `annuitised-capital` as the fixture. Multi-period build
  * years were here too and are built. What is left of the annuitised case is
  * three refusals about values rather than features -- a missing
  * `discount_rate`, a non-positive `lifetime`, and investment periods of
  * unequal length -- each of which makes the coefficient not a number where
  * PyPSA either warns and carries a NaN or raises outright.
  */
object Expansion:

  /** The capacity attribute of each component that can be expanded.
    *
    * PyPSA's `nominal_attrs`. A Store's is `e_nom`, an *energy* rather than a
    * power — it has no power rating at all.
    */
  val nominalAttribute: Map[String, String] = Map(
    "Generator"   -> "p_nom",
    "Line"        -> "s_nom",
    "Transformer" -> "s_nom",
    "Link"        -> "p_nom",
    "StorageUnit" -> "p_nom",
    "Store"       -> "e_nom",
  )

  /** The variable-map key for a component's capacity column. */
  def capacityKey(component: String): String = s"$component-${nominalAttribute(component)}"

  /** Snapshot index for a variable that has none.
    *
    * A capacity is chosen once for the whole horizon, so it does not fit the
    * variable map's `(component, entity, snapshot)` key. Rather than widen that
    * key -- which `Sclopf` and every accessor read through -- capacity columns
    * are filed under a snapshot that cannot collide with a real one.
    */
  val NoSnapshot: Int = -1

  /** The per-unit operational bounds of an extendable entity, by variable.
    *
    * One entry per variable the capacity bounds. A passive branch has a single
    * symmetric flow; a storage unit has three, and their lower bounds are all
    * zero, so only the upper ones need a row.
    */
  def operationalBounds(
      table: ComponentTable,
      id: String,
      snapshot: Int,
  ): IndexedSeq[(String, Double, Double)] =
    table.spec.name match
      case "Line" | "Transformer" =>
        val maxPu = table.valueAt("s_max_pu", id, snapshot)
        IndexedSeq((table.spec.name, -maxPu, maxPu))
      case "StorageUnit" =>
        IndexedSeq(
          (Storage.Dispatch, Double.NegativeInfinity, table.valueAt("p_max_pu", id, snapshot)),
          (Storage.Store, Double.NegativeInfinity, -table.valueAt("p_min_pu", id, snapshot)),
          (Storage.SoC, Double.NegativeInfinity, table.float("max_hours", id)),
        )
      case "Store" =>
        // The energy level, and both ends of it. A Store's `p` is deliberately
        // absent: PyPSA generates no operational bound for it at all, so how
        // fast a store moves energy follows only from this band and the elapsed
        // hours. Bounding `p` by `e_nom` would be strictly tighter and would
        // look entirely reasonable.
        IndexedSeq(
          (
            Stores.Energy,
            table.valueAt("e_min_pu", id, snapshot),
            table.valueAt("e_max_pu", id, snapshot),
          )
        )
      case other =>
        IndexedSeq(
          (other, table.valueAt("p_min_pu", id, snapshot), table.valueAt("p_max_pu", id, snapshot))
        )

  /** Whether this entity's capacity is a decision. */
  def isExtendable(table: ComponentTable, id: String): Boolean =
    nominalAttribute.get(table.spec.name).exists { attribute =>
      val flag = s"${attribute}_extendable"
      table.static.contains(flag) && table.bool(flag, id)
    }

  /** Entities of this table whose capacity is a decision, in table order. */
  def extendables(table: ComponentTable): IndexedSeq[String] =
    if !nominalAttribute.contains(table.spec.name) then IndexedSeq.empty
    else table.ids.filter(isExtendable(table, _))

  /** Hours in a year, as PyPSA's `constants.HOURS_PER_YEAR` defines it. */
  private val HoursPerYear = 8760.0

  /** The modelled horizon in years: the `objective` weightings summed and divided by 8760.
    *
    * `Network.nyears`, and it is the factor that makes an annuitised cost comparable to a
    * horizon of six snapshots. An annuity is a cost PER YEAR; a fixture whose snapshots carry
    * a weighting of 1.0 each models six hours, which is 0.07% of a year, so its capital term
    * is 0.07% of the annual one. Getting this wrong by leaving it out would overcharge
    * capital by a factor of 1,460 on such a network -- not a rounding error, and not visible
    * on a fixture whose weightings happen to sum to 8,760.
    *
    * Per period under multi-investment, where PyPSA groups the sum by period. [[nyears]]
    * returns the distinct values, because an annuitised cost is only well defined when they
    * agree -- see [[rejectUnequalHorizons]].
    */
  def nyears(network: Network): IndexedSeq[Double] =
    if !network.isMultiPeriod then
      IndexedSeq(network.snapshots.indices.map(network.weighting("objective", _)).sum / HoursPerYear)
    else
      network.snapshotPeriods.distinct.map { period =>
        network.snapshots.indices
          .filter(t => network.periodOf(t).contains(period))
          .map(network.weighting("objective", _))
          .sum / HoursPerYear
      }

  /** PyPSA's annuity factor: `r / (1 - (1 + r)^-n)`, with three special cases.
    *
    * {{{
    * r == 0, n finite    ->  1 / n           simple depreciation
    * r == 0, n infinite  ->  0               no interest and no end, so no annual cost
    * n infinite, r != 0  ->  max(r, 0)       a perpetuity
    * }}}
    *
    * All three are upstream's (`costs.py`), and the second and third are reachable by
    * default rather than exotic: `lifetime` defaults to '''+inf''', so an `overnight_cost`
    * set with nothing else is a perpetuity at the discount rate. A negative rate is allowed
    * and clipped to zero in the infinite case only, which is PyPSA's `clip(lower=0)` and
    * reads as "a perpetual asset cannot be cheaper than free".
    *
    * The finite branch is written with `math.pow(1 + r, -n)` where PyPSA writes
    * `1 / (1 + r) ** n`. Same value; said here because the two spellings differ in the last
    * bit for some inputs and the goldens are compared to 1e-6 relative.
    */
  def annuity(discountRate: Double, lifetime: Double): Double =
    if discountRate == 0.0 then (if lifetime.isInfinite then 0.0 else 1.0 / lifetime)
    else if lifetime.isInfinite then math.max(discountRate, 0.0)
    else discountRate / (1.0 - math.pow(1.0 + discountRate, -lifetime))

  /** The annuitised capital cost alone, '''without''' `fom_cost`.
    *
    * PyPSA keeps two numbers here and the objective and the statistics read different ones:
    *
    * {{{
    * Components.periodized_cost  ->  fom_cost = static["fom_cost"]   the objective's
    * Components.capital_cost     ->  fom_cost = None                 the statistic's
    * }}}
    *
    * So `statistics.capex` is `p_nom_opt x capital_cost` where `capital_cost` is the
    * annuitised overnight cost with no fixed O&M, while the objective charges the same
    * annuity plus `fom_cost`. Two coefficients, one function, distinguished by one argument.
    *
    * Found by implementing the annuity: `network-stats` loaded the raw `capital_cost` column,
    * which agreed with upstream on every golden for as long as `overnight_cost` was refused
    * and then reported 9,999,900 against PyPSA's 16,189.99 on `annuitised-capital` --
    * `both`'s ignored `capital_cost` of 99,999 times its 100 MW.
    */
  def capitalCost(network: Network, table: ComponentTable, id: String): Double =
    val overnight =
      if table.static.contains("overnight_cost") then table.float("overnight_cost", id)
      else Double.NaN
    if !overnight.isFinite then
      val capital = table.float("capital_cost", id)
      if capital.isFinite then capital else 0.0
    else
      // `horizon` rather than `nyears(network).head`, so the precondition is checked where it
      // is relied on. `reject` refuses a network whose periods disagree, but this is reachable
      // from `network-stats` with no `reject` having run -- `Stats.of` does not call it.
      overnight * annuity(table.float("discount_rate", id), table.float("lifetime", id)) *
        horizon(network)

  /** The single modelled horizon, or a refusal if the investment periods disagree about it.
    *
    * Separate from [[nyears]] because the two answer different questions: that one reports
    * what the periods model, and this one asserts there is only one answer before an annuity
    * is scaled by it. Reachable from `network-stats`, which never calls [[reject]].
    */
  private def horizon(network: Network): Double =
    val years = nyears(network).distinct
    if years.length > 1 then
      throw new Lopf.UnsupportedNetwork(
        s"an annuitised cost needs one modelled horizon and this network's periods model " +
          s"${years.map(y => f"$y%.6f").mkString(", ")} years"
      )
    years.head

  /** Cost per unit of capacity over the modelled horizon: PyPSA's `periodized_cost`.
    *
    * {{{
    * overnight_cost unset  ->  capital_cost + fom_cost
    * overnight_cost set    ->  overnight_cost x annuity(discount_rate, lifetime) x nyears
    *                           + fom_cost
    * }}}
    *
    * ==`overnight_cost` wins and `capital_cost` is ignored==
    *
    * Not averaged, not added, and not refused: upstream's consistency check '''warns''' that
    * "when 'overnight_cost' is provided, it takes precedence and capital_cost is ignored",
    * and `periodized_cost` reads one branch or the other. A network setting both is one PyPSA
    * has an answer for, so this has the same answer rather than a refusal.
    *
    * `fom_cost` is added in '''both''' branches -- it is a cost for the horizon already, like
    * `capital_cost`, and is not annuitised.
    *
    * ==What is refused, and why each would otherwise be silent==
    *
    * See [[reject]]. The three cases are a missing `discount_rate`, a non-positive
    * `lifetime`, and investment periods of unequal length, and not one of them is an error
    * upstream on a real network -- each produces a coefficient instead. The first matters
    * most because `discount_rate` defaults to NaN, so an `overnight_cost` set on its own
    * reaches it.
    *
    * This is `capitalCost` plus `fom_cost` and nothing else, which is how upstream relates
    * its own two: `periodized_cost` with `fom_cost` set against the same call with
    * `fom_cost = None`. Written as the sum rather than as a second copy of the branch logic,
    * after a review found two copies of the overnight-wins precedence to keep in step.
    */
  def periodizedCost(network: Network, table: ComponentTable, id: String): Double =
    val fom =
      if table.static.contains("fom_cost") then table.float("fom_cost", id) else 0.0
    capitalCost(network, table, id) + (if fom.isFinite then fom else 0.0)

  /** The factor a capacity's [[periodizedCost]] is multiplied by.
    *
    * On a flat index this is 1.0, which is PyPSA's `cost_weight =
    * active_ext.any(dim="snapshot")` on a network where every extendable entity
    * is active somewhere -- an inactive one has been dropped before reaching
    * here.
    *
    * On a multi-period index it is the sum of the `objective` weightings of the
    * periods the asset is active in:
    *
    * {{{
    * cost_weight = (active_ext.groupby("period").any("snapshot") * period_weight).sum("period")
    * }}}
    *
    * Three things follow, and the first is the one that makes multi-period
    * expansion a weighting rather than a new formulation:
    *
    *   - There is still '''one capacity variable per asset'''. PyPSA does not give a
    *     build year its own column; the `build_year` on the static frame says
    *     which periods the asset exists in, and the coefficient above charges its
    *     capital once per such period. So the column layout this model already
    *     has is the right one.
    *   - The weighting is the '''period''' objective weighting alone, not
    *     [[Periods.objectiveWeight]]. Capital is charged per period, not per
    *     snapshot, so the snapshot weighting does not enter -- multiplying by it
    *     would scale capital by however many snapshots the period happens to
    *     carry, which on a representative-period study is an arbitrary number.
    *   - The sum runs over the periods the '''snapshots''' carry, not the periods
    *     `investment_periods.csv` declares. PyPSA sums over `window.periods`,
    *     which is the index, and it tolerates a declared period no snapshot
    *     belongs to -- counting such a period would charge capital for a period
    *     the model does not solve.
    */
  def costWeight(network: Network, table: ComponentTable, id: String): Double =
    if !network.isMultiPeriod then 1.0
    else
      network.snapshotPeriods.distinct
        .filter(Periods.activeIn(table, id, _))
        .map(network.periodWeighting("objective", _))
        .sum

  /** What PyPSA subtracts from the objective: the capital cost of capacity that
    * already exists, over the extendable components only.
    *
    * '''Zero on a multi-period network''', which is not an approximation but what
    * PyPSA does. `define_objective` computes the per-asset `weighted_cost` in both
    * branches and appends it to `terms` only in the single-period one, so
    * `n._objective_constant` comes out 0.0 whenever `multi_investment_periods` is
    * set. Measured on `investment-periods-expansion`: 3,000 without the flag and
    * 0.0 with it, for the same network. It reads like an oversight upstream, and
    * reproducing it is still the right call -- the alternative is an objective
    * that differs from the pinned PyPSA's by the sunk capital on every
    * multi-period expansion network, which is the one number such a fixture is
    * compared on.
    */
  def objectiveConstant(network: Network): Double =
    if network.isMultiPeriod then 0.0
    else
      nominalAttribute.keys.toIndexedSeq.sorted.flatMap { component =>
        network.table(component).toIndexedSeq.flatMap { table =>
          extendables(table).map(id =>
            periodizedCost(network, table, id) * table.float(nominalAttribute(component), id))
        }
      }.sum

  /** Refuse the expansion features this model does not build.
    *
    * Each is silent if ignored rather than loud: an annuitised `overnight_cost`
    * read as a plain per-unit cost is off by the annuity factor, and a modular
    * capacity solved continuously returns a fractional number of blocks
    * reporting `Optimal`.
    */
  def reject(network: Network): Unit =
    def refuse(message: String): Nothing = throw new Lopf.UnsupportedNetwork(message)

    rejectUnequalHorizons(network, refuse)

    nominalAttribute.foreach { (component, attribute) =>
      network.table(component).foreach { table =>
        val ext = extendables(table)
        if ext.nonEmpty then
          // An overnight cost is annuitised over `lifetime` at `discount_rate`
          // and scaled by the modelled horizon. Reading it as a per-unit cost is
          // wrong by the annuity factor -- a factor of ten on a 25-year asset --
          // and NaN is its *default*, so this cannot be handled by falling back.
          ext.foreach { id =>
            // An overnight cost is annuitised over `lifetime` at `discount_rate` and scaled
            // by the modelled horizon -- see `periodizedCost`, which now builds it. What is
            // refused is the three shapes that make the result not a number.
            if table.static.contains("overnight_cost") && table.float("overnight_cost", id).isFinite
            then
              val rate     = table.float("discount_rate", id)
              val lifetime = table.float("lifetime", id)
              // `discount_rate` defaults to NaN, so this is the case an `overnight_cost` set
              // on its own reaches. PyPSA's consistency check only WARNS and its annuity then
              // returns NaN, which arrives in the objective as a coefficient -- a wrong answer
              // reported as `Optimal`, which is the direction this port refuses.
              if !rate.isFinite then
                refuse(
                  s"$component '$id' sets overnight_cost but no finite discount_rate, so its " +
                    "annuity is not a number. PyPSA warns and carries the NaN into the objective."
                )
              // Upstream does NOT raise here, and the first version of this comment said it
              // did. `costs.py` guards with `isinstance(lifetime, int | float) and
              // lifetime <= 0`, which is scalar-only, and `Components.periodized_cost`
              // always passes `static["lifetime"]` -- a Series. So the check never fires on
              // a component frame. Measured on a two-snapshot network with `lifetime = 0`:
              // `periodized_cost` returns `inf` and `optimize` returns `ok` with condition
              // `unknown` and an objective of 0.0. Worse than an error, and the reason the
              // refusal stays.
              //
              // An infinite lifetime is fine and is the DEFAULT -- it makes the annuity a
              // perpetuity at the rate.
              if lifetime <= 0.0 then
                refuse(
                  s"$component '$id' sets overnight_cost with lifetime = $lifetime, which has to " +
                    "be positive for an annuity to exist"
                )
            val modular = s"${attribute}_mod"
            if table.static.contains(modular) && table.float(modular, id) != 0.0 then
              refuse(
                s"$component '$id' sets $modular, so its capacity is an integer number of blocks; " +
                  "that is a mixed-integer problem and this builds a linear one"
              )
            val minimum = table.float(s"${attribute}_min", id)
            val maximum = table.float(s"${attribute}_max", id)
            if minimum > maximum then
              refuse(
                s"$component '$id' has ${attribute}_min = $minimum above ${attribute}_max = $maximum, " +
                  "so its capacity is infeasible"
              )
            if !(minimum >= 0.0) then
              refuse(s"$component '$id' has ${attribute}_min = $minimum, which is not a capacity")
          }
      }
    }

    // Committable is not checked here any more. This refused the committable
    // *and* extendable combination -- which needs a big-M tying a binary status
    // to a capacity variable -- and said in its comment that `UnitCommitment`
    // builds commitment without expansion and this builds expansion without
    // commitment. True, and it left the plain committable case unrefused and
    // silently solved as a linear program. `Commitment.reject` now covers every
    // committable entity, ahead of this, so the combination cannot reach here.

  /** Refuse an annuitised cost on investment periods of unequal length.
    *
    * `periodized_cost` raises on exactly this -- "overnight_cost cannot be used when
    * investment periods have different durations (nyears). Provide capital_cost instead, or
    * use investment periods with equal duration" -- because an annuity is a cost per year and
    * there is then no single number of years to scale it by.
    *
    * Reproduced as a refusal rather than left to produce a number, and the shape of the check
    * is upstream's: the periods' durations are collapsed to their distinct values and the
    * error fires when more than one survives, so two periods of equal length are fine however
    * many snapshots each carries.
    *
    * Unreachable from any committed fixture -- every multi-period golden weights its
    * snapshots equally -- which is why it is a named refusal rather than a tested branch.
    */
  private def rejectUnequalHorizons(network: Network, refuse: String => Nothing): Unit =
    if !network.isMultiPeriod then return
    val horizons = nyears(network).distinct
    if horizons.length <= 1 then return
    // Every id, not just the extendables, which is upstream's own scope: `periodized_cost`
    // guards on `overnight_cost.notna().any()` over the whole static column, so PyPSA raises
    // on a network where only a NON-extendable asset carries an overnight cost. Scoped to the
    // extendables, this port solved such a network and `Stats` then priced its capex at the
    // first period's horizon.
    val annuitised = nominalAttribute.keys.exists { component =>
      network.table(component).exists { table =>
        table.static.contains("overnight_cost") &&
          table.ids.exists(id => table.float("overnight_cost", id).isFinite)
      }
    }
    if annuitised then
      refuse(
        s"an overnight_cost is set and this network's investment periods model " +
          s"${horizons.map(y => f"$y%.6f").mkString(", ")} years, which are not equal. An annuity " +
          "is a cost per year and there is no single horizon to scale it by; PyPSA raises here " +
          "too, and says to provide capital_cost instead."
      )
