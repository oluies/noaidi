package org.noaidi.lopf

import org.noaidi.network.*

/** A `Store`: energy with no power rating.
  *
  * Not a [[Storage]] unit under another name, and the three differences are what
  * the `store-bank` fixture exists to pin:
  *
  *   - '''One signed power variable, not two.''' A Store has no charging or
  *     discharging efficiency, so there is nothing for two variables to carry
  *     differently. `p > 0` discharges into the bus and `p < 0` charges from it.
  *   - '''No power rating.''' PyPSA generates no operational bound for `Store-p`
  *     at all. How fast a store can move energy follows only from its energy
  *     band and the elapsed hours. Bounding `p` by `e_nom` — the obvious
  *     reading — is strictly tighter and gives a more expensive answer.
  *   - '''The energy band is `[e_min_pu, e_max_pu] · e_nom`,''' not `[0, e_nom]`.
  *
  * The energy balance:
  *
  * {{{
  * e(t) = (1 − standing_loss)^eh · e(t−1)  −  eh · p(t)
  * }}}
  *
  * with `e(t−1)` at the first snapshot being either the last snapshot's variable
  * (`e_cyclic`) or absent, with `e_initial` moving to the right-hand side. Note
  * the sign: `p` is subtracted, because a positive `p` is energy leaving the
  * store for the bus. Getting that backwards produces a store that charges when
  * it should discharge and still balances every bus.
  */
object Stores:

  val Energy = "Store-e"
  val Power  = "Store-p"

  /** Whether a store's first snapshot wraps to its last.
    *
    * Read through the column rather than the schema default, for the same reason
    * as [[Storage.isCyclic]]: a network that never sets it has no column, and
    * reading a missing column as anything but false would make every store
    * cyclic.
    */
  def isCyclic(table: ComponentTable, id: String): Boolean =
    table.static.contains("e_cyclic") && table.bool("e_cyclic", id)

  /** Refuse the store features this model does not build. */
  def reject(table: ComponentTable): Unit =
    def refuse(message: String): Nothing = throw new Lopf.UnsupportedNetwork(message)

    // `e_set` pins the energy level at a snapshot; dropping it leaves the
    // trajectory free, which is a cheaper problem returning `Optimal`.
    val pinned = table.ids.filter { id =>
      table.series.get("e_set").exists(_.covers(id)) ||
      (table.static.contains("e_set") && table.float("e_set", id).isFinite)
    }
    if pinned.nonEmpty then
      refuse(
        s"Store '${pinned.head}' sets e_set, which pins its energy at a snapshot; that constraint " +
          "is not built here, and dropping it would leave the trajectory free"
      )

    Seq("e_cyclic_per_period", "e_initial_per_period").foreach { attribute =>
      val set = table.ids.filter(id => table.static.contains(attribute) && table.bool(attribute, id))
      if set.nonEmpty then
        refuse(
          s"Store '${set.head}' sets $attribute, which is a multi-investment-period concept; this " +
            "model has a single horizon"
        )
    }

    val quadratic = table.ids.filter { id =>
      (table.static.contains("marginal_cost_quadratic") &&
        table.float("marginal_cost_quadratic", id) != 0.0) ||
      table.series.get("marginal_cost_quadratic").exists(_.covers(id))
    }
    if quadratic.nonEmpty then
      refuse(
        s"Store '${quadratic.head}' has a non-zero marginal_cost_quadratic; the objective here is " +
          "linear"
      )

    table.ids.foreach { id =>
      val standing = table.float("standing_loss", id)
      if standing < 0.0 || standing >= 1.0 then
        refuse(s"Store '$id' has standing_loss = $standing, which is not a fraction below 1")
    }
