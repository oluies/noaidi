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
  * Everything that makes capital cost something other than a per-unit constant:
  * `overnight_cost` with its annuitisation over `lifetime` and `discount_rate`,
  * modular capacity (`p_nom_mod`, an integer number of blocks), and
  * multi-investment-period build years. None of the goldens use them, and each
  * would be untested arithmetic that returns a plausible number.
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

  /** Cost per unit of capacity over the modelled horizon.
    *
    * `capital_cost` plus `fom_cost`, which is PyPSA's `periodized_cost` in the
    * case where `overnight_cost` is unset — and it is unset by default, being
    * NaN rather than zero. [[reject]] refuses the annuitised case rather than
    * letting a NaN reach the objective.
    */
  def periodizedCost(table: ComponentTable, id: String): Double =
    val capital = table.float("capital_cost", id)
    val fom     = if table.static.contains("fom_cost") then table.float("fom_cost", id) else 0.0
    (if capital.isFinite then capital else 0.0) + (if fom.isFinite then fom else 0.0)

  /** What PyPSA subtracts from the objective: the capital cost of capacity that
    * already exists, over the extendable components only.
    */
  def objectiveConstant(network: Network): Double =
    nominalAttribute.keys.toIndexedSeq.sorted.flatMap { component =>
      network.table(component).toIndexedSeq.flatMap { table =>
        extendables(table).map(id => periodizedCost(table, id) * table.float(nominalAttribute(component), id))
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

    nominalAttribute.foreach { (component, attribute) =>
      network.table(component).foreach { table =>
        val ext = extendables(table)
        if ext.nonEmpty then
          // An overnight cost is annuitised over `lifetime` at `discount_rate`
          // and scaled by the modelled horizon. Reading it as a per-unit cost is
          // wrong by the annuity factor -- a factor of ten on a 25-year asset --
          // and NaN is its *default*, so this cannot be handled by falling back.
          ext.foreach { id =>
            if table.static.contains("overnight_cost") && table.float("overnight_cost", id).isFinite then
              refuse(
                s"$component '$id' sets overnight_cost, which PyPSA annuitises over lifetime and " +
                  "discount_rate; only capital_cost, already scaled to the horizon, is modelled"
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
